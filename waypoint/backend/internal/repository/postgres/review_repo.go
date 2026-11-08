package postgres

import (
	"context"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/domain"
)

// ReviewRepo persists post-trip reviews.
type ReviewRepo struct{ q Querier }

// Create inserts a review.
func (r *ReviewRepo) Create(ctx context.Context, rv *domain.Review) (*domain.Review, error) {
	var out domain.Review
	err := r.q.QueryRow(ctx, `
		INSERT INTO reviews (booking_id, author_id, subject_id, rating, comment)
		VALUES ($1, $2, $3, $4, $5)
		RETURNING id, booking_id, author_id, subject_id, rating, comment, created_at`,
		rv.BookingID, rv.AuthorID, rv.SubjectID, rv.Rating, rv.Comment).
		Scan(&out.ID, &out.BookingID, &out.AuthorID, &out.SubjectID, &out.Rating, &out.Comment, &out.CreatedAt)
	if err != nil {
		return nil, mapErr(err)
	}
	return &out, nil
}

// ReviewedBookings returns the subset of bookings the author has already reviewed.
func (r *ReviewRepo) ReviewedBookings(ctx context.Context, authorID uuid.UUID, bookingIDs []uuid.UUID) (map[uuid.UUID]bool, error) {
	out := make(map[uuid.UUID]bool, len(bookingIDs))
	if len(bookingIDs) == 0 {
		return out, nil
	}
	rows, err := r.q.Query(ctx, `SELECT booking_id FROM reviews WHERE author_id = $1 AND booking_id = ANY($2)`, authorID, bookingIDs)
	if err != nil {
		return nil, mapErr(err)
	}
	ids, err := pgx.CollectRows(rows, pgx.RowTo[uuid.UUID])
	if err != nil {
		return nil, mapErr(err)
	}
	for _, id := range ids {
		out[id] = true
	}
	return out, nil
}

// ListForSubject returns the most recent reviews about a user.
func (r *ReviewRepo) ListForSubject(ctx context.Context, subjectID uuid.UUID, limit int) ([]domain.Review, error) {
	rows, err := r.q.Query(ctx, `
		SELECT id, booking_id, author_id, subject_id, rating, comment, created_at
		FROM reviews WHERE subject_id = $1 ORDER BY created_at DESC LIMIT $2`, subjectID, limit)
	if err != nil {
		return nil, mapErr(err)
	}
	defer rows.Close()
	var out []domain.Review
	for rows.Next() {
		var rv domain.Review
		if err := rows.Scan(&rv.ID, &rv.BookingID, &rv.AuthorID, &rv.SubjectID, &rv.Rating, &rv.Comment, &rv.CreatedAt); err != nil {
			return nil, mapErr(err)
		}
		out = append(out, rv)
	}
	return out, mapErr(rows.Err())
}
