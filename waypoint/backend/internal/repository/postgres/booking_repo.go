package postgres

import (
	"context"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/domain"
)

// BookingRepo persists bookings.
type BookingRepo struct{ q Querier }

const bookingColumns = `b.id, b.ride_id, b.passenger_id, b.seats,
	b.pickup_name, ST_Y(b.pickup::geometry), ST_X(b.pickup::geometry),
	b.dropoff_name, ST_Y(b.dropoff::geometry), ST_X(b.dropoff::geometry),
	b.pickup_fraction, b.dropoff_fraction, b.pickup_eta, b.dropoff_eta,
	b.price_cents, b.service_fee_cents, b.total_cents, b.currency, b.status::text, b.message,
	b.created_at, b.updated_at, b.responded_at, b.cancelled_at, b.completed_at`

func scanBooking(row pgx.Row) (*domain.Booking, error) {
	var b domain.Booking
	var status string
	err := row.Scan(&b.ID, &b.RideID, &b.PassengerID, &b.Seats,
		&b.Pickup.Name, &b.Pickup.Location.Lat, &b.Pickup.Location.Lng,
		&b.Dropoff.Name, &b.Dropoff.Location.Lat, &b.Dropoff.Location.Lng,
		&b.PickupFraction, &b.DropoffFraction, &b.PickupETA, &b.DropoffETA,
		&b.PriceCents, &b.ServiceFeeCents, &b.TotalCents, &b.Currency, &status, &b.Message,
		&b.CreatedAt, &b.UpdatedAt, &b.RespondedAt, &b.CancelledAt, &b.CompletedAt)
	if err != nil {
		return nil, mapErr(err)
	}
	b.Status = domain.BookingStatus(status)
	return &b, nil
}

func collectBookings(rows pgx.Rows, err error) ([]domain.Booking, error) {
	if err != nil {
		return nil, mapErr(err)
	}
	defer rows.Close()
	var out []domain.Booking
	for rows.Next() {
		b, err := scanBooking(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, *b)
	}
	return out, mapErr(rows.Err())
}

func activeStatusStrings() []string {
	out := make([]string, len(domain.ActiveBookingStatuses))
	for i, s := range domain.ActiveBookingStatuses {
		out[i] = string(s)
	}
	return out
}

// Create inserts a booking (status payment_pending).
func (r *BookingRepo) Create(ctx context.Context, b *domain.Booking) (*domain.Booking, error) {
	row := r.q.QueryRow(ctx, `
		WITH inserted AS (
			INSERT INTO bookings (ride_id, passenger_id, seats,
				pickup_name, pickup, dropoff_name, dropoff,
				pickup_fraction, dropoff_fraction, pickup_eta, dropoff_eta,
				price_cents, service_fee_cents, total_cents, currency, status, message)
			VALUES ($1, $2, $3,
				$4, ST_SetSRID(ST_MakePoint($5, $6), 4326)::geography,
				$7, ST_SetSRID(ST_MakePoint($8, $9), 4326)::geography,
				$10, $11, $12, $13, $14, $15, $16, $17, $18::booking_status, $19)
			RETURNING *
		)
		SELECT `+bookingColumns+` FROM inserted b`,
		b.RideID, b.PassengerID, b.Seats,
		b.Pickup.Name, b.Pickup.Location.Lng, b.Pickup.Location.Lat,
		b.Dropoff.Name, b.Dropoff.Location.Lng, b.Dropoff.Location.Lat,
		b.PickupFraction, b.DropoffFraction, b.PickupETA, b.DropoffETA,
		b.PriceCents, b.ServiceFeeCents, b.TotalCents, b.Currency, string(b.Status), b.Message)
	return scanBooking(row)
}

// GetByID loads a booking.
func (r *BookingRepo) GetByID(ctx context.Context, id uuid.UUID) (*domain.Booking, error) {
	return scanBooking(r.q.QueryRow(ctx, `SELECT `+bookingColumns+` FROM bookings b WHERE b.id = $1`, id))
}

// LockByID loads a booking with FOR UPDATE. Must run in a transaction.
func (r *BookingRepo) LockByID(ctx context.Context, id uuid.UUID) (*domain.Booking, error) {
	return scanBooking(r.q.QueryRow(ctx, `SELECT `+bookingColumns+` FROM bookings b WHERE b.id = $1 FOR UPDATE`, id))
}

