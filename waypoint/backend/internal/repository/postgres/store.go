// Package postgres implements persistence on PostgreSQL 16 + PostGIS.
package postgres

import (
	"context"
	"embed"
	"errors"
	"fmt"
	"io/fs"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/domain"
)

//go:embed migrations/*.sql
var migrationsFS embed.FS

// Querier is satisfied by both *pgxpool.Pool and pgx.Tx.
type Querier interface {
	Exec(ctx context.Context, sql string, args ...any) (pgconn.CommandTag, error)
	Query(ctx context.Context, sql string, args ...any) (pgx.Rows, error)
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}

// Store groups repositories bound to a single Querier (pool or transaction).
type Store struct {
	pool     *pgxpool.Pool
	q        Querier
	Users    *UserRepo
	Rides    *RideRepo
	Bookings *BookingRepo
	Payments *PaymentRepo
	Reviews  *ReviewRepo
}

// Connect opens a connection pool and verifies connectivity.
func Connect(ctx context.Context, url string, maxConns int32) (*pgxpool.Pool, error) {
	cfg, err := pgxpool.ParseConfig(url)
	if err != nil {
		return nil, fmt.Errorf("parse database url: %w", err)
	}
	if maxConns > 0 {
		cfg.MaxConns = maxConns
	}
	cfg.MaxConnIdleTime = 5 * time.Minute
	cfg.HealthCheckPeriod = 30 * time.Second

	var pool *pgxpool.Pool
	// Retry while the database container starts up.
	for attempt := 1; attempt <= 10; attempt++ {
		pool, err = pgxpool.NewWithConfig(ctx, cfg)
		if err == nil {
			if err = pool.Ping(ctx); err == nil {
				return pool, nil
			}
			pool.Close()
		}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(time.Duration(attempt) * time.Second):
		}
	}
	return nil, fmt.Errorf("connect database: %w", err)
}

// NewStore builds a Store bound to the pool.
func NewStore(pool *pgxpool.Pool) *Store {
	return newStore(pool, pool)
}

func newStore(pool *pgxpool.Pool, q Querier) *Store {
	return &Store{
		pool:     pool,
		q:        q,
		Users:    &UserRepo{q: q},
		Rides:    &RideRepo{q: q},
		Bookings: &BookingRepo{q: q},
		Payments: &PaymentRepo{q: q},
		Reviews:  &ReviewRepo{q: q},
	}
}

// WithTx runs fn inside a serializable-safe read-committed transaction.
// Repositories on the Store passed to fn share the transaction.
func (s *Store) WithTx(ctx context.Context, fn func(tx *Store) error) error {
	if _, inTx := s.q.(pgx.Tx); inTx {
		return fn(s)
	}
	tx, err := s.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer func() { _ = tx.Rollback(context.WithoutCancel(ctx)) }()
	if err := fn(newStore(s.pool, tx)); err != nil {
		return err
	}
	if err := tx.Commit(ctx); err != nil {
		return fmt.Errorf("commit tx: %w", err)
	}
	return nil
}

// Ping checks database health.
func (s *Store) Ping(ctx context.Context) error { return s.pool.Ping(ctx) }

// MarkStripeEventProcessed records a webhook event id; returns false if already seen.
func (s *Store) MarkStripeEventProcessed(ctx context.Context, id, typ string) (bool, error) {
	tag, err := s.q.Exec(ctx,
		`INSERT INTO stripe_events (id, type) VALUES ($1, $2) ON CONFLICT (id) DO NOTHING`, id, typ)
	if err != nil {
		return false, fmt.Errorf("record stripe event: %w", err)
	}
	return tag.RowsAffected() == 1, nil
}

// UnmarkStripeEvent removes an event id so that a failed handler can be retried by Stripe.
func (s *Store) UnmarkStripeEvent(ctx context.Context, id string) error {
	_, err := s.q.Exec(ctx, `DELETE FROM stripe_events WHERE id = $1`, id)
	return err
}

// Migrate applies all pending embedded *.up.sql migrations in version order.
// It is compatible with golang-migrate's schema_migrations table layout.
func Migrate(ctx context.Context, pool *pgxpool.Pool) error {
	if _, err := pool.Exec(ctx, `CREATE TABLE IF NOT EXISTS schema_migrations (
		version BIGINT PRIMARY KEY,
		dirty   BOOLEAN NOT NULL DEFAULT FALSE
	)`); err != nil {
		return fmt.Errorf("create schema_migrations: %w", err)
	}

	entries, err := fs.ReadDir(migrationsFS, "migrations")
	if err != nil {
		return fmt.Errorf("read migrations: %w", err)
	}
	type migration struct {
		version int64
		name    string
	}
	var ups []migration
	for _, e := range entries {
		if !strings.HasSuffix(e.Name(), ".up.sql") {
			continue
		}
		v, err := strconv.ParseInt(strings.SplitN(e.Name(), "_", 2)[0], 10, 64)
		if err != nil {
			return fmt.Errorf("invalid migration name %q: %w", e.Name(), err)
		}
		ups = append(ups, migration{version: v, name: e.Name()})
	}
	sort.Slice(ups, func(i, j int) bool { return ups[i].version < ups[j].version })

	// Serialize concurrent migrators across replicas.
	conn, err := pool.Acquire(ctx)
	if err != nil {
		return fmt.Errorf("acquire conn: %w", err)
	}
	defer conn.Release()
	const lockKey = 727274636
	if _, err := conn.Exec(ctx, `SELECT pg_advisory_lock($1)`, lockKey); err != nil {
		return fmt.Errorf("advisory lock: %w", err)
	}
	defer func() { _, _ = conn.Exec(context.WithoutCancel(ctx), `SELECT pg_advisory_unlock($1)`, lockKey) }()

	for _, m := range ups {
		var dirty bool
		err := conn.QueryRow(ctx, `SELECT dirty FROM schema_migrations WHERE version = $1`, m.version).Scan(&dirty)
		switch {
		case err == nil && dirty:
			return fmt.Errorf("migration %d is dirty; fix manually", m.version)
		case err == nil:
			continue
		case !errors.Is(err, pgx.ErrNoRows):
			return fmt.Errorf("check migration %d: %w", m.version, err)
		}

		sqlBytes, err := migrationsFS.ReadFile("migrations/" + m.name)
		if err != nil {
			return fmt.Errorf("read %s: %w", m.name, err)
		}
		tx, err := conn.Begin(ctx)
		if err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, string(sqlBytes)); err != nil {
			_ = tx.Rollback(ctx)
			return fmt.Errorf("apply %s: %w", m.name, err)
		}
		if _, err := tx.Exec(ctx, `INSERT INTO schema_migrations (version, dirty) VALUES ($1, FALSE)`, m.version); err != nil {
			_ = tx.Rollback(ctx)
			return fmt.Errorf("record %s: %w", m.name, err)
		}
		if err := tx.Commit(ctx); err != nil {
			return fmt.Errorf("commit %s: %w", m.name, err)
		}
	}
	return nil
}

// mapErr converts driver errors into domain errors.
func mapErr(err error) error {
	if err == nil {
		return nil
	}
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.ErrNotFound
	}
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) {
		switch pgErr.Code {
		case "23505": // unique_violation
			return fmt.Errorf("%w: %s", domain.ErrConflict, pgErr.ConstraintName)
		case "23503": // foreign_key_violation
			return fmt.Errorf("%w: %s", domain.ErrNotFound, pgErr.ConstraintName)
		case "23514": // check_violation
			return domain.NewValidationError(pgErr.ConstraintName, "violates constraint")
		}
	}
	return err
}
