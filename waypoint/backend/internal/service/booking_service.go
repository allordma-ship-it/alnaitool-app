package service

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/google/uuid"

	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/cache"
	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/domain"
	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/repository/postgres"
)

// maxSnapDistanceM is how far a requested pickup/dropoff may be from the route.
const maxSnapDistanceM = 25000

// CancellationPolicy configures passenger late-cancellation compensation.
type CancellationPolicy struct {
	LateWindow  time.Duration // cancellations closer than this to pickup are "late"
	LatePercent int           // share of the seat price paid to the driver on late cancellation
}

// BookingService orchestrates reservations, seat capacity and the escrow lifecycle.
type BookingService struct {
	store   *postgres.Store
	cache   *cache.Cache
	escrow  *EscrowService
	pricing Pricing
	policy  CancellationPolicy
	log     *slog.Logger
	now     func() time.Time
}

// NewBookingService builds the booking service.
func NewBookingService(store *postgres.Store, c *cache.Cache, escrow *EscrowService, pricing Pricing,
	policy CancellationPolicy, log *slog.Logger) *BookingService {
	return &BookingService{store: store, cache: c, escrow: escrow, pricing: pricing, policy: policy, log: log, now: time.Now}
}

// Create reserves seats on a ride segment and opens the escrow hold.
//
// Seat allocation happens under a row lock on the ride so concurrent bookings
// cannot oversubscribe any part of the route. The hold is opened after commit
// (network I/O outside the transaction); if that fails the booking is expired,
// releasing the seats.
func (s *BookingService) Create(ctx context.Context, passengerID, rideID uuid.UUID, in domain.CreateBookingInput) (*domain.BookingView, error) {
	if err := in.Validate(); err != nil {
		return nil, err
	}
	passenger, err := s.store.Users.GetByID(ctx, passengerID)
	if err != nil {
		return nil, err
	}

	var booking *domain.Booking
	var ride *domain.Ride
	err = s.store.WithTx(ctx, func(tx *postgres.Store) error {
		ride, err = tx.Rides.LockByID(ctx, rideID)
		if err != nil {
			return err
		}
		if ride.DriverID == passengerID {
			return domain.NewValidationError("ride_id", "you cannot book your own ride")
		}
		if ride.Status != domain.RideScheduled {
			return fmt.Errorf("%w: ride is %s", domain.ErrInvalidTransition, ride.Status)
		}
		if in.Seats > ride.SeatsTotal {
			return domain.ErrNoSeats
		}

		pickup, err := tx.Rides.ProjectOnRoute(ctx, rideID, in.Pickup.Location)
		if err != nil {
			return err
		}
		dropoff, err := tx.Rides.ProjectOnRoute(ctx, rideID, in.Dropoff.Location)
		if err != nil {
			return err
		}
		if pickup.DistanceM > maxSnapDistanceM {
			return domain.NewValidationError("pickup", "is too far from the driver's route")
		}
		if dropoff.DistanceM > maxSnapDistanceM {
			return domain.NewValidationError("dropoff", "is too far from the driver's route")
		}
		if pickup.Fraction >= dropoff.Fraction {
			return domain.NewValidationError("dropoff", "must come after pickup along the driver's route")
		}
		pickupETA := ride.ETAAt(pickup.Fraction).Truncate(time.Minute)
		if !pickupETA.After(s.now()) {
			return domain.NewValidationError("ride_id", "this ride has already passed your pickup point")
		}

		active, err := tx.Bookings.ListActiveByRide(ctx, rideID)
		if err != nil {
			return err
		}
		intervals := make([]domain.SeatInterval, 0, len(active))
		for _, b := range active {
			intervals = append(intervals, domain.SeatInterval{Seats: b.Seats, PickupFraction: b.PickupFraction, DropoffFraction: b.DropoffFraction})
		}
		if AvailableSeats(ride.SeatsTotal, intervals, pickup.Fraction, dropoff.Fraction) < in.Seats {
			return domain.ErrNoSeats
		}

		seatPrice := s.pricing.SegmentPrice(ride.PricePerSeatCents, pickup.Fraction, dropoff.Fraction)
		price := seatPrice * in.Seats
		fee := s.pricing.ServiceFee(price)
		booking, err = tx.Bookings.Create(ctx, &domain.Booking{
			RideID:          rideID,
			PassengerID:     passengerID,
			Seats:           in.Seats,
			Pickup:          domain.Place{Name: in.Pickup.Name, Location: pickup.Snapped},
			Dropoff:         domain.Place{Name: in.Dropoff.Name, Location: dropoff.Snapped},
			PickupFraction:  pickup.Fraction,
			DropoffFraction: dropoff.Fraction,
			PickupETA:       pickupETA,
			DropoffETA:      ride.ETAAt(dropoff.Fraction).Truncate(time.Minute),
			PriceCents:      price,
			ServiceFeeCents: fee,
			TotalCents:      price + fee,
			Currency:        ride.Currency,
			Status:          domain.BookingPaymentPending,
			Message:         in.Message,
		})
		if errors.Is(err, domain.ErrConflict) {
			return fmt.Errorf("%w: you already have an active booking on this ride", domain.ErrConflict)
		}
		return err
	})
	if err != nil {
		return nil, err
	}
	InvalidateSearch(ctx, s.cache, s.log)

	driver, err := s.store.Users.GetByID(ctx, ride.DriverID)
	if err != nil {
		return nil, err
	}
	if _, err := s.escrow.OpenHold(ctx, booking, passenger, driver); err != nil {
		s.log.Error("opening escrow hold failed", "booking", booking.ID, "err", err)
		if xerr := s.store.WithTx(context.WithoutCancel(ctx), func(tx *postgres.Store) error {
			_, uerr := tx.Bookings.UpdateStatus(ctx, booking.ID, domain.BookingExpired)
			return uerr
		}); xerr != nil {
			s.log.Error("expiring booking after hold failure", "booking", booking.ID, "err", xerr)
		}
		InvalidateSearch(ctx, s.cache, s.log)
		return nil, err
	}

	// The hold may already be authorized (simulated gateway, saved cards): advance immediately.
	if err := s.store.WithTx(ctx, func(tx *postgres.Store) error {
		return s.advanceIfAuthorizedLocked(ctx, tx, booking.ID)
	}); err != nil {
		return nil, err
	}
	return s.view(ctx, passengerID, booking.ID)
}

