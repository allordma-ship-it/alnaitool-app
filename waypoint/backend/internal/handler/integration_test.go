//go:build integration

// Integration tests run the full HTTP stack against real PostgreSQL/PostGIS and Redis.
//
//	TEST_DATABASE_URL=postgres://... TEST_REDIS_URL=redis://... go test -tags integration ./internal/handler/
package handler_test

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"math"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/cache"
	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/handler"
	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/repository/postgres"
	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/service"
)

// Stuttgart -> Ulm -> Augsburg -> Munich, roughly following the A8.
var a8 = [][2]float64{ // lat, lng
	{48.7758, 9.1829}, {48.7000, 9.4000}, {48.6000, 9.7000}, {48.4011, 9.9876},
	{48.4500, 10.3000}, {48.3705, 10.8978}, {48.2500, 11.2000}, {48.1372, 11.5755},
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

func polyline6(pts [][2]float64) string {
	var plat, plng int64
	var sb strings.Builder
	for _, p := range pts {
		lat, lng := int64(math.Round(p[0]*1e6)), int64(math.Round(p[1]*1e6))
		sb.WriteString(encodeValue(lat - plat))
		sb.WriteString(encodeValue(lng - plng))
		plat, plng = lat, lng
	}
	return sb.String()
}

// Damascus -> Homs -> Hama -> Aleppo, roughly following the M5.
var m5 = [][2]float64{
	{33.5138, 36.2765}, {33.7000, 36.5500}, {34.2000, 36.7000}, {34.7324, 36.7137},
	{35.1318, 36.7578}, {35.6000, 36.8000}, {36.2021, 37.1343},
}

func fakeOSRM(t *testing.T) *httptest.Server { return fakeOSRMRoute(t, a8, 231000, 8400) }

func fakeOSRMRoute(t *testing.T, route [][2]float64, distance, duration float64) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasPrefix(r.URL.Path, "/route/v1/driving/") {
			http.NotFound(w, r)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"code": "Ok",
			"routes": []map[string]any{{
				"geometry": polyline6(route), "distance": distance, "duration": duration,
			}},
		})
	}))
}

type client struct {
	t     *testing.T
	base  string
	token string
}

func (c *client) do(method, path string, body any, wantStatus int, out any) {
	c.t.Helper()
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, c.base+path, rd)
	req.Header.Set("Content-Type", "application/json")
	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		c.t.Fatalf("%s %s: %v", method, path, err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != wantStatus {
		c.t.Fatalf("%s %s: status %d, want %d: %s", method, path, resp.StatusCode, wantStatus, raw)
	}
	if out != nil {
		if err := json.Unmarshal(raw, out); err != nil {
			c.t.Fatalf("decode %s: %v (%s)", path, err, raw)
		}
	}
}

func register(t *testing.T, base, name string) *client {
	c := &client{t: t, base: base}
	var res struct {
		Tokens struct {
			AccessToken string `json:"access_token"`
		} `json:"tokens"`
	}
	email := fmt.Sprintf("%s-%d@example.test", name, time.Now().UnixNano())
	c.do("POST", "/v1/auth/register", map[string]any{
		"email": email, "password": "correct-horse-battery", "first_name": name, "last_name": "Tester",
	}, 201, &res)
	c.token = res.Tokens.AccessToken
	return c
}

