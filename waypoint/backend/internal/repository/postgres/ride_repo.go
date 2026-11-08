package postgres

import (
	"context"
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/domain"
)

// RideRepo persists rides and runs PostGIS spatial queries against routes.
type RideRepo struct{ q Querier }

const rideColumns = `r.id, r.driver_id,
	r.origin_name, ST_Y(r.origin::geometry), ST_X(r.origin::geometry),
	r.destination_name, ST_Y(r.destination::geometry), ST_X(r.destination::geometry),
	r.distance_m, r.duration_s, r.departure_at, r.arrival_at, r.seats_total,
	r.price_per_seat_cents, r.currency, r.instant_booking, r.allow_smoking, r.allow_pets, r.max_two_back,
	r.vehicle_description, r.notes, r.status::text, r.created_at, r.updated_at, r.cancelled_at`

func rideScanTargets(r *domain.Ride, status *string) []any {
	return []any{&r.ID, &r.DriverID,
		&r.Origin.Name, &r.Origin.Location.Lat, &r.Origin.Location.Lng,
		&r.Destination.Name, &r.Destination.Location.Lat, &r.Destination.Location.Lng,
		&r.DistanceM, &r.DurationS, &r.DepartureAt, &r.ArrivalAt, &r.SeatsTotal,
		&r.PricePerSeatCents, &r.Currency, &r.Preferences.InstantBooking, &r.Preferences.AllowSmoking,
		&r.Preferences.AllowPets, &r.Preferences.MaxTwoBack,
		&r.VehicleDescription, &r.Notes, status, &r.CreatedAt, &r.UpdatedAt, &r.CancelledAt}
}

func scanRide(row pgx.Row, extra ...any) (*domain.Ride, error) {
	var r domain.Ride
	var status string
	if err := row.Scan(append(rideScanTargets(&r, &status), extra...)...); err != nil {
		return nil, mapErr(err)
	}
	r.Status = domain.RideStatus(status)
	return &r, nil
}

// LineStringWKT encodes a polyline as EWKT for PostGIS.
func LineStringWKT(points []domain.LatLng) string {
	var b strings.Builder
	b.Grow(len(points) * 24)
	b.WriteString("SRID=4326;LINESTRING(")
	for i, p := range points {
		if i > 0 {
			b.WriteByte(',')
		}
		b.WriteString(strconv.FormatFloat(p.Lng, 'f', 6, 64))
		b.WriteByte(' ')
		b.WriteString(strconv.FormatFloat(p.Lat, 'f', 6, 64))
	}
	b.WriteByte(')')
	return b.String()
}

// CreateRideParams bundles everything needed to persist a ride.
type CreateRideParams struct {
	DriverID  uuid.UUID
	Input     domain.PublishRideInput
	Route     []domain.LatLng
	DistanceM int
	DurationS int
}

// Create inserts a ride with its route geometry.
func (r *RideRepo) Create(ctx context.Context, p CreateRideParams) (*domain.Ride, error) {
	if len(p.Route) < 2 {
		return nil, domain.NewValidationError("route", "must contain at least two points")
	}
	in := p.Input
	arrival := in.DepartureAt.Add(time.Duration(p.DurationS) * time.Second)
	row := r.q.QueryRow(ctx, `
		WITH inserted AS (
			INSERT INTO rides (driver_id, origin_name, origin, destination_name, destination, route,
				distance_m, duration_s, departure_at, arrival_at, seats_total, price_per_seat_cents, currency,
				instant_booking, allow_smoking, allow_pets, max_two_back, vehicle_description, notes)
			VALUES ($1, $2, ST_SetSRID(ST_MakePoint($3, $4), 4326)::geography,
				$5, ST_SetSRID(ST_MakePoint($6, $7), 4326)::geography,
				ST_GeogFromText($8), $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20, $21)
			RETURNING *
		)
		SELECT `+rideColumns+`, ST_AsGeoJSON(ST_Simplify(r.route::geometry, 0.0001), 6)
		FROM inserted r`,
		p.DriverID, in.Origin.Name, in.Origin.Location.Lng, in.Origin.Location.Lat,
		in.Destination.Name, in.Destination.Location.Lng, in.Destination.Location.Lat,
		LineStringWKT(p.Route), p.DistanceM, p.DurationS, in.DepartureAt, arrival, in.SeatsTotal,
		in.PricePerSeatCents, in.Currency, in.Preferences.InstantBooking, in.Preferences.AllowSmoking,
		in.Preferences.AllowPets, in.Preferences.MaxTwoBack, in.VehicleDescription, in.Notes)
	var geo string
	ride, err := scanRide(row, &geo)
	if err != nil {
		return nil, err
	}
	ride.RouteGeoJSON = json.RawMessage(geo)
	return ride, nil
}

