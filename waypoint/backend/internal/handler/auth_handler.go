package handler

import (
	"log/slog"
	"net/http"

	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/domain"
	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/service"
)

// AuthHandler serves authentication and account endpoints.
type AuthHandler struct {
	auth  *service.AuthService
	users *service.UserService
	log   *slog.Logger
}

// NewAuthHandler builds the handler.
func NewAuthHandler(auth *service.AuthService, users *service.UserService, log *slog.Logger) *AuthHandler {
	return &AuthHandler{auth: auth, users: users, log: log}
}

// Register handles POST /v1/auth/register.
func (h *AuthHandler) Register(w http.ResponseWriter, r *http.Request) {
	var in domain.RegisterInput
	if err := decode(w, r, &in); err != nil {
		respondError(w, r, h.log, err)
		return
	}
	res, err := h.auth.Register(r.Context(), in)
	if err != nil {
		respondError(w, r, h.log, err)
		return
	}
	writeJSON(w, http.StatusCreated, res)
}

type loginRequest struct {
	Email    string `json:"email"`
	Password string `json:"password"`
}

// Login handles POST /v1/auth/login.
func (h *AuthHandler) Login(w http.ResponseWriter, r *http.Request) {
	var in loginRequest
	if err := decode(w, r, &in); err != nil {
		respondError(w, r, h.log, err)
		return
	}
	if in.Email == "" || in.Password == "" {
		respondError(w, r, h.log, domain.NewValidationError("email", "email and password are required"))
		return
	}
	res, err := h.auth.Login(r.Context(), in.Email, in.Password)
	if err != nil {
		respondError(w, r, h.log, err)
		return
	}
	writeJSON(w, http.StatusOK, res)
}

type refreshRequest struct {
	RefreshToken string `json:"refresh_token"`
}

// Refresh handles POST /v1/auth/refresh.
func (h *AuthHandler) Refresh(w http.ResponseWriter, r *http.Request) {
	var in refreshRequest
	if err := decode(w, r, &in); err != nil {
		respondError(w, r, h.log, err)
		return
	}
	res, err := h.auth.Refresh(r.Context(), in.RefreshToken)
	if err != nil {
		respondError(w, r, h.log, err)
		return
	}
	writeJSON(w, http.StatusOK, res)
}

type logoutRequest struct {
	RefreshToken string `json:"refresh_token"`
	AllDevices   bool   `json:"all_devices"`
}

// Logout handles POST /v1/auth/logout.
func (h *AuthHandler) Logout(w http.ResponseWriter, r *http.Request) {
	var in logoutRequest
	if err := decode(w, r, &in); err != nil {
		respondError(w, r, h.log, err)
		return
	}
	if err := h.auth.Logout(r.Context(), mustUser(r), in.RefreshToken, in.AllDevices); err != nil {
		respondError(w, r, h.log, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// Me handles GET /v1/me.
func (h *AuthHandler) Me(w http.ResponseWriter, r *http.Request) {
	me, err := h.users.Me(r.Context(), mustUser(r))
	if err != nil {
		respondError(w, r, h.log, err)
		return
	}
	writeJSON(w, http.StatusOK, me)
}

// UpdateMe handles PATCH /v1/me.
func (h *AuthHandler) UpdateMe(w http.ResponseWriter, r *http.Request) {
	var in domain.UpdateProfileInput
	if err := decode(w, r, &in); err != nil {
		respondError(w, r, h.log, err)
		return
	}
	me, err := h.users.UpdateProfile(r.Context(), mustUser(r), in)
	if err != nil {
		respondError(w, r, h.log, err)
		return
	}
	writeJSON(w, http.StatusOK, me)
}

// PublicProfile handles GET /v1/users/{userID}.
func (h *AuthHandler) PublicProfile(w http.ResponseWriter, r *http.Request) {
	id, err := pathUUID(r, "userID")
	if err != nil {
		respondError(w, r, h.log, err)
		return
	}
	p, err := h.users.PublicProfile(r.Context(), id)
	if err != nil {
		respondError(w, r, h.log, err)
		return
	}
	writeJSON(w, http.StatusOK, p)
}

type onboardRequest struct {
	Country string `json:"country"`
}

// StartPayouts handles POST /v1/me/payouts/onboarding.
func (h *AuthHandler) StartPayouts(w http.ResponseWriter, r *http.Request) {
	var in onboardRequest
	if r.ContentLength != 0 {
		if err := decode(w, r, &in); err != nil {
			respondError(w, r, h.log, err)
			return
		}
	}
	res, err := h.users.StartPayoutOnboarding(r.Context(), mustUser(r), in.Country)
	if err != nil {
		respondError(w, r, h.log, err)
		return
	}
	writeJSON(w, http.StatusOK, res)
}

// RefreshPayouts handles POST /v1/me/payouts/refresh.
func (h *AuthHandler) RefreshPayouts(w http.ResponseWriter, r *http.Request) {
	me, err := h.users.RefreshPayoutStatus(r.Context(), mustUser(r))
	if err != nil {
		respondError(w, r, h.log, err)
		return
	}
	writeJSON(w, http.StatusOK, me)
}