func TestEndToEndRideSharing(t *testing.T) {
	dbURL, redisURL := os.Getenv("TEST_DATABASE_URL"), os.Getenv("TEST_REDIS_URL")
	if dbURL == "" || redisURL == "" {
		t.Skip("TEST_DATABASE_URL and TEST_REDIS_URL are required")
	}
	ctx := context.Background()
	log := slog.New(slog.NewTextHandler(io.Discard, nil))

	pool, err := postgres.Connect(ctx, dbURL, 10)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	if err := postgres.Migrate(ctx, pool); err != nil {
		t.Fatal(err)
	}
	store := postgres.NewStore(pool)
	rc, err := cache.Connect(ctx, redisURL)
	if err != nil {
		t.Fatal(err)
	}
	defer rc.Close()
	// Registrations share the per-IP auth limit (10/min); reset it between runs.
	if err := rc.Delete(ctx, "rl:auth:ip:127.0.0.1"); err != nil {
		t.Fatal(err)
	}

	osrm := fakeOSRM(t)
	defer osrm.Close()
	routing, _ := service.NewRoutingClient("osrm", osrm.URL, 5*time.Second)
	pricing := service.Pricing{Currency: "EUR", FeeBps: 1000, FeeFixedCents: 50, CentsPerKm: 8, StepMinor: 50, MinSeatMinor: 200}
	escrow := service.NewEscrowService(store, service.NewSimulatedGateway(), log)
	authSvc := service.NewAuthService(store, rc, strings.Repeat("s", 40), 15*time.Minute, time.Hour)
	users := service.NewUserService(store, escrow, "app://r", "app://ok")
	bookings := service.NewBookingService(store, rc, escrow, pricing, service.CancellationPolicy{LateWindow: 24 * time.Hour, LatePercent: 50}, log)
	rides := service.NewRideService(store, rc, routing, pricing, escrow, bookings, log)
	srv := httptest.NewServer(handler.NewRouter(handler.RouterDeps{
		Log: log, Store: store, Cache: rc, Auth: authSvc, Users: users, Rides: rides, Bookings: bookings,
		Escrow: escrow, Matcher: service.NewSpatialMatcher(store, rc, pricing, log),
		Geocoder: service.NewGeocoder("http://127.0.0.1:1", "test", "", rc, log), AllowedOrigins: []string{"*"}, RatePerMinute: 10000,
	}))
	defer srv.Close()

	driver := register(t, srv.URL, "Driver")
	alice := register(t, srv.URL, "Alice")
	bob := register(t, srv.URL, "Bob")
	carol := register(t, srv.URL, "Carol")

	// Preview + publish (2 seats, instant booking).
	var preview struct {
		DistanceM      int `json:"distance_m"`
		SuggestedPrice int `json:"suggested_price_cents"`
	}
	stuttgart := map[string]float64{"lat": 48.7758, "lng": 9.1829}
	munich := map[string]float64{"lat": 48.1372, "lng": 11.5755}
	driver.do("POST", "/v1/rides/preview", map[string]any{"origin": stuttgart, "destination": munich}, 200, &preview)
	if preview.DistanceM != 231000 || preview.SuggestedPrice != 1850 {
		t.Fatalf("unexpected preview %+v", preview)
	}

	departure := time.Now().Add(48 * time.Hour).UTC().Truncate(time.Minute)
	var ride struct {
		ID     string `json:"id"`
		Status string `json:"status"`
	}
	driver.do("POST", "/v1/rides", map[string]any{
		"origin":               map[string]any{"name": "Stuttgart Hbf", "location": stuttgart},
		"destination":          map[string]any{"name": "München Hbf", "location": munich},
		"departure_at":         departure,
		"seats_total":          2,
		"price_per_seat_cents": 2000,
		"preferences":          map[string]bool{"instant_booking": true},
	}, 201, &ride)

	// Partial-route search Ulm -> Augsburg must find the ride, pro-rated.
	date := departure.Format("2006-01-02")
	var search struct {
		Data []struct {
			Ride struct {
				ID string `json:"id"`
			} `json:"ride"`
			SeatsAvailable int `json:"seats_available"`
			PricePerSeat   int `json:"price_per_seat_cents"`
			Segment        struct {
				PickupFraction  float64 `json:"pickup_fraction"`
				DropoffFraction float64 `json:"dropoff_fraction"`
			} `json:"segment"`
		} `json:"data"`
	}
	q := fmt.Sprintf("/v1/rides/search?from_lat=48.40&from_lng=9.99&to_lat=48.37&to_lng=10.89&date=%s&seats=1", date)
	alice.do("GET", q, nil, 200, &search)
	if len(search.Data) != 1 || search.Data[0].Ride.ID != ride.ID {
		t.Fatalf("partial route search failed: %+v", search)
	}
	m := search.Data[0]
	if !(m.Segment.PickupFraction > 0.2 && m.Segment.DropoffFraction < 0.9 && m.PricePerSeat < 2000) {
		t.Fatalf("segment/pricing wrong: %+v", m)
	}

	// Wrong direction (Munich -> Stuttgart) must not match.
	alice.do("GET", fmt.Sprintf("/v1/rides/search?from_lat=48.137&from_lng=11.575&to_lat=48.775&to_lng=9.183&date=%s", date), nil, 200, &search)
	if len(search.Data) != 0 {
		t.Fatalf("reverse direction matched: %+v", search.Data)
	}

	type bookingResp struct {
		ID      string `json:"id"`
		Status  string `json:"status"`
		Total   int    `json:"total_cents"`
		Payment struct {
			Status   string `json:"status"`
			Captured int    `json:"captured_cents"`
		} `json:"payment"`
	}
	place := func(name string, lat, lng float64) map[string]any {
		return map[string]any{"name": name, "location": map[string]float64{"lat": lat, "lng": lng}}
	}

	// Alice: Stuttgart -> Ulm, 2 seats (fills first half).
	var b1 bookingResp
	alice.do("POST", "/v1/rides/"+ride.ID+"/bookings", map[string]any{
		"seats": 2, "pickup": place("Stuttgart", 48.7758, 9.1829), "dropoff": place("Ulm", 48.4011, 9.9876),
	}, 201, &b1)
	if b1.Status != "confirmed" || b1.Payment.Status != "authorized" {
		t.Fatalf("instant booking should be confirmed with held funds: %+v", b1)
	}

	// Bob: Augsburg -> Munich, 2 seats — disjoint segment, must succeed.
	var b2 bookingResp
	bob.do("POST", "/v1/rides/"+ride.ID+"/bookings", map[string]any{
		"seats": 2, "pickup": place("Augsburg", 48.3705, 10.8978), "dropoff": place("München", 48.1372, 11.5755),
	}, 201, &b2)

	// Carol: Stuttgart -> Munich overlaps both: no seats.
	carol.do("POST", "/v1/rides/"+ride.ID+"/bookings", map[string]any{
		"seats": 1, "pickup": place("Stuttgart", 48.7758, 9.1829), "dropoff": place("München", 48.1372, 11.5755),
	}, 409, nil)

	// Alice cancels early (>24h before pickup): hold fully released.
	alice.do("POST", "/v1/bookings/"+b1.ID+"/cancel", nil, 200, &b1)
	if b1.Status != "cancelled_by_passenger" || b1.Payment.Status != "voided" {
		t.Fatalf("early cancellation should void: %+v", b1)
	}

	// Now Carol fits on Stuttgart -> Ulm.
	var b3 bookingResp
	carol.do("POST", "/v1/rides/"+ride.ID+"/bookings", map[string]any{
		"seats": 1, "pickup": place("Stuttgart", 48.7758, 9.1829), "dropoff": place("Ulm", 48.4011, 9.9876),
	}, 201, &b3)

	// Driver sees bookings; travel happens: shift ride into the past, then complete.
	var driverList struct {
		Data []bookingResp `json:"data"`
	}
	driver.do("GET", "/v1/rides/"+ride.ID+"/bookings", nil, 200, &driverList)
	if len(driverList.Data) != 3 {
		t.Fatalf("driver should see 3 bookings, got %d", len(driverList.Data))
	}
	if _, err := pool.Exec(ctx, `UPDATE rides SET departure_at = NOW() - INTERVAL '3 hours', arrival_at = NOW() - INTERVAL '1 hour' WHERE id = $1`, ride.ID); err != nil {
		t.Fatal(err)
	}
	driver.do("POST", "/v1/rides/"+ride.ID+"/complete", nil, 200, &ride)
	if ride.Status != "completed" {
		t.Fatalf("ride status %s", ride.Status)
	}

	bob.do("GET", "/v1/bookings/"+b2.ID, nil, 200, &b2)
	if b2.Status != "completed" || b2.Payment.Status != "captured" || b2.Payment.Captured != b2.Total {
		t.Fatalf("completion should capture escrow: %+v", b2)
	}

	// Reviews update the driver's rating; duplicates rejected.
	bob.do("POST", "/v1/bookings/"+b2.ID+"/review", map[string]any{"rating": 5, "comment": "Great trip"}, 201, nil)
	bob.do("POST", "/v1/bookings/"+b2.ID+"/review", map[string]any{"rating": 4}, 409, nil)
	var me struct {
		Rating      float64 `json:"rating"`
		RatingCount int     `json:"rating_count"`
	}
	driver.do("GET", "/v1/me", nil, 200, &me)
	if me.RatingCount != 1 || me.Rating != 5 {
		t.Fatalf("driver rating not updated: %+v", me)
	}

	// Escrow audit log contains the full trail for Bob's payment.
	var events int
	if err := pool.QueryRow(ctx, `SELECT COUNT(*) FROM payment_events e JOIN payments p ON p.id = e.payment_id WHERE p.booking_id = $1`, b2.ID).Scan(&events); err != nil {
		t.Fatal(err)
	}
	if events != 3 { // created(pending) -> authorized -> captured
		t.Fatalf("expected 3 escrow events, got %d", events)
	}
}

