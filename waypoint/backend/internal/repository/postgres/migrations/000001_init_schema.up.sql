-- WayPoint initial schema: users, rides (PostGIS routes), bookings, escrow payments, reviews.

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS citext;

CREATE OR REPLACE FUNCTION set_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- Users
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email               CITEXT NOT NULL UNIQUE,
    phone               TEXT,
    password_hash       TEXT NOT NULL,
    first_name          TEXT NOT NULL CHECK (char_length(first_name) BETWEEN 1 AND 80),
    last_name           TEXT NOT NULL CHECK (char_length(last_name) BETWEEN 1 AND 80),
    bio                 TEXT NOT NULL DEFAULT '' CHECK (char_length(bio) <= 1000),
    avatar_url          TEXT,
    rating_sum          INTEGER NOT NULL DEFAULT 0 CHECK (rating_sum >= 0),
    rating_count        INTEGER NOT NULL DEFAULT 0 CHECK (rating_count >= 0),
    stripe_customer_id  TEXT UNIQUE,
    stripe_account_id   TEXT UNIQUE,
    payouts_enabled     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TRIGGER users_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- Rides
-- ---------------------------------------------------------------------------
CREATE TYPE ride_status AS ENUM ('scheduled', 'in_progress', 'completed', 'cancelled');

CREATE TABLE rides (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    driver_id             UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    origin_name           TEXT NOT NULL,
    origin                GEOGRAPHY(Point, 4326) NOT NULL,
    destination_name      TEXT NOT NULL,
    destination           GEOGRAPHY(Point, 4326) NOT NULL,
    route                 GEOGRAPHY(LineString, 4326) NOT NULL,
    distance_m            INTEGER NOT NULL CHECK (distance_m > 0),
    duration_s            INTEGER NOT NULL CHECK (duration_s > 0),
    departure_at          TIMESTAMPTZ NOT NULL,
    arrival_at            TIMESTAMPTZ NOT NULL,
    seats_total           SMALLINT NOT NULL CHECK (seats_total BETWEEN 1 AND 8),
    price_per_seat_cents  INTEGER NOT NULL CHECK (price_per_seat_cents > 0),
    currency              CHAR(3) NOT NULL DEFAULT 'EUR',
    instant_booking       BOOLEAN NOT NULL DEFAULT TRUE,
    allow_smoking         BOOLEAN NOT NULL DEFAULT FALSE,
    allow_pets            BOOLEAN NOT NULL DEFAULT FALSE,
    max_two_back          BOOLEAN NOT NULL DEFAULT TRUE,
    vehicle_description   TEXT NOT NULL DEFAULT '',
    notes                 TEXT NOT NULL DEFAULT '' CHECK (char_length(notes) <= 2000),
    status                ride_status NOT NULL DEFAULT 'scheduled',
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    cancelled_at          TIMESTAMPTZ,
    CHECK (arrival_at > departure_at)
);

CREATE INDEX rides_route_gix       ON rides USING GIST (route);
CREATE INDEX rides_origin_gix      ON rides USING GIST (origin);
CREATE INDEX rides_destination_gix ON rides USING GIST (destination);
CREATE INDEX rides_status_departure_idx ON rides (status, departure_at);
CREATE INDEX rides_driver_departure_idx ON rides (driver_id, departure_at DESC);

CREATE TRIGGER rides_updated_at BEFORE UPDATE ON rides
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- Bookings
-- ---------------------------------------------------------------------------
CREATE TYPE booking_status AS ENUM (
    'payment_pending',
    'requested',
    'confirmed',
    'rejected',
    'cancelled_by_passenger',
    'cancelled_by_driver',
    'expired',
    'completed'
);

