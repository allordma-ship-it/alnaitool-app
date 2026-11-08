package service

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/cache"
	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/domain"
	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/repository/postgres"
)

// RideService manages the driver-side ride lifecycle.
type RideService struct {
	store    *postgres.Store
	cache    *cache.Cache
	routing  RoutingClient
	pricing  Pricing
	escrow   *EscrowService
	bookings *BookingService
	log      *slog.Logger
	now      func() time.Time
}

// NewRideService builds the ride service.
func NewRideService(store *postgres.Store, c *cache.Cache, routing RoutingClient, pricing Pricing,
	escrow *EscrowService, bookings *BookingService, log *slog.Logger) *RideService {
	return &RideService{store: store, cache: c, routing: routing, pricing: pricing, escrow: escrow,
		bookings: bookings, log: log, now: time.Now}
}

type cachedRoute struct {
	Points    []domain.LatLng `json:"p"`
	DistanceM int             `json:"d"`
	DurationS int             `json:"t"`
}

func (s *RideService) route(ctx context.Context, from, to domain.LatLng) (*Route, error) {
	key := fmt.Sprintf("route:v1:%.5f,%.5f:%.5f,%.5f", from.Lat, from.Lng, to.Lat, to.Lng)
	var cr cachedRoute
	if err := s.cache.GetJSON(ctx, key, &cr); err == nil && len(cr.Points) >= 2 {
		return &Route{Points: cr.Points, DistanceM: cr.DistanceM, DurationS: cr.DurationS}, nil
	}
	r, err := s.routing.Route(ctx, []domain.LatLng{from, to})
	if err != nil {
		return nil, err
	}
	if err := s.cache.SetJSON(ctx, key, cachedRoute{Points: r.Points, DistanceM: r.DistanceM, DurationS: r.DurationS}, 6*time.Hour); err != nil {
		s.log.Warn("route cache store failed", "err", err)
	}
	return r, nil
}

// Preview computes the route and a price suggestion before publishing.
func (s *RideService) Preview(ctx context.Context, from, to domain.LatLng) (*domain.RoutePreview, error) {
	if !from.Valid() {
		return nil, domain.NewValidationError("origin", "must be a valid coordinate")
	}
	if !to.Valid() {
		return nil, domain.NewValidationError("destination", "must be a valid coordinate")
	}
	r, err := s.route(ctx, from, to)
	if err != nil {
		return nil, err
	}
	suggested, minP, maxP := s.pricing.SuggestedPrice(r.DistanceM)
	return &domain.RoutePreview{
		DistanceM:           r.DistanceM,
		DurationS:           r.DurationS,
		SuggestedPriceCents: suggested,
		MinPriceCents:       minP,
		MaxPriceCents:       maxP,
		RouteGeoJSON:        RouteGeoJSON(simplifyForDisplay(r.Points, 400)),
	}, nil
}

// simplifyForDisplay down-samples a polyline evenly, always keeping both endpoints.
func simplifyForDisplay(pts []domain.LatLng, max int) []domain.LatLng {
	if len(pts) <= max || max < 2 {
		return pts
	}
	out := make([]domain.LatLng, 0, max)
	step := float64(len(pts)-1) / float64(max-1)
	for i := 0; i < max-1; i++ {
		out = append(out, pts[int(float64(i)*step)])
	}
	return append(out, pts[len(pts)-1])
}

// Publish validates, routes and stores a new ride offer.
func (s *RideService) Publish(ctx context.Context, driverID uuid.UUID, in domain.PublishRideInput) (*domain.RideDetail, error) {
	if err := in.Validate(s.now()); err != nil {
		return nil, err
	}
	driver, err := s.store.Users.GetByID(ctx, driverID)
	if err != nil {
		return nil, err
	}
	if s.escrow.RequiresConnectedAccount() && !driver.PayoutsEnabled {
		return nil, fmt.Errorf("%w: complete payout onboarding before publishing rides", domain.ErrPaymentRequired)
	}
	r, err := s.route(ctx, in.Origin.Location, in.Destination.Location)
	if err != nil {
		return nil, err
	}
	_, minP, maxP := s.pricing.SuggestedPrice(r.DistanceM)
	if in.PricePerSeatCents > maxP {
		return nil, domain.NewValidationError("price_per_seat_cents",
			fmt.Sprintf("exceeds the cost-sharing maximum of %d for this distance", maxP))
	}
	if in.PricePerSeatCents < minP/2 {
		return nil, domain.NewValidationError("price_per_seat_cents",
			fmt.Sprintf("is below the minimum of %d for this distance", minP/2))
	}
	ride, err := s.store.Rides.Create(ctx, postgres.CreateRideParams{
		DriverID:  driverID,
		Input:     in,
		Route:     r.Points,
		DistanceM: r.DistanceM,
		DurationS: r.DurationS,
	})
	if err != nil {
		return nil, err
	}
	InvalidateSearch(ctx, s.cache, s.log)
	return &domain.RideDetail{Ride: *ride, Driver: driver.Public(), SeatsAvailable: ride.SeatsTotal}, nil
}