// TestSyriaCashMarket runs the Syrian configuration: SYP pricing in 50-pound
// steps, no platform fee, and cash settlement between passenger and driver.
func TestSyriaCashMarket(t *testing.T) {
	dbURL, redisURL := os.Getenv("TEST_DATABASE_URL"), os.Getenv("TEST_REDIS_URL")
	if dbURL == "" || redisURL == "" {
		t.Skip("TEST_DATABASE_URL and TEST_REDIS_URL are required")
	}
	ctx := context.Background()
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	pool, err := postgres.Connect(ctx, dbURL, 10)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	if err := postgres.Migrate(ctx, pool); err != nil {
		t.Fatal(err)
	}
	store := postgres.NewStore(pool)
	rc, err := cache.Connect(ctx, redisURL)
	if err != nil {
		t.Fatal(err)
	}
	defer rc.Close()
	// Registrations share the per-IP auth limit (10/min); reset it between runs.
	if err := rc.Delete(ctx, "rl:auth:ip:127.0.0.1"); err != nil {
		t.Fatal(err)
	}

	osrm := fakeOSRMRoute(t, m5, 356000, 15000)
	defer osrm.Close()
	routing, _ := service.NewRoutingClient("osrm", osrm.URL, 5*time.Second)
	// 2.50 SYP per seat-km, rounded to 50 SYP, minimum 100 SYP (amounts in piastres).
	pricing := service.Pricing{Currency: "SYP", CentsPerKm: 250, StepMinor: 5000, MinSeatMinor: 10000}
	escrow := service.NewEscrowService(store, service.NewCashGateway(), log)
	authSvc := service.NewAuthService(store, rc, strings.Repeat("s", 40), 15*time.Minute, time.Hour)
	users := service.NewUserService(store, escrow, "app://r", "app://ok")
	bookings := service.NewBookingService(store, rc, escrow, pricing, service.CancellationPolicy{LateWindow: 24 * time.Hour, LatePercent: 50}, log)
	rides := service.NewRideService(store, rc, routing, pricing, escrow, bookings, log)
	market := handler.MarketConfig{Country: "SY", Currency: "SYP", Timezone: "Asia/Damascus", PaymentProvider: "cash", PriceStepMinor: 5000}
	srv := httptest.NewServer(handler.NewRouter(handler.RouterDeps{
		Log: log, Store: store, Cache: rc, Auth: authSvc, Users: users, Rides: rides, Bookings: bookings,
		Escrow: escrow, Matcher: service.NewSpatialMatcher(store, rc, pricing, log),
		Geocoder: service.NewGeocoder("http://127.0.0.1:1", "test", "", rc, log), AllowedOrigins: []string{"*"},
		RatePerMinute: 10000, Market: market,
	}))
	defer srv.Close()

	anon := &client{t: t, base: srv.URL}
	var cfg handler.MarketConfig
	anon.do("GET", "/v1/config", nil, 200, &cfg)
	if cfg != market {
		t.Fatalf("config = %+v", cfg)
	}

	driver := register(t, srv.URL, "سامر")
	passenger := register(t, srv.URL, "ليلى")

	// Drivers need no payout onboarding with cash.
	driver.do("POST", "/v1/me/payouts/onboarding", map[string]any{"country": "SY"}, 422, nil)

	damascus := map[string]float64{"lat": 33.5138, "lng": 36.2765}
	aleppo := map[string]float64{"lat": 36.2021, "lng": 37.1343}
	var preview struct {
		Suggested int `json:"suggested_price_cents"`
	}
	driver.do("POST", "/v1/rides/preview", map[string]any{"origin": damascus, "destination": aleppo}, 200, &preview)
	if preview.Suggested != 90000 { // 356 km × 2.5 = 890 SYP, rounded to the 50-SYP step
		t.Fatalf("suggested %d, want 90000 (900 SYP)", preview.Suggested)
	}

	// Fixed 08:00 Damascus time three days ahead, so pickups on the route fall on
	// the same calendar day whatever time the test runs.
	dl := mustLoc(t, "Asia/Damascus")
	day := time.Now().In(dl).AddDate(0, 0, 3)
	departure := time.Date(day.Year(), day.Month(), day.Day(), 8, 0, 0, 0, dl).UTC()
	var ride struct {
		ID       string `json:"id"`
		Currency string `json:"currency"`
	}
	driver.do("POST", "/v1/rides", map[string]any{
		"origin":               map[string]any{"name": "دمشق", "location": damascus},
		"destination":          map[string]any{"name": "حلب", "location": aleppo},
		"departure_at":         departure,
		"seats_total":          3,
		"price_per_seat_cents": 90000,
		"preferences":          map[string]bool{"instant_booking": true},
	}, 201, &ride)
	if ride.Currency != "SYP" {
		t.Fatalf("ride currency %q", ride.Currency)
	}

	// Homs -> Hama is part of the route: pro-rated, rounded to 50 SYP, no fee.
	date := departure.In(dl).Format("2006-01-02")
	var search struct {
		Data []struct {
			PricePerSeat int `json:"price_per_seat_cents"`
		} `json:"data"`
	}
	passenger.do("GET", fmt.Sprintf("/v1/rides/search?from_lat=34.73&from_lng=36.71&to_lat=35.13&to_lng=36.75&date=%s", date), nil, 200, &search)
	if len(search.Data) != 1 || search.Data[0].PricePerSeat%5000 != 0 || search.Data[0].PricePerSeat >= 90000 {
		t.Fatalf("Homs->Hama search: %+v", search)
	}

	type bookingResp struct {
		ID      string `json:"id"`
		Status  string `json:"status"`
		Price   int    `json:"price_cents"`
		Fee     int    `json:"service_fee_cents"`
		Total   int    `json:"total_cents"`
		Payment struct {
			Provider string `json:"provider"`
			Status   string `json:"status"`
			Captured int    `json:"captured_cents"`
		} `json:"payment"`
	}
	place := func(name string, lat, lng float64) map[string]any {
		return map[string]any{"name": name, "location": map[string]float64{"lat": lat, "lng": lng}}
	}
	var b bookingResp
	passenger.do("POST", "/v1/rides/"+ride.ID+"/bookings", map[string]any{
		"seats": 1, "pickup": place("حمص", 34.7324, 36.7137), "dropoff": place("حماة", 35.1318, 36.7578),
	}, 201, &b)
	if b.Status != "confirmed" || b.Payment.Provider != "cash" || b.Payment.Status != "authorized" || b.Fee != 0 || b.Total != b.Price {
		t.Fatalf("cash booking: %+v", b)
	}

	// Late cancellation with cash never charges anything.
	if _, err := pool.Exec(ctx, `UPDATE bookings SET pickup_eta = NOW() + INTERVAL '2 hours' WHERE id = $1`, b.ID); err != nil {
		t.Fatal(err)
	}
	passenger.do("POST", "/v1/bookings/"+b.ID+"/cancel", nil, 200, &b)
	if b.Status != "cancelled_by_passenger" || b.Payment.Status != "voided" || b.Payment.Captured != 0 {
		t.Fatalf("cash late cancel: %+v", b)
	}

	// Rebook and complete the trip: the reservation is marked as paid in cash.
	passenger.do("POST", "/v1/rides/"+ride.ID+"/bookings", map[string]any{
		"seats": 2, "pickup": place("دمشق", 33.5138, 36.2765), "dropoff": place("حلب", 36.2021, 37.1343),
	}, 201, &b)
	if b.Price != 180000 {
		t.Fatalf("2 seats full route = %d, want 180000", b.Price)
	}
	if _, err := pool.Exec(ctx, `UPDATE rides SET departure_at = NOW() - INTERVAL '6 hours', arrival_at = NOW() - INTERVAL '1 hour' WHERE id = $1`, ride.ID); err != nil {
		t.Fatal(err)
	}
	driver.do("POST", "/v1/rides/"+ride.ID+"/complete", nil, 200, nil)
	passenger.do("GET", "/v1/bookings/"+b.ID, nil, 200, &b)
	if b.Status != "completed" || b.Payment.Status != "captured" || b.Payment.Captured != 180000 {
		t.Fatalf("cash completion: %+v", b)
	}
}

func mustLoc(t *testing.T, name string) *time.Location {
	loc, err := time.LoadLocation(name)
	if err != nil {
		t.Fatal(err)
	}
	return loc
}