CREATE TABLE bookings (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ride_id            UUID NOT NULL REFERENCES rides(id) ON DELETE RESTRICT,
    passenger_id       UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    seats              SMALLINT NOT NULL CHECK (seats BETWEEN 1 AND 8),
    pickup_name        TEXT NOT NULL,
    pickup             GEOGRAPHY(Point, 4326) NOT NULL,
    dropoff_name       TEXT NOT NULL,
    dropoff            GEOGRAPHY(Point, 4326) NOT NULL,
    pickup_fraction    DOUBLE PRECISION NOT NULL CHECK (pickup_fraction >= 0 AND pickup_fraction <= 1),
    dropoff_fraction   DOUBLE PRECISION NOT NULL CHECK (dropoff_fraction >= 0 AND dropoff_fraction <= 1),
    pickup_eta         TIMESTAMPTZ NOT NULL,
    dropoff_eta        TIMESTAMPTZ NOT NULL,
    price_cents        INTEGER NOT NULL CHECK (price_cents > 0),
    service_fee_cents  INTEGER NOT NULL CHECK (service_fee_cents >= 0),
    total_cents        INTEGER NOT NULL CHECK (total_cents = price_cents + service_fee_cents),
    currency           CHAR(3) NOT NULL,
    status             booking_status NOT NULL DEFAULT 'payment_pending',
    message            TEXT NOT NULL DEFAULT '' CHECK (char_length(message) <= 1000),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    responded_at       TIMESTAMPTZ,
    cancelled_at       TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    CHECK (pickup_fraction < dropoff_fraction)
);

CREATE INDEX bookings_ride_status_idx ON bookings (ride_id, status);
CREATE INDEX bookings_passenger_idx   ON bookings (passenger_id, created_at DESC);
CREATE INDEX bookings_status_created_idx ON bookings (status, created_at);

-- A passenger may hold at most one live booking per ride.
CREATE UNIQUE INDEX bookings_one_active_per_passenger
    ON bookings (ride_id, passenger_id)
    WHERE status IN ('payment_pending', 'requested', 'confirmed');

CREATE TRIGGER bookings_updated_at BEFORE UPDATE ON bookings
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- Escrow payments
-- ---------------------------------------------------------------------------
CREATE TYPE payment_status AS ENUM (
    'pending',      -- intent created, awaiting payment method / authorization
    'authorized',   -- funds held on passenger card (escrow)
    'captured',     -- funds captured; driver share transferred to connected account
    'voided',       -- hold released without charge
    'refunded',     -- captured funds returned (fully or partially)
    'failed'        -- authorization failed / intent unusable
);

CREATE TABLE payments (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id          UUID NOT NULL UNIQUE REFERENCES bookings(id) ON DELETE RESTRICT,
    provider            TEXT NOT NULL CHECK (provider IN ('stripe', 'simulated')),
    provider_intent_id  TEXT NOT NULL UNIQUE,
    client_secret       TEXT NOT NULL DEFAULT '',
    amount_cents        INTEGER NOT NULL CHECK (amount_cents > 0),
    fee_cents           INTEGER NOT NULL CHECK (fee_cents >= 0),
    captured_cents      INTEGER NOT NULL DEFAULT 0 CHECK (captured_cents >= 0),
    refunded_cents      INTEGER NOT NULL DEFAULT 0 CHECK (refunded_cents >= 0),
    currency            CHAR(3) NOT NULL,
    status              payment_status NOT NULL DEFAULT 'pending',
    last_error          TEXT NOT NULL DEFAULT '',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    authorized_at       TIMESTAMPTZ,
    captured_at         TIMESTAMPTZ,
    voided_at           TIMESTAMPTZ,
    refunded_at         TIMESTAMPTZ,
    CHECK (captured_cents <= amount_cents),
    CHECK (refunded_cents <= captured_cents)
);

CREATE INDEX payments_status_idx ON payments (status);

CREATE TRIGGER payments_updated_at BEFORE UPDATE ON payments
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Immutable audit log of every escrow state transition.
CREATE TABLE payment_events (
    id           BIGSERIAL PRIMARY KEY,
    payment_id   UUID NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
    from_status  payment_status,
    to_status    payment_status NOT NULL,
    amount_cents INTEGER NOT NULL DEFAULT 0,
    reason       TEXT NOT NULL DEFAULT '',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX payment_events_payment_idx ON payment_events (payment_id, created_at);

-- Idempotency store for processed Stripe webhook events.
CREATE TABLE stripe_events (
    id           TEXT PRIMARY KEY,
    type         TEXT NOT NULL,
    received_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ---------------------------------------------------------------------------
-- Reviews
-- ---------------------------------------------------------------------------
CREATE TABLE reviews (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id  UUID NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    author_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    subject_id  UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    rating      SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment     TEXT NOT NULL DEFAULT '' CHECK (char_length(comment) <= 1000),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (booking_id, author_id),
    CHECK (author_id <> subject_id)
);

CREATE INDEX reviews_subject_idx ON reviews (subject_id, created_at DESC);