// advanceIfAuthorizedLocked moves a payment_pending booking forward once its hold is authorized,
// and expires it if the hold was voided.
func (s *BookingService) advanceIfAuthorizedLocked(ctx context.Context, tx *postgres.Store, bookingID uuid.UUID) error {
	b, err := tx.Bookings.LockByID(ctx, bookingID)
	if err != nil {
		return err
	}
	p, err := tx.Payments.GetByBooking(ctx, bookingID)
	if err != nil {
		return err
	}
	switch {
	case b.Status == domain.BookingPaymentPending && p.Status == domain.PaymentAuthorized:
		ride, err := tx.Rides.GetByID(ctx, b.RideID, false)
		if err != nil {
			return err
		}
		next := domain.BookingRequested
		if ride.Preferences.InstantBooking {
			next = domain.BookingConfirmed
		}
		if ride.Status != domain.RideScheduled {
			// Ride was cancelled/started while the passenger was paying.
			pl, err := tx.Payments.LockByBooking(ctx, bookingID)
			if err != nil {
				return err
			}
			if err := s.escrow.VoidLocked(ctx, tx, pl, "ride no longer bookable"); err != nil {
				return err
			}
			next = domain.BookingExpired
		}
		_, err = tx.Bookings.UpdateStatus(ctx, b.ID, next)
		return err
	case b.Status.IsActive() && p.Status == domain.PaymentVoided:
		_, err := tx.Bookings.UpdateStatus(ctx, b.ID, domain.BookingExpired)
		if err == nil {
			InvalidateSearch(ctx, s.cache, s.log)
		}
		return err
	}
	return nil
}

