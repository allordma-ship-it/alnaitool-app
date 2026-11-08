package handler

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	chimw "github.com/go-chi/chi/v5/middleware"

	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/cache"
	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/domain"
	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/middleware"
	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/repository/postgres"
	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/service"
)

// MarketConfig is the public, per-deployment market configuration served at
// GET /v1/config so clients never hard-code currency or payment behaviour.
type MarketConfig struct {
	Country         string `json:"country"`
	Currency        string `json:"currency"`
	Timezone        string `json:"timezone"`
	PaymentProvider string `json:"payment_provider"`
	PayoutsRequired bool   `json:"payouts_required"`
	PriceStepMinor  int    `json:"price_step_minor"`
	ServiceFeeBps   int    `json:"service_fee_bps"`
}

// RouterDeps are the dependencies needed to mount the API.
type RouterDeps struct {
	Log            *slog.Logger
	Store          *postgres.Store
	Cache          *cache.Cache
	Auth           *service.AuthService
	Users          *service.UserService
	Rides          *service.RideService
	Bookings       *service.BookingService
	Escrow         *service.EscrowService
	Matcher        *service.SpatialMatcher
	Geocoder       *service.Geocoder
	WebhookSecret  string
	AllowedOrigins []string
	TrustProxy     bool
	RatePerMinute  int
	Market         MarketConfig
}

func isNotFound(err error) bool { return errors.Is(err, domain.ErrNotFound) }

// NewRouter wires every route and middleware.
func NewRouter(d RouterDeps) http.Handler {
	r := chi.NewRouter()
	r.Use(chimw.RequestID)
	if d.TrustProxy {
		r.Use(chimw.RealIP)
	}
	r.Use(middleware.Logger(d.Log))
	r.Use(chimw.Recoverer)
	r.Use(middleware.SecurityHeaders)
	r.Use(middleware.CORS(d.AllowedOrigins))
	r.Use(chimw.Timeout(30 * time.Second))

	authH := NewAuthHandler(d.Auth, d.Users, d.Log)
	rideH := NewRideHandler(d.Rides, d.Matcher, d.Geocoder, d.Market.Timezone, d.Log)
	bookH := NewBookingHandler(d.Bookings, d.Log)
	hookH := NewWebhookHandler(d.WebhookSecret, d.Store, d.Bookings, d.Escrow, d.Users, d.Log)

	r.Get("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	})
	r.Get("/readyz", func(w http.ResponseWriter, req *http.Request) {
		ctx, cancel := context.WithTimeout(req.Context(), 2*time.Second)
		defer cancel()
		status := map[string]string{"postgres": "ok", "redis": "ok"}
		code := http.StatusOK
		if err := d.Store.Ping(ctx); err != nil {
			status["postgres"] = err.Error()
			code = http.StatusServiceUnavailable
		}
		if err := d.Cache.Ping(ctx); err != nil {
			status["redis"] = err.Error()
			code = http.StatusServiceUnavailable
		}
		writeJSON(w, code, status)
	})

	limit := func(scope string, perMinute int) func(http.Handler) http.Handler {
		return middleware.RateLimit(d.Cache, scope, perMinute, time.Minute, d.Log)
	}
	requireAuth := middleware.RequireAuth(d.Auth)

	r.Route("/v1", func(v1 chi.Router) {
		v1.Post("/webhooks/stripe", hookH.Stripe)
		v1.Get("/config", func(w http.ResponseWriter, _ *http.Request) {
			writeJSON(w, http.StatusOK, d.Market)
		})

		// Credential endpoints get a tight per-IP limit against brute force.
		v1.Group(func(g chi.Router) {
			g.Use(limit("auth", 10))
			g.Post("/auth/register", authH.Register)
			g.Post("/auth/login", authH.Login)
			g.Post("/auth/refresh", authH.Refresh)
		})

		// Public (optionally authenticated) discovery endpoints.
		v1.Group(func(g chi.Router) {
			g.Use(middleware.OptionalAuth(d.Auth))
			g.Use(limit("public", d.RatePerMinute))
			g.Get("/rides/search", rideH.Search)
			g.Get("/rides/{rideID}", rideH.Get)
			g.Get("/geocode", rideH.Geocode)
			g.Get("/users/{userID}", authH.PublicProfile)
		})

		// Authenticated endpoints.
		v1.Group(func(g chi.Router) {
			g.Use(requireAuth)
			g.Use(limit("api", d.RatePerMinute))

			g.Post("/auth/logout", authH.Logout)
			g.Get("/me", authH.Me)
			g.Patch("/me", authH.UpdateMe)
			g.Post("/me/payouts/onboarding", authH.StartPayouts)
			g.Post("/me/payouts/refresh", authH.RefreshPayouts)
			g.Get("/me/rides", rideH.ListMine)
			g.Get("/me/bookings", bookH.ListMine)

			g.Post("/rides/preview", rideH.Preview)
			g.Post("/rides", rideH.Publish)
			g.Post("/rides/{rideID}/cancel", rideH.Cancel)
			g.Post("/rides/{rideID}/start", rideH.Start)
			g.Post("/rides/{rideID}/complete", rideH.Complete)
			g.Get("/rides/{rideID}/bookings", bookH.ListForRide)
			g.Post("/rides/{rideID}/bookings", bookH.Create)

			g.Get("/bookings/{bookingID}", bookH.Get)
			g.Post("/bookings/{bookingID}/accept", bookH.Accept)
			g.Post("/bookings/{bookingID}/reject", bookH.Reject)
			g.Post("/bookings/{bookingID}/driver-cancel", bookH.DriverCancel)
			g.Post("/bookings/{bookingID}/cancel", bookH.Cancel)
			g.Post("/bookings/{bookingID}/confirm-trip", bookH.ConfirmTrip)
			g.Post("/bookings/{bookingID}/payment/sync", bookH.SyncPayment)
			g.Post("/bookings/{bookingID}/review", bookH.Review)
		})
	})

	r.NotFound(func(w http.ResponseWriter, req *http.Request) {
		writeErr(w, req, http.StatusNotFound, "not_found", "route not found", "")
	})
	r.MethodNotAllowed(func(w http.ResponseWriter, req *http.Request) {
		writeErr(w, req, http.StatusMethodNotAllowed, "method_not_allowed", "method not allowed", "")
	})
	return r
}