// Get returns a ride with its route, driver profile and full-route seat availability.
func (s *RideService) Get(ctx context.Context, rideID uuid.UUID) (*domain.RideDetail, error) {
	ride, err := s.store.Rides.GetByID(ctx, rideID, true)
	if err != nil {
		return nil, err
	}
	driver, err := s.store.Users.GetByID(ctx, ride.DriverID)
	if err != nil {
		return nil, err
	}
	intervals, err := s.store.Bookings.ActiveIntervals(ctx, []uuid.UUID{ride.ID})
	if err != nil {
		return nil, err
	}
	return &domain.RideDetail{
		Ride:           *ride,
		Driver:         driver.Public(),
		SeatsAvailable: AvailableSeats(ride.SeatsTotal, intervals[ride.ID], 0, 1),
	}, nil
}

// DriverRide is a ride in the driver's dashboard with booking counters.
type DriverRide struct {
	domain.RideDetail
	PendingRequests int `json:"pending_requests"`
	ConfirmedSeats  int `json:"confirmed_seats"`
}

// ListMine lists the driver's upcoming or past rides.
func (s *RideService) ListMine(ctx context.Context, driverID uuid.UUID, upcoming bool) ([]DriverRide, error) {
	rides, err := s.store.Rides.ListByDriver(ctx, driverID, upcoming, 100)
	if err != nil {
		return nil, err
	}
	driver, err := s.store.Users.GetByID(ctx, driverID)
	if err != nil {
		return nil, err
	}
	out := make([]DriverRide, 0, len(rides))
	for _, r := range rides {
		bookings, err := s.store.Bookings.ListActiveByRide(ctx, r.ID)
		if err != nil {
			return nil, err
		}
		dr := DriverRide{RideDetail: domain.RideDetail{Ride: r, Driver: driver.Public()}}
		intervals := make([]domain.SeatInterval, 0, len(bookings))
		for _, b := range bookings {
			intervals = append(intervals, domain.SeatInterval{Seats: b.Seats, PickupFraction: b.PickupFraction, DropoffFraction: b.DropoffFraction})
			switch b.Status {
			case domain.BookingRequested:
				dr.PendingRequests++
			case domain.BookingConfirmed:
				dr.ConfirmedSeats += b.Seats
			}
		}
		dr.SeatsAvailable = AvailableSeats(r.SeatsTotal, intervals, 0, 1)
		out = append(out, dr)
	}
	return out, nil
}

// Cancel cancels a scheduled ride, releasing every passenger's escrow hold.
func (s *RideService) Cancel(ctx context.Context, driverID, rideID uuid.UUID) error {
	err := s.store.WithTx(ctx, func(tx *postgres.Store) error {
		ride, err := tx.Rides.LockByID(ctx, rideID)
		if err != nil {
			return err
		}
		if ride.DriverID != driverID {
			return domain.ErrForbidden
		}
		if !ride.Status.CanTransitionTo(domain.RideCancelled) {
			return fmt.Errorf("%w: ride is %s", domain.ErrInvalidTransition, ride.Status)
		}
		bookings, err := tx.Bookings.ListActiveByRide(ctx, rideID)
		if err != nil {
			return err
		}
		for i := range bookings {
			if err := s.bookings.closeWithVoidLocked(ctx, tx, &bookings[i], domain.BookingCancelledByDriver, "ride cancelled by driver"); err != nil {
				return err
			}
		}
		return tx.Rides.UpdateStatus(ctx, rideID, domain.RideCancelled)
	})
	if err == nil {
		InvalidateSearch(ctx, s.cache, s.log)
	}
	return err
}