// HandleIntentUpdate applies a provider webhook to escrow and booking state.
func (s *BookingService) HandleIntentUpdate(ctx context.Context, in *Intent) error {
	return s.store.WithTx(ctx, func(tx *postgres.Store) error {
		p, _, err := s.escrow.ApplyIntentUpdate(ctx, tx, in)
		if errors.Is(err, domain.ErrNotFound) {
			s.log.Warn("webhook for unknown intent", "intent", in.ID)
			return nil
		}
		if err != nil {
			return err
		}
		return s.advanceIfAuthorizedLocked(ctx, tx, p.BookingID)
	})
}

// SyncPayment lets the passenger app reconcile payment state after the payment sheet
// closes (complements webhooks, useful when webhooks are delayed).
func (s *BookingService) SyncPayment(ctx context.Context, passengerID, bookingID uuid.UUID) (*domain.BookingView, error) {
	err := s.store.WithTx(ctx, func(tx *postgres.Store) error {
		b, err := tx.Bookings.LockByID(ctx, bookingID)
		if err != nil {
			return err
		}
		if b.PassengerID != passengerID {
			return domain.ErrNotFound
		}
		p, err := tx.Payments.LockByBooking(ctx, bookingID)
		if err != nil {
			return err
		}
		if !p.Status.IsTerminal() {
			if _, err := s.escrow.SyncLocked(ctx, tx, p); err != nil {
				return err
			}
		}
		return s.advanceIfAuthorizedLocked(ctx, tx, bookingID)
	})
	if err != nil {
		return nil, err
	}
	return s.view(ctx, passengerID, bookingID)
}

// lockForDriver locks a booking and verifies the caller drives the ride.
func (s *BookingService) lockForDriver(ctx context.Context, tx *postgres.Store, driverID, bookingID uuid.UUID) (*domain.Booking, *domain.Ride, error) {
	b, err := tx.Bookings.LockByID(ctx, bookingID)
	if err != nil {
		return nil, nil, err
	}
	ride, err := tx.Rides.GetByID(ctx, b.RideID, false)
	if err != nil {
		return nil, nil, err
	}
	if ride.DriverID != driverID {
		return nil, nil, domain.ErrNotFound
	}
	return b, ride, nil
}

// Accept confirms a pending booking request (driver).
func (s *BookingService) Accept(ctx context.Context, driverID, bookingID uuid.UUID) (*domain.BookingView, error) {
	err := s.store.WithTx(ctx, func(tx *postgres.Store) error {
		b, ride, err := s.lockForDriver(ctx, tx, driverID, bookingID)
		if err != nil {
			return err
		}
		if b.Status != domain.BookingRequested {
			return fmt.Errorf("%w: booking is %s", domain.ErrInvalidTransition, b.Status)
		}
		if ride.Status != domain.RideScheduled {
			return fmt.Errorf("%w: ride is %s", domain.ErrInvalidTransition, ride.Status)
		}
		p, err := tx.Payments.GetByBooking(ctx, bookingID)
		if err != nil {
			return err
		}
		if p.Status != domain.PaymentAuthorized {
			return fmt.Errorf("%w: payment is %s", domain.ErrPaymentRequired, p.Status)
		}
		_, err = tx.Bookings.UpdateStatus(ctx, bookingID, domain.BookingConfirmed)
		return err
	})
	if err != nil {
		return nil, err
	}
	return s.view(ctx, driverID, bookingID)
}