// ListByPassenger lists a passenger's bookings, newest departures first.
func (r *BookingRepo) ListByPassenger(ctx context.Context, passengerID uuid.UUID, limit int) ([]domain.Booking, error) {
	return collectBookings(r.q.Query(ctx, `SELECT `+bookingColumns+` FROM bookings b
		WHERE b.passenger_id = $1 ORDER BY b.pickup_eta DESC LIMIT $2`, passengerID, limit))
}

// ListByRide lists all bookings on a ride.
func (r *BookingRepo) ListByRide(ctx context.Context, rideID uuid.UUID) ([]domain.Booking, error) {
	return collectBookings(r.q.Query(ctx, `SELECT `+bookingColumns+` FROM bookings b
		WHERE b.ride_id = $1 ORDER BY b.pickup_fraction, b.created_at`, rideID))
}

// ListActiveByRide lists seat-holding bookings on a ride.
func (r *BookingRepo) ListActiveByRide(ctx context.Context, rideID uuid.UUID) ([]domain.Booking, error) {
	return collectBookings(r.q.Query(ctx, `SELECT `+bookingColumns+` FROM bookings b
		WHERE b.ride_id = $1 AND b.status::text = ANY($2) ORDER BY b.created_at`, rideID, activeStatusStrings()))
}

// ActiveIntervals returns the seat intervals of live bookings for each ride.
func (r *BookingRepo) ActiveIntervals(ctx context.Context, rideIDs []uuid.UUID) (map[uuid.UUID][]domain.SeatInterval, error) {
	out := make(map[uuid.UUID][]domain.SeatInterval, len(rideIDs))
	if len(rideIDs) == 0 {
		return out, nil
	}
	rows, err := r.q.Query(ctx, `
		SELECT ride_id, seats, pickup_fraction, dropoff_fraction FROM bookings
		WHERE ride_id = ANY($1) AND status::text = ANY($2)`, rideIDs, activeStatusStrings())
	if err != nil {
		return nil, mapErr(err)
	}
	defer rows.Close()
	for rows.Next() {
		var id uuid.UUID
		var si domain.SeatInterval
		if err := rows.Scan(&id, &si.Seats, &si.PickupFraction, &si.DropoffFraction); err != nil {
			return nil, mapErr(err)
		}
		out[id] = append(out[id], si)
	}
	return out, mapErr(rows.Err())
}

// UpdateStatus moves a booking to a new status and stamps the matching timestamp.
func (r *BookingRepo) UpdateStatus(ctx context.Context, id uuid.UUID, status domain.BookingStatus) (*domain.Booking, error) {
	row := r.q.QueryRow(ctx, `
		WITH updated AS (
			UPDATE bookings SET status = $2::booking_status,
				responded_at = CASE WHEN $2 IN ('confirmed', 'rejected') AND status = 'requested' THEN NOW() ELSE responded_at END,
				cancelled_at = CASE WHEN $2 IN ('cancelled_by_passenger', 'cancelled_by_driver', 'expired') THEN NOW() ELSE cancelled_at END,
				completed_at = CASE WHEN $2 = 'completed' THEN NOW() ELSE completed_at END
			WHERE id = $1
			RETURNING *
		)
		SELECT `+bookingColumns+` FROM updated b`, id, string(status))
	return scanBooking(row)
}

// ListIDsByStatusOlderThan returns booking ids in a status created before a cutoff.
func (r *BookingRepo) ListIDsByStatusOlderThan(ctx context.Context, status domain.BookingStatus, before time.Time, limit int) ([]uuid.UUID, error) {
	rows, err := r.q.Query(ctx, `
		SELECT id FROM bookings WHERE status = $1::booking_status AND created_at < $2
		ORDER BY created_at LIMIT $3`, string(status), before, limit)
	if err != nil {
		return nil, mapErr(err)
	}
	return pgx.CollectRows(rows, pgx.RowTo[uuid.UUID])
}

// ListRequestedPastPickup returns pending requests whose pickup time has passed.
func (r *BookingRepo) ListRequestedPastPickup(ctx context.Context, now time.Time, limit int) ([]uuid.UUID, error) {
	rows, err := r.q.Query(ctx, `
		SELECT id FROM bookings WHERE status IN ('requested', 'payment_pending') AND pickup_eta < $1
		ORDER BY pickup_eta LIMIT $2`, now, limit)
	if err != nil {
		return nil, mapErr(err)
	}
	return pgx.CollectRows(rows, pgx.RowTo[uuid.UUID])
}
