package domain

import (
	"strings"
	"time"

	"github.com/google/uuid"
)

// BookingStatus is the lifecycle state of a passenger's seat reservation.
type BookingStatus string

const (
	BookingPaymentPending       BookingStatus = "payment_pending"
	BookingRequested            BookingStatus = "requested"
	BookingConfirmed            BookingStatus = "confirmed"
	BookingRejected             BookingStatus = "rejected"
	BookingCancelledByPassenger BookingStatus = "cancelled_by_passenger"
	BookingCancelledByDriver    BookingStatus = "cancelled_by_driver"
	BookingExpired              BookingStatus = "expired"
	BookingCompleted            BookingStatus = "completed"
)

// ActiveBookingStatuses are the statuses that occupy seats on a ride.
var ActiveBookingStatuses = []BookingStatus{BookingPaymentPending, BookingRequested, BookingConfirmed}

var bookingTransitions = map[BookingStatus][]BookingStatus{
	BookingPaymentPending: {BookingRequested, BookingConfirmed, BookingExpired, BookingCancelledByPassenger, BookingCancelledByDriver},
	BookingRequested:      {BookingConfirmed, BookingRejected, BookingExpired, BookingCancelledByPassenger, BookingCancelledByDriver},
	BookingConfirmed:      {BookingCompleted, BookingCancelledByPassenger, BookingCancelledByDriver, BookingExpired},
}

// CanTransitionTo reports whether the booking may move to the target status.
func (s BookingStatus) CanTransitionTo(target BookingStatus) bool {
	for _, t := range bookingTransitions[s] {
		if t == target {
			return true
		}
	}
	return false
}

// IsActive reports whether the status still holds seats.
func (s BookingStatus) IsActive() bool {
	for _, a := range ActiveBookingStatuses {
		if a == s {
			return true
		}
	}
	return false
}

// Booking is a passenger's reservation on a segment of a ride.
type Booking struct {
	ID              uuid.UUID     `json:"id"`
	RideID          uuid.UUID     `json:"ride_id"`
	PassengerID     uuid.UUID     `json:"passenger_id"`
	Seats           int           `json:"seats"`
	Pickup          Place         `json:"pickup"`
	Dropoff         Place         `json:"dropoff"`
	PickupFraction  float64       `json:"pickup_fraction"`
	DropoffFraction float64       `json:"dropoff_fraction"`
	PickupETA       time.Time     `json:"pickup_eta"`
	DropoffETA      time.Time     `json:"dropoff_eta"`
	PriceCents      int           `json:"price_cents"`
	ServiceFeeCents int           `json:"service_fee_cents"`
	TotalCents      int           `json:"total_cents"`
	Currency        string        `json:"currency"`
	Status          BookingStatus `json:"status"`
	Message         string        `json:"message"`
	CreatedAt       time.Time     `json:"created_at"`
	UpdatedAt       time.Time     `json:"updated_at"`
	RespondedAt     *time.Time    `json:"responded_at,omitempty"`
	CancelledAt     *time.Time    `json:"cancelled_at,omitempty"`
	CompletedAt     *time.Time    `json:"completed_at,omitempty"`
}

// SeatInterval is the minimal booking projection used for capacity checks.
type SeatInterval struct {
	Seats           int
	PickupFraction  float64
	DropoffFraction float64
}

// BookingView is a booking enriched for API responses.
type BookingView struct {
	Booking
	Ride      Ride          `json:"ride"`
	Driver    PublicProfile `json:"driver"`
	Passenger PublicProfile `json:"passenger"`
	Payment   *PaymentView  `json:"payment,omitempty"`
	Reviewed  bool          `json:"reviewed"`
}

// CreateBookingInput is the passenger's booking request.
type CreateBookingInput struct {
	Seats   int    `json:"seats"`
	Pickup  Place  `json:"pickup"`
	Dropoff Place  `json:"dropoff"`
	Message string `json:"message"`
}

