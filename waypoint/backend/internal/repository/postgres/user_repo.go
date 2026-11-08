package postgres

import (
	"context"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/domain"
)

// UserRepo persists users.
type UserRepo struct{ q Querier }

const userColumns = `id, email, phone, password_hash, first_name, last_name, bio, avatar_url,
	rating_sum, rating_count, stripe_customer_id, stripe_account_id, payouts_enabled, created_at, updated_at`

func scanUser(row pgx.Row) (*domain.User, error) {
	var u domain.User
	err := row.Scan(&u.ID, &u.Email, &u.Phone, &u.PasswordHash, &u.FirstName, &u.LastName, &u.Bio, &u.AvatarURL,
		&u.RatingSum, &u.RatingCount, &u.StripeCustomerID, &u.StripeAccountID, &u.PayoutsEnabled, &u.CreatedAt, &u.UpdatedAt)
	if err != nil {
		return nil, mapErr(err)
	}
	return &u, nil
}

// Create inserts a new user.
func (r *UserRepo) Create(ctx context.Context, in domain.RegisterInput, passwordHash string) (*domain.User, error) {
	row := r.q.QueryRow(ctx, `
		INSERT INTO users (email, phone, password_hash, first_name, last_name)
		VALUES ($1, $2, $3, $4, $5)
		RETURNING `+userColumns,
		in.Email, in.Phone, passwordHash, in.FirstName, in.LastName)
	return scanUser(row)
}

// GetByID loads a user by id.
func (r *UserRepo) GetByID(ctx context.Context, id uuid.UUID) (*domain.User, error) {
	return scanUser(r.q.QueryRow(ctx, `SELECT `+userColumns+` FROM users WHERE id = $1`, id))
}

// GetByEmail loads a user by email (case-insensitive).
func (r *UserRepo) GetByEmail(ctx context.Context, email string) (*domain.User, error) {
	return scanUser(r.q.QueryRow(ctx, `SELECT `+userColumns+` FROM users WHERE email = $1`, email))
}

// GetByStripeAccount loads the driver owning a connected account.
func (r *UserRepo) GetByStripeAccount(ctx context.Context, accountID string) (*domain.User, error) {
	return scanUser(r.q.QueryRow(ctx, `SELECT `+userColumns+` FROM users WHERE stripe_account_id = $1`, accountID))
}

// GetMany loads users by id into a map.
func (r *UserRepo) GetMany(ctx context.Context, ids []uuid.UUID) (map[uuid.UUID]*domain.User, error) {
	out := make(map[uuid.UUID]*domain.User, len(ids))
	if len(ids) == 0 {
		return out, nil
	}
	rows, err := r.q.Query(ctx, `SELECT `+userColumns+` FROM users WHERE id = ANY($1)`, ids)
	if err != nil {
		return nil, mapErr(err)
	}
	defer rows.Close()
	for rows.Next() {
		u, err := scanUser(rows)
		if err != nil {
			return nil, err
		}
		out[u.ID] = u
	}
	return out, mapErr(rows.Err())
}

// UpdateProfile applies optional profile changes.
func (r *UserRepo) UpdateProfile(ctx context.Context, id uuid.UUID, in domain.UpdateProfileInput) (*domain.User, error) {
	row := r.q.QueryRow(ctx, `
		UPDATE users SET
			first_name = COALESCE($2, first_name),
			last_name  = COALESCE($3, last_name),
			bio        = COALESCE($4, bio),
			phone      = COALESCE(NULLIF($5, ''), phone),
			avatar_url = CASE WHEN $6::text IS NULL THEN avatar_url ELSE NULLIF($6, '') END
		WHERE id = $1
		RETURNING `+userColumns,
		id, in.FirstName, in.LastName, in.Bio, in.Phone, in.AvatarURL)
	return scanUser(row)
}

// SetStripeCustomer stores the passenger's Stripe customer id.
func (r *UserRepo) SetStripeCustomer(ctx context.Context, id uuid.UUID, customerID string) error {
	_, err := r.q.Exec(ctx, `UPDATE users SET stripe_customer_id = $2 WHERE id = $1`, id, customerID)
	return mapErr(err)
}

// SetStripeAccount stores the driver's connected account id.
func (r *UserRepo) SetStripeAccount(ctx context.Context, id uuid.UUID, accountID string) error {
	_, err := r.q.Exec(ctx, `UPDATE users SET stripe_account_id = $2 WHERE id = $1`, id, accountID)
	return mapErr(err)
}

// SetPayoutsEnabled flags whether the driver can receive transfers.
func (r *UserRepo) SetPayoutsEnabled(ctx context.Context, id uuid.UUID, enabled bool) error {
	_, err := r.q.Exec(ctx, `UPDATE users SET payouts_enabled = $2 WHERE id = $1`, id, enabled)
	return mapErr(err)
}

// AddRating atomically folds a new rating into the user's aggregate.
func (r *UserRepo) AddRating(ctx context.Context, id uuid.UUID, rating int) error {
	_, err := r.q.Exec(ctx,
		`UPDATE users SET rating_sum = rating_sum + $2, rating_count = rating_count + 1 WHERE id = $1`, id, rating)
	return mapErr(err)
}
