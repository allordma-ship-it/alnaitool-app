package domain

import (
	"errors"
	"fmt"
)

// Sentinel errors shared across layers. Handlers map them to HTTP status codes.
var (
	ErrNotFound          = errors.New("resource not found")
	ErrConflict          = errors.New("resource conflict")
	ErrForbidden         = errors.New("forbidden")
	ErrUnauthorized      = errors.New("unauthorized")
	ErrInvalidTransition = errors.New("invalid state transition")
	ErrNoSeats           = errors.New("not enough seats available on this segment")
	ErrPaymentRequired   = errors.New("payment setup required")
	ErrUpstream          = errors.New("upstream service unavailable")
)

// ValidationError describes a client input problem on a specific field.
type ValidationError struct {
	Field   string `json:"field"`
	Message string `json:"message"`
}

func (e *ValidationError) Error() string {
	return fmt.Sprintf("%s: %s", e.Field, e.Message)
}

// NewValidationError builds a ValidationError.
func NewValidationError(field, msg string) error {
	return &ValidationError{Field: field, Message: msg}
}
