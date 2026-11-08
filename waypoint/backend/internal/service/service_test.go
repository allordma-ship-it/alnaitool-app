package service

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"math"
	"strconv"
	"testing"
	"time"

	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/domain"
)

func TestAvailableSeatsSegmentAware(t *testing.T) {
	bookings := []domain.SeatInterval{
		{Seats: 2, PickupFraction: 0.0, DropoffFraction: 0.5},
		{Seats: 1, PickupFraction: 0.4, DropoffFraction: 1.0},
	}
	cases := []struct {
		name     string
		from, to float64
		want     int
	}{
		{"full route sees peak overlap at 0.4", 0, 1, 1},
		{"first half before second booking", 0, 0.3, 2},
		{"after first dropoff", 0.5, 1, 3},
		{"touching dropoff boundary is free", 0.5, 0.7, 3},
		{"overlap window", 0.45, 0.55, 1},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := AvailableSeats(4, bookings, c.from, c.to); got != c.want {
				t.Fatalf("AvailableSeats(%v,%v) = %d, want %d", c.from, c.to, got, c.want)
			}
		})
	}
	if got := AvailableSeats(1, []domain.SeatInterval{{Seats: 3, PickupFraction: 0, DropoffFraction: 1}}, 0, 1); got != 0 {
		t.Fatalf("oversubscribed ride must report 0, got %d", got)
	}
}

func TestSegmentPricing(t *testing.T) {
	p := Pricing{FeeBps: 1000, FeeFixedCents: 50, CentsPerKm: 7}
	if got := p.SegmentPrice(2000, 0, 1); got != 2000 {
		t.Fatalf("full route price = %d", got)
	}
	if got := p.SegmentPrice(2000, 0.25, 0.75); got != 1000 {
		t.Fatalf("half route price = %d", got)
	}
	if got := p.SegmentPrice(2000, 0.5, 0.52); got != 200 {
		t.Fatalf("tiny segment must hit floor, got %d", got)
	}
	if got := p.SegmentPrice(150, 0.1, 0.2); got != 150 {
		t.Fatalf("floor must not exceed full price, got %d", got)
	}
	if got := p.ServiceFee(1000); got != 150 {
		t.Fatalf("service fee = %d", got)
	}
	s, minP, maxP := p.SuggestedPrice(230000)
	if s != 1600 || minP > s || maxP < s {
		t.Fatalf("suggested=%d min=%d max=%d", s, minP, maxP)
	}
}

func encodeValue(v int64) string {
	v <<= 1
	if v < 0 {
		v = ^v
	}
	var out []byte
	for v >= 0x20 {
		out = append(out, byte((0x20|(v&0x1f))+63))
		v >>= 5
	}
	return string(append(out, byte(v+63)))
}

func encodePolyline(pts []domain.LatLng, precision int) string {
	f := math.Pow10(precision)
	var plat, plng int64
	s := ""
	for _, p := range pts {
		lat, lng := int64(math.Round(p.Lat*f)), int64(math.Round(p.Lng*f))
		s += encodeValue(lat-plat) + encodeValue(lng-plng)
		plat, plng = lat, lng
	}
	return s
}

func TestDecodePolyline(t *testing.T) {
	// Canonical Google example (precision 5).
	pts, err := DecodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
	if err != nil {
		t.Fatal(err)
	}
	want := []domain.LatLng{{Lat: 38.5, Lng: -120.2}, {Lat: 40.7, Lng: -120.95}, {Lat: 43.252, Lng: -126.453}}
	if len(pts) != len(want) {
		t.Fatalf("got %d points", len(pts))
	}
	for i := range want {
		if math.Abs(pts[i].Lat-want[i].Lat) > 1e-9 || math.Abs(pts[i].Lng-want[i].Lng) > 1e-9 {
			t.Fatalf("point %d = %+v, want %+v", i, pts[i], want[i])
		}
	}
	// Round trip at precision 6.
	in := []domain.LatLng{{Lat: 48.775846, Lng: 9.182932}, {Lat: 48.137154, Lng: 11.576124}}
	out, err := DecodePolyline(encodePolyline(in, 6), 6)
	if err != nil || len(out) != 2 || out[1] != in[1] {
		t.Fatalf("round trip failed: %v %+v", err, out)
	}
	if _, err := DecodePolyline("_p~i", 5); err == nil {
		t.Fatal("expected truncated polyline error")
	}
}

func TestVerifyStripeSignature(t *testing.T) {
	secret := "whsec_test"
	payload := []byte(`{"id":"evt_1","type":"payment_intent.succeeded","data":{"object":{"id":"pi_1"}}}`)
	now := time.Unix(1_700_000_000, 0)
	ts := strconv.FormatInt(now.Unix(), 10)
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(ts + "." + string(payload)))
	sig := hex.EncodeToString(mac.Sum(nil))

	ev, err := VerifyStripeSignature(payload, fmt.Sprintf("t=%s,v1=%s", ts, sig), secret, 5*time.Minute, now)
	if err != nil || ev.ID != "evt_1" {
		t.Fatalf("valid signature rejected: %v", err)
	}
	if _, err := VerifyStripeSignature(payload, fmt.Sprintf("t=%s,v1=%s", ts, sig), "wrong", 5*time.Minute, now); err == nil {
		t.Fatal("wrong secret accepted")
	}
	if _, err := VerifyStripeSignature(payload, fmt.Sprintf("t=%s,v1=%s", ts, sig), secret, 5*time.Minute, now.Add(time.Hour)); err == nil {
		t.Fatal("stale timestamp accepted")
	}
}

func TestStateMachines(t *testing.T) {
	if !domain.PaymentPending.CanTransitionTo(domain.PaymentAuthorized) ||
		!domain.PaymentAuthorized.CanTransitionTo(domain.PaymentCaptured) ||
		!domain.PaymentCaptured.CanTransitionTo(domain.PaymentRefunded) {
		t.Fatal("happy path escrow transitions must be allowed")
	}
	if domain.PaymentCaptured.CanTransitionTo(domain.PaymentVoided) || domain.PaymentVoided.CanTransitionTo(domain.PaymentCaptured) {
		t.Fatal("captured funds cannot be voided and voided holds cannot be captured")
	}
	if domain.BookingCompleted.CanTransitionTo(domain.BookingCancelledByPassenger) {
		t.Fatal("completed booking must be terminal")
	}
	if !domain.RideScheduled.CanTransitionTo(domain.RideCancelled) || domain.RideCompleted.CanTransitionTo(domain.RideCancelled) {
		t.Fatal("ride transitions wrong")
	}
}