// Reject declines a pending booking request (driver) and releases the hold.
func (s *BookingService) Reject(ctx context.Context, driverID, bookingID uuid.UUID) (*domain.BookingView, error) {
	err := s.store.WithTx(ctx, func(tx *postgres.Store) error {
		b, _, err := s.lockForDriver(ctx, tx, driverID, bookingID)
		if err != nil {
			return err
		}
		if b.Status != domain.BookingRequested {
			return fmt.Errorf("%w: booking is %s", domain.ErrInvalidTransition, b.Status)
		}
		return s.closeWithVoidLocked(ctx, tx, b, domain.BookingRejected, "rejected by driver")
	})
	if err != nil {
		return nil, err
	}
	InvalidateSearch(ctx, s.cache, s.log)
	return s.view(ctx, driverID, bookingID)
}

// CancelByDriver removes a passenger from the ride (driver); the hold is fully released.
func (s *BookingService) CancelByDriver(ctx context.Context, driverID, bookingID uuid.UUID) (*domain.BookingView, error) {
	err := s.store.WithTx(ctx, func(tx *postgres.Store) error {
		b, _, err := s.lockForDriver(ctx, tx, driverID, bookingID)
		if err != nil {
			return err
		}
		return s.closeWithVoidLocked(ctx, tx, b, domain.BookingCancelledByDriver, "cancelled by driver")
	})
	if err != nil {
		return nil, err
	}
	InvalidateSearch(ctx, s.cache, s.log)
	return s.view(ctx, driverID, bookingID)
}

// CancelByPassenger cancels a booking under the cancellation policy:
// before confirmation or outside the late window the hold is released in full;
// a late cancellation of a confirmed seat captures LatePercent of the seat price
// (paid to the driver) plus the service fee, releasing the remainder.
func (s *BookingService) CancelByPassenger(ctx context.Context, passengerID, bookingID uuid.UUID) (*domain.BookingView, error) {
	err := s.store.WithTx(ctx, func(tx *postgres.Store) error {
		b, err := tx.Bookings.LockByID(ctx, bookingID)
		if err != nil {
			return err
		}
		if b.PassengerID != passengerID {
			return domain.ErrNotFound
		}
		if !b.Status.CanTransitionTo(domain.BookingCancelledByPassenger) {
			return fmt.Errorf("%w: booking is %s", domain.ErrInvalidTransition, b.Status)
		}
		// Compensation needs funds held by the platform; cash bookings cancel freely.
		late := b.Status == domain.BookingConfirmed && b.PickupETA.Sub(s.now()) < s.policy.LateWindow
		if !late || s.policy.LatePercent == 0 || s.escrow.IsCash() {
			return s.closeWithVoidLocked(ctx, tx, b, domain.BookingCancelledByPassenger, "cancelled by passenger")
		}
		p, err := tx.Payments.LockByBooking(ctx, bookingID)
		if err != nil {
			return err
		}
		driverShare := b.PriceCents * s.policy.LatePercent / 100
		if err := s.escrow.CaptureLocked(ctx, tx, p, driverShare+b.ServiceFeeCents, b.ServiceFeeCents,
			fmt.Sprintf("late passenger cancellation (%d%% compensation)", s.policy.LatePercent)); err != nil {
			return err
		}
		_, err = tx.Bookings.UpdateStatus(ctx, bookingID, domain.BookingCancelledByPassenger)
		return err
	})
	if err != nil {
		return nil, err
	}
	InvalidateSearch(ctx, s.cache, s.log)
	return s.view(ctx, passengerID, bookingID)
}

// ConfirmTrip lets the passenger confirm the ride took place, releasing escrow to the driver early.
func (s *BookingService) ConfirmTrip(ctx context.Context, passengerID, bookingID uuid.UUID) (*domain.BookingView, error) {
	err := s.store.WithTx(ctx, func(tx *postgres.Store) error {
		b, err := tx.Bookings.LockByID(ctx, bookingID)
		if err != nil {
			return err
		}
		if b.PassengerID != passengerID {
			return domain.ErrNotFound
		}
		if b.Status != domain.BookingConfirmed {
			return fmt.Errorf("%w: booking is %s", domain.ErrInvalidTransition, b.Status)
		}
		if s.now().Before(b.PickupETA) {
			return domain.NewValidationError("booking", "the trip can be confirmed only after pickup")
		}
		return s.completeLocked(ctx, tx, b, "confirmed by passenger")
	})
	if err != nil {
		return nil, err
	}
	return s.view(ctx, passengerID, bookingID)
}

