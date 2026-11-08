package domain

import (
	"encoding/json"
	"strings"
	"time"

	"github.com/google/uuid"
)

// LatLng is a WGS84 coordinate.
type LatLng struct {
	Lat float64 `json:"lat"`
	Lng float64 `json:"lng"`
}

// Valid reports whether the coordinate is within WGS84 bounds.
func (p LatLng) Valid() bool {
	return p.Lat >= -90 && p.Lat <= 90 && p.Lng >= -180 && p.Lng <= 180 && !(p.Lat == 0 && p.Lng == 0)
}

// Place is a named coordinate.
type Place struct {
	Name     string `json:"name"`
	Location LatLng `json:"location"`
}

// Validate checks the place.
func (p *Place) Validate(field string) error {
	p.Name = strings.TrimSpace(p.Name)
	if p.Name == "" || len(p.Name) > 200 {
		return NewValidationError(field+".name", "is required (max 200 characters)")
	}
	if !p.Location.Valid() {
		return NewValidationError(field+".location", "must be a valid coordinate")
	}
	return nil
}

// RideStatus is the lifecycle state of a published ride.
type RideStatus string

const (
	RideScheduled  RideStatus = "scheduled"
	RideInProgress RideStatus = "in_progress"
	RideCompleted  RideStatus = "completed"
	RideCancelled  RideStatus = "cancelled"
)

var rideTransitions = map[RideStatus][]RideStatus{
	RideScheduled:  {RideInProgress, RideCancelled, RideCompleted},
	RideInProgress: {RideCompleted},
}

// CanTransitionTo reports whether a ride may move to the target status.
func (s RideStatus) CanTransitionTo(target RideStatus) bool {
	for _, t := range rideTransitions[s] {
		if t == target {
			return true
		}
	}
	return false
}

// RidePreferences are the driver's travel rules.
type RidePreferences struct {
	InstantBooking bool `json:"instant_booking"`
	AllowSmoking   bool `json:"allow_smoking"`
	AllowPets      bool `json:"allow_pets"`
	MaxTwoBack     bool `json:"max_two_back"`
}

// Ride is a trip offered by a driver.
type Ride struct {
	ID                 uuid.UUID       `json:"id"`
	DriverID           uuid.UUID       `json:"driver_id"`
	Origin             Place           `json:"origin"`
	Destination        Place           `json:"destination"`
	DistanceM          int             `json:"distance_m"`
	DurationS          int             `json:"duration_s"`
	DepartureAt        time.Time       `json:"departure_at"`
	ArrivalAt          time.Time       `json:"arrival_at"`
	SeatsTotal         int             `json:"seats_total"`
	PricePerSeatCents  int             `json:"price_per_seat_cents"`
	Currency           string          `json:"currency"`
	Preferences        RidePreferences `json:"preferences"`
	VehicleDescription string          `json:"vehicle_description"`
	Notes              string          `json:"notes"`
	Status             RideStatus      `json:"status"`
	RouteGeoJSON       json.RawMessage `json:"route,omitempty"`
	CreatedAt          time.Time       `json:"created_at"`
	UpdatedAt          time.Time       `json:"updated_at"`
	CancelledAt        *time.Time      `json:"cancelled_at,omitempty"`
}

// ETAAt returns the estimated time the driver reaches a route fraction.
func (r *Ride) ETAAt(fraction float64) time.Time {
	return r.DepartureAt.Add(time.Duration(float64(r.DurationS)*fraction) * time.Second)
}

// RideDetail enriches a ride with driver and capacity information.
type RideDetail struct {
	Ride
	Driver         PublicProfile `json:"driver"`
	SeatsAvailable int           `json:"seats_available"`
}

// PublishRideInput is the payload drivers send to publish a ride.
type PublishRideInput struct {
	Origin             Place           `json:"origin"`
	Destination        Place           `json:"destination"`
	DepartureAt        time.Time       `json:"departure_at"`
	SeatsTotal         int             `json:"seats_total"`
	PricePerSeatCents  int             `json:"price_per_seat_cents"`
	Currency           string          `json:"currency"`
	Preferences        RidePreferences `json:"preferences"`
	VehicleDescription string          `json:"vehicle_description"`
	Notes              string          `json:"notes"`
}

