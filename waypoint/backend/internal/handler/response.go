// Package handler exposes the HTTP API.
package handler

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strings"

	"github.com/go-chi/chi/v5"
	chimw "github.com/go-chi/chi/v5/middleware"
	"github.com/google/uuid"

	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/domain"
	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/middleware"
)

const maxBodyBytes = 1 << 20

type errorBody struct {
	Code      string `json:"code"`
	Message   string `json:"message"`
	Field     string `json:"field,omitempty"`
	RequestID string `json:"request_id,omitempty"`
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	if v != nil {
		_ = json.NewEncoder(w).Encode(v)
	}
}

func writeErr(w http.ResponseWriter, r *http.Request, status int, code, msg, field string) {
	writeJSON(w, status, map[string]errorBody{"error": {
		Code: code, Message: msg, Field: field, RequestID: chimw.GetReqID(r.Context()),
	}})
}

// respondError maps domain errors onto HTTP responses; unknown errors become 500s.
func respondError(w http.ResponseWriter, r *http.Request, log *slog.Logger, err error) {
	var ve *domain.ValidationError
	switch {
	case errors.As(err, &ve):
		writeErr(w, r, http.StatusUnprocessableEntity, "validation_failed", ve.Message, ve.Field)
	case errors.Is(err, domain.ErrNotFound):
		writeErr(w, r, http.StatusNotFound, "not_found", "resource not found", "")
	case errors.Is(err, domain.ErrUnauthorized):
		writeErr(w, r, http.StatusUnauthorized, "unauthorized", "invalid credentials", "")
	case errors.Is(err, domain.ErrForbidden):
		writeErr(w, r, http.StatusForbidden, "forbidden", "you are not allowed to do this", "")
	case errors.Is(err, domain.ErrNoSeats):
		writeErr(w, r, http.StatusConflict, "no_seats", err.Error(), "seats")
	case errors.Is(err, domain.ErrConflict):
		writeErr(w, r, http.StatusConflict, "conflict", cleanMsg(err), "")
	case errors.Is(err, domain.ErrInvalidTransition):
		writeErr(w, r, http.StatusConflict, "invalid_state", cleanMsg(err), "")
	case errors.Is(err, domain.ErrPaymentRequired):
		writeErr(w, r, http.StatusPaymentRequired, "payment_required", cleanMsg(err), "")
	case errors.Is(err, domain.ErrUpstream):
		log.Error("upstream failure", "err", err, "path", r.URL.Path, "request_id", chimw.GetReqID(r.Context()))
		writeErr(w, r, http.StatusBadGateway, "upstream_unavailable", "a dependent service is unavailable, please retry", "")
	default:
		log.Error("unhandled error", "err", err, "path", r.URL.Path, "request_id", chimw.GetReqID(r.Context()))
		writeErr(w, r, http.StatusInternalServerError, "internal", "internal server error", "")
	}
}

func cleanMsg(err error) string {
	msg := err.Error()
	if i := strings.LastIndex(msg, ": "); i >= 0 && i+2 < len(msg) {
		return msg[i+2:]
	}
	return msg
}

// decode reads a JSON body strictly (unknown fields rejected, size limited).
func decode(w http.ResponseWriter, r *http.Request, dst any) error {
	r.Body = http.MaxBytesReader(w, r.Body, maxBodyBytes)
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(dst); err != nil {
		var mbe *http.MaxBytesError
		var ute *json.UnmarshalTypeError
		switch {
		case errors.Is(err, io.EOF):
			return domain.NewValidationError("body", "request body is required")
		case errors.As(err, &mbe):
			return domain.NewValidationError("body", "request body too large")
		case errors.As(err, &ute):
			return domain.NewValidationError(ute.Field, fmt.Sprintf("must be of type %s", ute.Type))
		case strings.HasPrefix(err.Error(), "json: unknown field"):
			return domain.NewValidationError(strings.Trim(strings.TrimPrefix(err.Error(), "json: unknown field "), `"`), "unknown field")
		default:
			return domain.NewValidationError("body", "malformed JSON")
		}
	}
	if dec.More() {
		return domain.NewValidationError("body", "must contain a single JSON object")
	}
	return nil
}

func pathUUID(r *http.Request, name string) (uuid.UUID, error) {
	id, err := uuid.Parse(chi.URLParam(r, name))
	if err != nil {
		return uuid.Nil, domain.NewValidationError(name, "must be a valid UUID")
	}
	return id, nil
}

// mustUser returns the authenticated user id (routes are guarded by RequireAuth).
func mustUser(r *http.Request) uuid.UUID {
	id, _ := middleware.UserID(r.Context())
	return id
}

type listResponse[T any] struct {
	Data []T `json:"data"`
}

func list[T any](items []T) listResponse[T] {
	if items == nil {
		items = []T{}
	}
	return listResponse[T]{Data: items}
}