// completeLocked captures the full escrow amount and marks the booking completed.
func (s *BookingService) completeLocked(ctx context.Context, tx *postgres.Store, b *domain.Booking, reason string) error {
	p, err := tx.Payments.LockByBooking(ctx, b.ID)
	if err != nil {
		return err
	}
	if err := s.escrow.CaptureLocked(ctx, tx, p, b.TotalCents, b.ServiceFeeCents, reason); err != nil {
		return err
	}
	_, err = tx.Bookings.UpdateStatus(ctx, b.ID, domain.BookingCompleted)
	return err
}

// closeWithVoidLocked releases the hold (if any) and moves the booking to a closed status.
func (s *BookingService) closeWithVoidLocked(ctx context.Context, tx *postgres.Store, b *domain.Booking, to domain.BookingStatus, reason string) error {
	if !b.Status.CanTransitionTo(to) {
		return fmt.Errorf("%w: booking %s -> %s", domain.ErrInvalidTransition, b.Status, to)
	}
	p, err := tx.Payments.LockByBooking(ctx, b.ID)
	switch {
	case errors.Is(err, domain.ErrNotFound):
		// No hold was opened (provider failure before persistence).
	case err != nil:
		return err
	default:
		if err := s.escrow.VoidLocked(ctx, tx, p, reason); err != nil {
			return err
		}
	}
	updated, err := tx.Bookings.UpdateStatus(ctx, b.ID, to)
	if err != nil {
		return err
	}
	*b = *updated
	return nil
}

// Get returns a booking visible to its passenger or the ride's driver.
func (s *BookingService) Get(ctx context.Context, viewerID, bookingID uuid.UUID) (*domain.BookingView, error) {
	return s.view(ctx, viewerID, bookingID)
}

// ListMine lists the caller's bookings as a passenger.
func (s *BookingService) ListMine(ctx context.Context, passengerID uuid.UUID) ([]domain.BookingView, error) {
	bookings, err := s.store.Bookings.ListByPassenger(ctx, passengerID, 100)
	if err != nil {
		return nil, err
	}
	return s.views(ctx, passengerID, bookings)
}

// ListForRide lists all bookings on a ride (driver only).
func (s *BookingService) ListForRide(ctx context.Context, driverID, rideID uuid.UUID) ([]domain.BookingView, error) {
	ride, err := s.store.Rides.GetByID(ctx, rideID, false)
	if err != nil {
		return nil, err
	}
	if ride.DriverID != driverID {
		return nil, domain.ErrNotFound
	}
	bookings, err := s.store.Bookings.ListByRide(ctx, rideID)
	if err != nil {
		return nil, err
	}
	return s.views(ctx, driverID, bookings)
}

// Review records a rating after a completed trip, by either party about the other.
func (s *BookingService) Review(ctx context.Context, authorID, bookingID uuid.UUID, in domain.CreateReviewInput) (*domain.Review, error) {
	if err := in.Validate(); err != nil {
		return nil, err
	}
	var out *domain.Review
	err := s.store.WithTx(ctx, func(tx *postgres.Store) error {
		b, err := tx.Bookings.GetByID(ctx, bookingID)
		if err != nil {
			return err
		}
		ride, err := tx.Rides.GetByID(ctx, b.RideID, false)
		if err != nil {
			return err
		}
		var subject uuid.UUID
		switch authorID {
		case b.PassengerID:
			subject = ride.DriverID
		case ride.DriverID:
			subject = b.PassengerID
		default:
			return domain.ErrNotFound
		}
		if b.Status != domain.BookingCompleted {
			return domain.NewValidationError("booking", "only completed trips can be reviewed")
		}
		if b.CompletedAt != nil && s.now().Sub(*b.CompletedAt) > 14*24*time.Hour {
			return domain.NewValidationError("booking", "the review period (14 days) has ended")
		}
		out, err = tx.Reviews.Create(ctx, &domain.Review{
			BookingID: bookingID, AuthorID: authorID, SubjectID: subject, Rating: in.Rating, Comment: in.Comment,
		})
		if errors.Is(err, domain.ErrConflict) {
			return fmt.Errorf("%w: you already reviewed this trip", domain.ErrConflict)
		}
		if err != nil {
			return err
		}
		return tx.Users.AddRating(ctx, subject, in.Rating)
	})
	return out, err
}