// Validate checks the publish payload.
func (in *PublishRideInput) Validate(now time.Time) error {
	if err := in.Origin.Validate("origin"); err != nil {
		return err
	}
	if err := in.Destination.Validate("destination"); err != nil {
		return err
	}
	if in.Origin.Location == in.Destination.Location {
		return NewValidationError("destination", "must differ from origin")
	}
	if in.DepartureAt.Before(now.Add(30 * time.Minute)) {
		return NewValidationError("departure_at", "must be at least 30 minutes in the future")
	}
	if in.DepartureAt.After(now.AddDate(0, 6, 0)) {
		return NewValidationError("departure_at", "must be within the next 6 months")
	}
	if in.SeatsTotal < 1 || in.SeatsTotal > 8 {
		return NewValidationError("seats_total", "must be between 1 and 8")
	}
	if in.PricePerSeatCents < 100 || in.PricePerSeatCents > 50000 {
		return NewValidationError("price_per_seat_cents", "must be between 100 and 50000")
	}
	in.Currency = strings.ToUpper(strings.TrimSpace(in.Currency))
	if in.Currency == "" {
		in.Currency = "EUR"
	}
	if len(in.Currency) != 3 {
		return NewValidationError("currency", "must be an ISO-4217 code")
	}
	in.VehicleDescription = strings.TrimSpace(in.VehicleDescription)
	if len(in.VehicleDescription) > 200 {
		return NewValidationError("vehicle_description", "must be at most 200 characters")
	}
	in.Notes = strings.TrimSpace(in.Notes)
	if len(in.Notes) > 2000 {
		return NewValidationError("notes", "must be at most 2000 characters")
	}
	return nil
}

// RideSearchQuery describes a passenger's search.
type RideSearchQuery struct {
	Origin       LatLng
	Destination  LatLng
	Date         time.Time // day in the searcher's local zone (start of day)
	Seats        int
	RadiusMeters float64
	Limit        int
}

// Validate checks the search query.
func (q *RideSearchQuery) Validate() error {
	if !q.Origin.Valid() {
		return NewValidationError("from", "must be a valid coordinate")
	}
	if !q.Destination.Valid() {
		return NewValidationError("to", "must be a valid coordinate")
	}
	if q.Seats < 1 || q.Seats > 8 {
		return NewValidationError("seats", "must be between 1 and 8")
	}
	if q.RadiusMeters <= 0 {
		q.RadiusMeters = 15000
	}
	if q.RadiusMeters > 50000 {
		return NewValidationError("radius_m", "must be at most 50000")
	}
	if q.Limit <= 0 || q.Limit > 50 {
		q.Limit = 30
	}
	return nil
}

// SegmentMatch describes how a search maps onto a ride's route.
type SegmentMatch struct {
	Pickup           LatLng    `json:"pickup"`
	Dropoff          LatLng    `json:"dropoff"`
	PickupFraction   float64   `json:"pickup_fraction"`
	DropoffFraction  float64   `json:"dropoff_fraction"`
	PickupDistanceM  float64   `json:"pickup_distance_m"`
	DropoffDistanceM float64   `json:"dropoff_distance_m"`
	PickupETA        time.Time `json:"pickup_eta"`
	DropoffETA       time.Time `json:"dropoff_eta"`
}

// RideMatch is a search result: a ride plus the passenger's segment on it.
type RideMatch struct {
	Ride           Ride          `json:"ride"`
	Driver         PublicProfile `json:"driver"`
	Segment        SegmentMatch  `json:"segment"`
	SeatsAvailable int           `json:"seats_available"`
	PricePerSeat   int           `json:"price_per_seat_cents"`
	Score          float64       `json:"score"`
}

// RoutePreview is returned before publishing so drivers can confirm the route.
type RoutePreview struct {
	DistanceM           int             `json:"distance_m"`
	DurationS           int             `json:"duration_s"`
	SuggestedPriceCents int             `json:"suggested_price_cents"`
	MinPriceCents       int             `json:"min_price_cents"`
	MaxPriceCents       int             `json:"max_price_cents"`
	RouteGeoJSON        json.RawMessage `json:"route"`
}