// GetByID loads a ride; withRoute includes a simplified GeoJSON LineString.
func (r *RideRepo) GetByID(ctx context.Context, id uuid.UUID, withRoute bool) (*domain.Ride, error) {
	if !withRoute {
		return scanRide(r.q.QueryRow(ctx, `SELECT `+rideColumns+` FROM rides r WHERE r.id = $1`, id))
	}
	var geo string
	ride, err := scanRide(r.q.QueryRow(ctx,
		`SELECT `+rideColumns+`, ST_AsGeoJSON(ST_Simplify(r.route::geometry, 0.0001), 6) FROM rides r WHERE r.id = $1`, id), &geo)
	if err != nil {
		return nil, err
	}
	ride.RouteGeoJSON = json.RawMessage(geo)
	return ride, nil
}

// LockByID loads a ride with a row-level lock (FOR UPDATE). Must run in a transaction.
func (r *RideRepo) LockByID(ctx context.Context, id uuid.UUID) (*domain.Ride, error) {
	return scanRide(r.q.QueryRow(ctx, `SELECT `+rideColumns+` FROM rides r WHERE r.id = $1 FOR UPDATE`, id))
}

// GetMany loads rides by id into a map (without routes).
func (r *RideRepo) GetMany(ctx context.Context, ids []uuid.UUID) (map[uuid.UUID]*domain.Ride, error) {
	out := make(map[uuid.UUID]*domain.Ride, len(ids))
	if len(ids) == 0 {
		return out, nil
	}
	rows, err := r.q.Query(ctx, `SELECT `+rideColumns+` FROM rides r WHERE r.id = ANY($1)`, ids)
	if err != nil {
		return nil, mapErr(err)
	}
	defer rows.Close()
	for rows.Next() {
		ride, err := scanRide(rows)
		if err != nil {
			return nil, err
		}
		out[ride.ID] = ride
	}
	return out, mapErr(rows.Err())
}

// ListByDriver returns a driver's rides; upcoming=true lists future ones ascending.
func (r *RideRepo) ListByDriver(ctx context.Context, driverID uuid.UUID, upcoming bool, limit int) ([]domain.Ride, error) {
	query := `SELECT ` + rideColumns + ` FROM rides r WHERE r.driver_id = $1 AND r.arrival_at >= NOW() - INTERVAL '1 day'
		ORDER BY r.departure_at ASC LIMIT $2`
	if !upcoming {
		query = `SELECT ` + rideColumns + ` FROM rides r WHERE r.driver_id = $1 AND r.arrival_at < NOW() - INTERVAL '1 day'
		ORDER BY r.departure_at DESC LIMIT $2`
	}
	rows, err := r.q.Query(ctx, query, driverID, limit)
	if err != nil {
		return nil, mapErr(err)
	}
	defer rows.Close()
	var out []domain.Ride
	for rows.Next() {
		ride, err := scanRide(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, *ride)
	}
	return out, mapErr(rows.Err())
}

// UpdateStatus moves a ride to a new status.
func (r *RideRepo) UpdateStatus(ctx context.Context, id uuid.UUID, status domain.RideStatus) error {
	tag, err := r.q.Exec(ctx, `
		UPDATE rides SET status = $2::ride_status,
			cancelled_at = CASE WHEN $2::ride_status = 'cancelled' THEN NOW() ELSE cancelled_at END
		WHERE id = $1`, id, string(status))
	if err != nil {
		return mapErr(err)
	}
	if tag.RowsAffected() == 0 {
		return domain.ErrNotFound
	}
	return nil
}

// ListDueForCompletion returns rides that ended more than `after` ago and are not closed.
func (r *RideRepo) ListDueForCompletion(ctx context.Context, after time.Duration, limit int) ([]uuid.UUID, error) {
	rows, err := r.q.Query(ctx, `
		SELECT id FROM rides
		WHERE status IN ('scheduled', 'in_progress') AND arrival_at < NOW() - make_interval(secs => $1)
		ORDER BY arrival_at LIMIT $2`, after.Seconds(), limit)
	if err != nil {
		return nil, mapErr(err)
	}
	return pgx.CollectRows(rows, pgx.RowTo[uuid.UUID])
}

// RouteProjection is a point snapped onto a ride's route.
type RouteProjection struct {
	Fraction  float64
	Snapped   domain.LatLng
	DistanceM float64
}