func (s *BookingService) view(ctx context.Context, viewerID, bookingID uuid.UUID) (*domain.BookingView, error) {
	b, err := s.store.Bookings.GetByID(ctx, bookingID)
	if err != nil {
		return nil, err
	}
	views, err := s.views(ctx, viewerID, []domain.Booking{*b})
	if err != nil {
		return nil, err
	}
	if len(views) == 0 {
		return nil, domain.ErrNotFound
	}
	return &views[0], nil
}

// views enriches bookings, filtering out any the viewer may not see.
func (s *BookingService) views(ctx context.Context, viewerID uuid.UUID, bookings []domain.Booking) ([]domain.BookingView, error) {
	out := make([]domain.BookingView, 0, len(bookings))
	if len(bookings) == 0 {
		return out, nil
	}
	rideIDs := make([]uuid.UUID, 0, len(bookings))
	bookingIDs := make([]uuid.UUID, 0, len(bookings))
	for _, b := range bookings {
		rideIDs = append(rideIDs, b.RideID)
		bookingIDs = append(bookingIDs, b.ID)
	}
	rides, err := s.store.Rides.GetMany(ctx, rideIDs)
	if err != nil {
		return nil, err
	}
	userIDs := make([]uuid.UUID, 0, len(bookings)*2)
	for _, b := range bookings {
		userIDs = append(userIDs, b.PassengerID)
		if r, ok := rides[b.RideID]; ok {
			userIDs = append(userIDs, r.DriverID)
		}
	}
	users, err := s.store.Users.GetMany(ctx, userIDs)
	if err != nil {
		return nil, err
	}
	payments, err := s.store.Payments.GetManyByBooking(ctx, bookingIDs)
	if err != nil {
		return nil, err
	}
	reviewed, err := s.store.Reviews.ReviewedBookings(ctx, viewerID, bookingIDs)
	if err != nil {
		return nil, err
	}
	for _, b := range bookings {
		ride, ok := rides[b.RideID]
		if !ok {
			continue
		}
		if viewerID != b.PassengerID && viewerID != ride.DriverID {
			continue
		}
		driver, passenger := users[ride.DriverID], users[b.PassengerID]
		if driver == nil || passenger == nil {
			continue
		}
		v := domain.BookingView{
			Booking:   b,
			Ride:      *ride,
			Driver:    driver.Public(),
			Passenger: passenger.Public(),
			Reviewed:  reviewed[b.ID],
		}
		if p, ok := payments[b.ID]; ok {
			v.Payment = s.escrow.View(p, viewerID == b.PassengerID)
		}
		out = append(out, v)
	}
	return out, nil
}

// ---------------------------------------------------------------------------
// Background maintenance (invoked by the worker)
// ---------------------------------------------------------------------------

// ExpireUnpaid expires bookings whose payment was never authorized within ttl.
func (s *BookingService) ExpireUnpaid(ctx context.Context, ttl time.Duration) (int, error) {
	ids, err := s.store.Bookings.ListIDsByStatusOlderThan(ctx, domain.BookingPaymentPending, s.now().Add(-ttl), 100)
	if err != nil {
		return 0, err
	}
	return s.expireEach(ctx, ids, domain.BookingPaymentPending, "payment not completed in time")
}

