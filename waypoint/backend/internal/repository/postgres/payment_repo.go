package postgres

import (
	"context"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/domain"
)

// PaymentRepo persists escrow payments and their transition audit log.
type PaymentRepo struct{ q Querier }

const paymentColumns = `id, booking_id, provider, provider_intent_id, client_secret, amount_cents, fee_cents,
	captured_cents, refunded_cents, currency, status::text, last_error, created_at, updated_at,
	authorized_at, captured_at, voided_at, refunded_at`

func scanPayment(row pgx.Row) (*domain.Payment, error) {
	var p domain.Payment
	var status string
	err := row.Scan(&p.ID, &p.BookingID, &p.Provider, &p.ProviderIntentID, &p.ClientSecret, &p.AmountCents, &p.FeeCents,
		&p.CapturedCents, &p.RefundedCents, &p.Currency, &status, &p.LastError, &p.CreatedAt, &p.UpdatedAt,
		&p.AuthorizedAt, &p.CapturedAt, &p.VoidedAt, &p.RefundedAt)
	if err != nil {
		return nil, mapErr(err)
	}
	p.Status = domain.PaymentStatus(status)
	return &p, nil
}

// Create inserts a payment record in pending state and logs the creation event.
func (r *PaymentRepo) Create(ctx context.Context, p *domain.Payment) (*domain.Payment, error) {
	created, err := scanPayment(r.q.QueryRow(ctx, `
		INSERT INTO payments (booking_id, provider, provider_intent_id, client_secret, amount_cents, fee_cents, currency, status)
		VALUES ($1, $2, $3, $4, $5, $6, $7, 'pending')
		RETURNING `+paymentColumns,
		p.BookingID, p.Provider, p.ProviderIntentID, p.ClientSecret, p.AmountCents, p.FeeCents, p.Currency))
	if err != nil {
		return nil, err
	}
	if err := r.InsertEvent(ctx, created.ID, nil, domain.PaymentPending, created.AmountCents, "intent created"); err != nil {
		return nil, err
	}
	return created, nil
}

// GetByBooking loads the payment attached to a booking.
func (r *PaymentRepo) GetByBooking(ctx context.Context, bookingID uuid.UUID) (*domain.Payment, error) {
	return scanPayment(r.q.QueryRow(ctx, `SELECT `+paymentColumns+` FROM payments WHERE booking_id = $1`, bookingID))
}

// GetManyByBooking loads payments for several bookings.
func (r *PaymentRepo) GetManyByBooking(ctx context.Context, bookingIDs []uuid.UUID) (map[uuid.UUID]*domain.Payment, error) {
	out := make(map[uuid.UUID]*domain.Payment, len(bookingIDs))
	if len(bookingIDs) == 0 {
		return out, nil
	}
	rows, err := r.q.Query(ctx, `SELECT `+paymentColumns+` FROM payments WHERE booking_id = ANY($1)`, bookingIDs)
	if err != nil {
		return nil, mapErr(err)
	}
	defer rows.Close()
	for rows.Next() {
		p, err := scanPayment(rows)
		if err != nil {
			return nil, err
		}
		out[p.BookingID] = p
	}
	return out, mapErr(rows.Err())
}

// LockByBooking loads the payment FOR UPDATE.
func (r *PaymentRepo) LockByBooking(ctx context.Context, bookingID uuid.UUID) (*domain.Payment, error) {
	return scanPayment(r.q.QueryRow(ctx, `SELECT `+paymentColumns+` FROM payments WHERE booking_id = $1 FOR UPDATE`, bookingID))
}

// LockByIntent loads the payment owning a provider intent FOR UPDATE.
func (r *PaymentRepo) LockByIntent(ctx context.Context, intentID string) (*domain.Payment, error) {
	return scanPayment(r.q.QueryRow(ctx, `SELECT `+paymentColumns+` FROM payments WHERE provider_intent_id = $1 FOR UPDATE`, intentID))
}

// Save persists the mutable escrow fields and appends an audit event when the status changed.
func (r *PaymentRepo) Save(ctx context.Context, p *domain.Payment, from domain.PaymentStatus, eventAmount int, reason string) error {
	_, err := r.q.Exec(ctx, `
		UPDATE payments SET
			status = $2::payment_status, captured_cents = $3, refunded_cents = $4, fee_cents = $5, last_error = $6,
			authorized_at = $7, captured_at = $8, voided_at = $9, refunded_at = $10, client_secret = $11
		WHERE id = $1`,
		p.ID, string(p.Status), p.CapturedCents, p.RefundedCents, p.FeeCents, p.LastError,
		p.AuthorizedAt, p.CapturedAt, p.VoidedAt, p.RefundedAt, p.ClientSecret)
	if err != nil {
		return mapErr(err)
	}
	if from != p.Status {
		f := from
		return r.InsertEvent(ctx, p.ID, &f, p.Status, eventAmount, reason)
	}
	return nil
}

// InsertEvent appends an entry to the escrow audit log.
func (r *PaymentRepo) InsertEvent(ctx context.Context, paymentID uuid.UUID, from *domain.PaymentStatus, to domain.PaymentStatus, amount int, reason string) error {
	var fromStr *string
	if from != nil {
		s := string(*from)
		fromStr = &s
	}
	_, err := r.q.Exec(ctx, `
		INSERT INTO payment_events (payment_id, from_status, to_status, amount_cents, reason)
		VALUES ($1, $2::payment_status, $3::payment_status, $4, $5)`,
		paymentID, fromStr, string(to), amount, reason)
	return mapErr(err)
}

// ReplaceIntent points the payment at a new provider intent (hold renewal).
func (r *PaymentRepo) ReplaceIntent(ctx context.Context, paymentID uuid.UUID, intentID, clientSecret string) error {
	_, err := r.q.Exec(ctx, `UPDATE payments SET provider_intent_id = $2, client_secret = $3 WHERE id = $1`,
		paymentID, intentID, clientSecret)
	return mapErr(err)
}

// ListAuthorizedBefore returns booking ids whose hold was authorized before the cutoff
// and is still outstanding (candidates for re-authorization).
func (r *PaymentRepo) ListAuthorizedBefore(ctx context.Context, before time.Time, limit int) ([]uuid.UUID, error) {
	rows, err := r.q.Query(ctx, `
		SELECT p.booking_id FROM payments p
		JOIN bookings b ON b.id = p.booking_id
		WHERE p.status = 'authorized' AND p.authorized_at < $1
		  AND b.status IN ('requested', 'confirmed')
		ORDER BY p.authorized_at LIMIT $2`, before, limit)
	if err != nil {
		return nil, mapErr(err)
	}
	return pgx.CollectRows(rows, pgx.RowTo[uuid.UUID])
}