// Start marks a ride as in progress (allowed from one hour before departure).
func (s *RideService) Start(ctx context.Context, driverID, rideID uuid.UUID) error {
	err := s.store.WithTx(ctx, func(tx *postgres.Store) error {
		ride, err := tx.Rides.LockByID(ctx, rideID)
		if err != nil {
			return err
		}
		if ride.DriverID != driverID {
			return domain.ErrForbidden
		}
		if s.now().Before(ride.DepartureAt.Add(-time.Hour)) {
			return domain.NewValidationError("departure_at", "ride can be started at most one hour before departure")
		}
		if !ride.Status.CanTransitionTo(domain.RideInProgress) {
			return fmt.Errorf("%w: ride is %s", domain.ErrInvalidTransition, ride.Status)
		}
		// Unanswered requests and unpaid bookings lapse once the ride starts.
		bookings, err := tx.Bookings.ListActiveByRide(ctx, rideID)
		if err != nil {
			return err
		}
		for i := range bookings {
			if bookings[i].Status != domain.BookingConfirmed {
				if err := s.bookings.closeWithVoidLocked(ctx, tx, &bookings[i], domain.BookingExpired, "ride started"); err != nil {
					return err
				}
			}
		}
		return tx.Rides.UpdateStatus(ctx, rideID, domain.RideInProgress)
	})
	if err == nil {
		InvalidateSearch(ctx, s.cache, s.log)
	}
	return err
}

// Complete closes a ride after departure and captures escrow for confirmed bookings.
func (s *RideService) Complete(ctx context.Context, driverID, rideID uuid.UUID) error {
	return s.complete(ctx, rideID, func(r *domain.Ride) error {
		if r.DriverID != driverID {
			return domain.ErrForbidden
		}
		if s.now().Before(r.DepartureAt) {
			return domain.NewValidationError("departure_at", "ride cannot be completed before departure")
		}
		return nil
	})
}

// AutoComplete closes a ride on behalf of the system (worker).
func (s *RideService) AutoComplete(ctx context.Context, rideID uuid.UUID) error {
	return s.complete(ctx, rideID, func(*domain.Ride) error { return nil })
}

func (s *RideService) complete(ctx context.Context, rideID uuid.UUID, guard func(*domain.Ride) error) error {
	var failures []error
	err := s.store.WithTx(ctx, func(tx *postgres.Store) error {
		ride, err := tx.Rides.LockByID(ctx, rideID)
		if err != nil {
			return err
		}
		if err := guard(ride); err != nil {
			return err
		}
		if !ride.Status.CanTransitionTo(domain.RideCompleted) {
			return fmt.Errorf("%w: ride is %s", domain.ErrInvalidTransition, ride.Status)
		}
		bookings, err := tx.Bookings.ListActiveByRide(ctx, rideID)
		if err != nil {
			return err
		}
		for i := range bookings {
			b := &bookings[i]
			if b.Status == domain.BookingConfirmed {
				if err := s.bookings.completeLocked(ctx, tx, b, "ride completed"); err != nil {
					// Keep the booking confirmed so the worker retries capture later.
					s.log.Error("capture on ride completion failed", "booking", b.ID, "err", err)
					failures = append(failures, err)
				}
				continue
			}
			if err := s.bookings.closeWithVoidLocked(ctx, tx, b, domain.BookingExpired, "ride completed"); err != nil {
				return err
			}
		}
		if len(failures) > 0 {
			return nil // ride stays open; payments retried by the worker
		}
		return tx.Rides.UpdateStatus(ctx, rideID, domain.RideCompleted)
	})
	if err != nil {
		return err
	}
	if len(failures) > 0 {
		return fmt.Errorf("%w: %d payment capture(s) pending retry", domain.ErrUpstream, len(failures))
	}
	return nil
}

// normalizeEmail lowercases and trims an email address.
func normalizeEmail(s string) string { return strings.ToLower(strings.TrimSpace(s)) }

// IsRetryable reports whether an error is transient (used by the worker).
func IsRetryable(err error) bool { return errors.Is(err, domain.ErrUpstream) }