// ExpireUnanswered expires booking requests the driver did not answer within ttl,
// and any request or unpaid booking whose pickup time has passed.
func (s *BookingService) ExpireUnanswered(ctx context.Context, ttl time.Duration) (int, error) {
	ids, err := s.store.Bookings.ListIDsByStatusOlderThan(ctx, domain.BookingRequested, s.now().Add(-ttl), 100)
	if err != nil {
		return 0, err
	}
	past, err := s.store.Bookings.ListRequestedPastPickup(ctx, s.now(), 100)
	if err != nil {
		return 0, err
	}
	n1, err1 := s.expireEach(ctx, ids, domain.BookingRequested, "driver did not respond in time")
	n2, err2 := s.expireEach(ctx, past, "", "pickup time passed")
	return n1 + n2, errors.Join(err1, err2)
}

func (s *BookingService) expireEach(ctx context.Context, ids []uuid.UUID, expected domain.BookingStatus, reason string) (int, error) {
	n := 0
	var errs []error
	for _, id := range ids {
		err := s.store.WithTx(ctx, func(tx *postgres.Store) error {
			b, err := tx.Bookings.LockByID(ctx, id)
			if err != nil {
				return err
			}
			if (expected != "" && b.Status != expected) || !b.Status.CanTransitionTo(domain.BookingExpired) {
				return nil // state changed concurrently
			}
			if b.Status == domain.BookingPaymentPending {
				// Last-chance reconciliation: the passenger may have just paid.
				if p, perr := tx.Payments.LockByBooking(ctx, id); perr == nil && !p.Status.IsTerminal() && reason != "pickup time passed" {
					if _, serr := s.escrow.SyncLocked(ctx, tx, p); serr == nil && p.Status == domain.PaymentAuthorized {
						return s.advanceIfAuthorizedLocked(ctx, tx, id)
					}
				}
			}
			if err := s.closeWithVoidLocked(ctx, tx, b, domain.BookingExpired, reason); err != nil {
				return err
			}
			n++
			return nil
		})
		if err != nil {
			errs = append(errs, fmt.Errorf("expire booking %s: %w", id, err))
		}
	}
	if n > 0 {
		InvalidateSearch(ctx, s.cache, s.log)
	}
	return n, errors.Join(errs...)
}

// RenewAgeingHolds re-authorizes holds older than maxAge before the card network drops them.
func (s *BookingService) RenewAgeingHolds(ctx context.Context, maxAge time.Duration) (int, error) {
	if s.escrow.IsCash() {
		return 0, nil // cash reservations do not expire
	}
	ids, err := s.store.Payments.ListAuthorizedBefore(ctx, s.now().Add(-maxAge), 50)
	if err != nil {
		return 0, err
	}
	n := 0
	var errs []error
	for _, id := range ids {
		err := s.store.WithTx(ctx, func(tx *postgres.Store) error {
			b, err := tx.Bookings.LockByID(ctx, id)
			if err != nil {
				return err
			}
			if b.Status != domain.BookingRequested && b.Status != domain.BookingConfirmed {
				return nil
			}
			p, err := tx.Payments.LockByBooking(ctx, id)
			if err != nil {
				return err
			}
			ride, err := tx.Rides.GetByID(ctx, b.RideID, false)
			if err != nil {
				return err
			}
			driver, err := tx.Users.GetByID(ctx, ride.DriverID)
			if err != nil {
				return err
			}
			if err := s.escrow.RenewLocked(ctx, tx, p, b, driver); err != nil {
				return err
			}
			if p.Status == domain.PaymentFailed {
				// Card can no longer be charged: release the seat.
				_, err = tx.Bookings.UpdateStatus(ctx, id, domain.BookingExpired)
				return err
			}
			n++
			return nil
		})
		if err != nil {
			errs = append(errs, fmt.Errorf("renew hold for booking %s: %w", id, err))
		}
	}
	return n, errors.Join(errs...)
}