// ProjectOnRoute snaps a coordinate onto the ride's route and measures the gap.
func (r *RideRepo) ProjectOnRoute(ctx context.Context, rideID uuid.UUID, p domain.LatLng) (*RouteProjection, error) {
	var out RouteProjection
	err := r.q.QueryRow(ctx, `
		WITH pt AS (SELECT ST_SetSRID(ST_MakePoint($2, $3), 4326) AS g)
		SELECT m.f,
		       ST_Y(ST_LineInterpolatePoint(r.route::geometry, m.f)),
		       ST_X(ST_LineInterpolatePoint(r.route::geometry, m.f)),
		       ST_Distance(r.route, pt.g::geography)
		FROM rides r CROSS JOIN pt
		CROSS JOIN LATERAL (SELECT ST_LineLocatePoint(r.route::geometry, pt.g) AS f) m
		WHERE r.id = $1`, rideID, p.Lng, p.Lat).
		Scan(&out.Fraction, &out.Snapped.Lat, &out.Snapped.Lng, &out.DistanceM)
	if err != nil {
		return nil, mapErr(err)
	}
	return &out, nil
}

// SpatialCandidate is a raw PostGIS match before capacity, pricing and scoring.
type SpatialCandidate struct {
	Ride             domain.Ride
	PickupFraction   float64
	DropoffFraction  float64
	PickupDistanceM  float64
	DropoffDistanceM float64
	Pickup           domain.LatLng
	Dropoff          domain.LatLng
}

// SpatialSearchParams is the input to FindAlongRoute.
type SpatialSearchParams struct {
	Origin        domain.LatLng
	Destination   domain.LatLng
	PickupFrom    time.Time
	PickupTo      time.Time
	MinSeats      int
	RadiusMeters  float64
	ExcludeDriver uuid.UUID
	Limit         int
}

// FindAlongRoute finds scheduled rides whose route passes within RadiusMeters of
// both the origin and destination, in the correct travel direction, with a pickup
// ETA inside [PickupFrom, PickupTo). Uses the GiST index on rides.route via ST_DWithin.
func (r *RideRepo) FindAlongRoute(ctx context.Context, p SpatialSearchParams) ([]SpatialCandidate, error) {
	rows, err := r.q.Query(ctx, `
		WITH params AS (
			SELECT ST_SetSRID(ST_MakePoint($1, $2), 4326) AS o,
			       ST_SetSRID(ST_MakePoint($3, $4), 4326) AS d
		)
		SELECT `+rideColumns+`,
			m.pf, m.df,
			ST_Distance(r.route, p.o::geography),
			ST_Distance(r.route, p.d::geography),
			ST_Y(ST_LineInterpolatePoint(r.route::geometry, m.pf)),
			ST_X(ST_LineInterpolatePoint(r.route::geometry, m.pf)),
			ST_Y(ST_LineInterpolatePoint(r.route::geometry, m.df)),
			ST_X(ST_LineInterpolatePoint(r.route::geometry, m.df))
		FROM rides r
		CROSS JOIN params p
		CROSS JOIN LATERAL (
			SELECT ST_LineLocatePoint(r.route::geometry, p.o) AS pf,
			       ST_LineLocatePoint(r.route::geometry, p.d) AS df
		) m
		WHERE r.status = 'scheduled'
		  AND r.departure_at >= $5::timestamptz - INTERVAL '1 day'
		  AND r.departure_at <  $6::timestamptz
		  AND r.seats_total >= $7
		  AND r.driver_id <> $9
		  AND ST_DWithin(r.route, p.o::geography, $8)
		  AND ST_DWithin(r.route, p.d::geography, $8)
		  AND m.pf < m.df
		  AND r.departure_at + make_interval(secs => r.duration_s * m.pf) >= $5::timestamptz
		  AND r.departure_at + make_interval(secs => r.duration_s * m.pf) <  $6::timestamptz
		ORDER BY r.departure_at
		LIMIT $10`,
		p.Origin.Lng, p.Origin.Lat, p.Destination.Lng, p.Destination.Lat,
		p.PickupFrom, p.PickupTo, p.MinSeats, p.RadiusMeters, p.ExcludeDriver, p.Limit)
	if err != nil {
		return nil, fmt.Errorf("spatial search: %w", mapErr(err))
	}
	defer rows.Close()

	var out []SpatialCandidate
	for rows.Next() {
		var c SpatialCandidate
		ride, err := scanRide(rows, &c.PickupFraction, &c.DropoffFraction, &c.PickupDistanceM, &c.DropoffDistanceM,
			&c.Pickup.Lat, &c.Pickup.Lng, &c.Dropoff.Lat, &c.Dropoff.Lng)
		if err != nil {
			return nil, err
		}
		c.Ride = *ride
		out = append(out, c)
	}
	return out, mapErr(rows.Err())
}
