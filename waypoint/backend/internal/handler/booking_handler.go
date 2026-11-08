package handler

import (
	"context"
	"log/slog"
	"net/http"

	"github.com/google/uuid"

	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/domain"
	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/service"
)

// BookingHandler serves reservation and escrow endpoints.
type BookingHandler struct {
	bookings *service.BookingService
	log      *slog.Logger
}

// NewBookingHandler builds the handler.
func NewBookingHandler(bookings *service.BookingService, log *slog.Logger) *BookingHandler {
	return &BookingHandler{bookings: bookings, log: log}
}

// Create handles POST /v1/rides/{rideID}/bookings.
func (h *BookingHandler) Create(w http.ResponseWriter, r *http.Request) {
	rideID, err := pathUUID(r, "rideID")
	if err != nil {
		respondError(w, r, h.log, err)
		return
	}
	var in domain.CreateBookingInput
	if err := decode(w, r, &in); err != nil {
		respondError(w, r, h.log, err)
		return
	}
	b, err := h.bookings.Create(r.Context(), mustUser(r), rideID, in)
	if err != nil {
		respondError(w, r, h.log, err)
		return
	}
	writeJSON(w, http.StatusCreated, b)
}

// ListMine handles GET /v1/me/bookings.
func (h *BookingHandler) ListMine(w http.ResponseWriter, r *http.Request) {
	out, err := h.bookings.ListMine(r.Context(), mustUser(r))
	if err != nil {
		respondError(w, r, h.log, err)
		return
	}
	writeJSON(w, http.StatusOK, list(out))
}

// ListForRide handles GET /v1/rides/{rideID}/bookings (driver).
func (h *BookingHandler) ListForRide(w http.ResponseWriter, r *http.Request) {
	rideID, err := pathUUID(r, "rideID")
	if err != nil {
		respondError(w, r, h.log, err)
		return
	}
	out, err := h.bookings.ListForRide(r.Context(), mustUser(r), rideID)
	if err != nil {
		respondError(w, r, h.log, err)
		return
	}
	writeJSON(w, http.StatusOK, list(out))
}

// Get handles GET /v1/bookings/{bookingID}.
func (h *BookingHandler) Get(w http.ResponseWriter, r *http.Request) {
	h.action(w, r, h.bookings.Get)
}

type bookingAction func(ctx context.Context, userID, bookingID uuid.UUID) (*domain.BookingView, error)

func (h *BookingHandler) action(w http.ResponseWriter, r *http.Request, fn bookingAction) {
	id, err := pathUUID(r, "bookingID")
	if err != nil {
		respondError(w, r, h.log, err)
		return
	}
	b, err := fn(r.Context(), mustUser(r), id)
	if err != nil {
		respondError(w, r, h.log, err)
		return
	}
	writeJSON(w, http.StatusOK, b)
}

// Accept handles POST /v1/bookings/{bookingID}/accept (driver).
func (h *BookingHandler) Accept(w http.ResponseWriter, r *http.Request) {
	h.action(w, r, h.bookings.Accept)
}

// Reject handles POST /v1/bookings/{bookingID}/reject (driver).
func (h *BookingHandler) Reject(w http.ResponseWriter, r *http.Request) {
	h.action(w, r, h.bookings.Reject)
}

// DriverCancel handles POST /v1/bookings/{bookingID}/driver-cancel (driver).
func (h *BookingHandler) DriverCancel(w http.ResponseWriter, r *http.Request) {
	h.action(w, r, h.bookings.CancelByDriver)
}

// Cancel handles POST /v1/bookings/{bookingID}/cancel (passenger).
func (h *BookingHandler) Cancel(w http.ResponseWriter, r *http.Request) {
	h.action(w, r, h.bookings.CancelByPassenger)
}

// ConfirmTrip handles POST /v1/bookings/{bookingID}/confirm-trip (passenger).
func (h *BookingHandler) ConfirmTrip(w http.ResponseWriter, r *http.Request) {
	h.action(w, r, h.bookings.ConfirmTrip)
}

// SyncPayment handles POST /v1/bookings/{bookingID}/payment/sync (passenger).
func (h *BookingHandler) SyncPayment(w http.ResponseWriter, r *http.Request) {
	h.action(w, r, h.bookings.SyncPayment)
}

// Review handles POST /v1/bookings/{bookingID}/review.
func (h *BookingHandler) Review(w http.ResponseWriter, r *http.Request) {
	id, err := pathUUID(r, "bookingID")
	if err != nil {
		respondError(w, r, h.log, err)
		return
	}
	var in domain.CreateReviewInput
	if err := decode(w, r, &in); err != nil {
		respondError(w, r, h.log, err)
		return
	}
	rv, err := h.bookings.Review(r.Context(), mustUser(r), id, in)
	if err != nil {
		respondError(w, r, h.log, err)
		return
	}
	writeJSON(w, http.StatusCreated, rv)
}