// Validate checks the booking request.
func (in *CreateBookingInput) Validate() error {
	if in.Seats < 1 || in.Seats > 8 {
		return NewValidationError("seats", "must be between 1 and 8")
	}
	if err := in.Pickup.Validate("pickup"); err != nil {
		return err
	}
	if err := in.Dropoff.Validate("dropoff"); err != nil {
		return err
	}
	in.Message = strings.TrimSpace(in.Message)
	if len(in.Message) > 1000 {
		return NewValidationError("message", "must be at most 1000 characters")
	}
	return nil
}

// PaymentStatus is the escrow state of a booking payment.
type PaymentStatus string

const (
	PaymentPending    PaymentStatus = "pending"
	PaymentAuthorized PaymentStatus = "authorized"
	PaymentCaptured   PaymentStatus = "captured"
	PaymentVoided     PaymentStatus = "voided"
	PaymentRefunded   PaymentStatus = "refunded"
	PaymentFailed     PaymentStatus = "failed"
)

var paymentTransitions = map[PaymentStatus][]PaymentStatus{
	PaymentPending:    {PaymentAuthorized, PaymentVoided, PaymentFailed},
	PaymentAuthorized: {PaymentCaptured, PaymentVoided, PaymentFailed},
	PaymentCaptured:   {PaymentRefunded},
	PaymentFailed:     {PaymentAuthorized, PaymentVoided},
}

// CanTransitionTo reports whether the escrow may move to the target state.
func (s PaymentStatus) CanTransitionTo(target PaymentStatus) bool {
	for _, t := range paymentTransitions[s] {
		if t == target {
			return true
		}
	}
	return false
}

// IsTerminal reports whether no further transitions are possible.
func (s PaymentStatus) IsTerminal() bool {
	return len(paymentTransitions[s]) == 0
}

// Payment is the escrow record attached to a booking.
type Payment struct {
	ID               uuid.UUID     `json:"id"`
	BookingID        uuid.UUID     `json:"booking_id"`
	Provider         string        `json:"provider"`
	ProviderIntentID string        `json:"provider_intent_id"`
	ClientSecret     string        `json:"-"`
	AmountCents      int           `json:"amount_cents"`
	FeeCents         int           `json:"fee_cents"`
	CapturedCents    int           `json:"captured_cents"`
	RefundedCents    int           `json:"refunded_cents"`
	Currency         string        `json:"currency"`
	Status           PaymentStatus `json:"status"`
	LastError        string        `json:"last_error,omitempty"`
	CreatedAt        time.Time     `json:"created_at"`
	UpdatedAt        time.Time     `json:"updated_at"`
	AuthorizedAt     *time.Time    `json:"authorized_at,omitempty"`
	CapturedAt       *time.Time    `json:"captured_at,omitempty"`
	VoidedAt         *time.Time    `json:"voided_at,omitempty"`
	RefundedAt       *time.Time    `json:"refunded_at,omitempty"`
}

// PaymentView is exposed to the paying passenger (includes client secret while pending).
type PaymentView struct {
	Provider       string        `json:"provider"`
	Status         PaymentStatus `json:"status"`
	AmountCents    int           `json:"amount_cents"`
	CapturedCents  int           `json:"captured_cents"`
	RefundedCents  int           `json:"refunded_cents"`
	Currency       string        `json:"currency"`
	ClientSecret   string        `json:"client_secret,omitempty"`
	PublishableKey string        `json:"publishable_key,omitempty"`
}

// Review is a rating left after a completed trip.
type Review struct {
	ID        uuid.UUID `json:"id"`
	BookingID uuid.UUID `json:"booking_id"`
	AuthorID  uuid.UUID `json:"author_id"`
	SubjectID uuid.UUID `json:"subject_id"`
	Rating    int       `json:"rating"`
	Comment   string    `json:"comment"`
	CreatedAt time.Time `json:"created_at"`
}

// CreateReviewInput is a rating submission.
type CreateReviewInput struct {
	Rating  int    `json:"rating"`
	Comment string `json:"comment"`
}

// Validate checks the review payload.
func (in *CreateReviewInput) Validate() error {
	if in.Rating < 1 || in.Rating > 5 {
		return NewValidationError("rating", "must be between 1 and 5")
	}
	in.Comment = strings.TrimSpace(in.Comment)
	if len(in.Comment) > 1000 {
		return NewValidationError("comment", "must be at most 1000 characters")
	}
	return nil
}
