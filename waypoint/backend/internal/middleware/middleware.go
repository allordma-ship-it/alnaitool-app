// Package middleware provides HTTP middleware: auth, rate limiting, logging and CORS.
package middleware

import (
	"context"
	"encoding/json"
	"log/slog"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	chimw "github.com/go-chi/chi/v5/middleware"
	"github.com/google/uuid"

	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/cache"
)

type ctxKey int

const userIDKey ctxKey = iota

// Authenticator validates access tokens.
type Authenticator interface {
	Authenticate(token string) (uuid.UUID, error)
}

// UserID returns the authenticated user id from the request context.
func UserID(ctx context.Context) (uuid.UUID, bool) {
	id, ok := ctx.Value(userIDKey).(uuid.UUID)
	return id, ok && id != uuid.Nil
}

func bearer(r *http.Request) string {
	h := r.Header.Get("Authorization")
	if len(h) > 7 && strings.EqualFold(h[:7], "bearer ") {
		return strings.TrimSpace(h[7:])
	}
	return ""
}

func writeError(w http.ResponseWriter, status int, code, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{"error": map[string]string{"code": code, "message": msg}})
}

// RequireAuth rejects requests without a valid access token.
func RequireAuth(a Authenticator) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			tok := bearer(r)
			if tok == "" {
				writeError(w, http.StatusUnauthorized, "unauthorized", "missing bearer token")
				return
			}
			id, err := a.Authenticate(tok)
			if err != nil {
				w.Header().Set("WWW-Authenticate", `Bearer error="invalid_token"`)
				writeError(w, http.StatusUnauthorized, "unauthorized", "invalid or expired token")
				return
			}
			next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), userIDKey, id)))
		})
	}
}

// OptionalAuth attaches the user id when a valid token is present.
func OptionalAuth(a Authenticator) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if tok := bearer(r); tok != "" {
				if id, err := a.Authenticate(tok); err == nil {
					r = r.WithContext(context.WithValue(r.Context(), userIDKey, id))
				}
			}
			next.ServeHTTP(w, r)
		})
	}
}

// RateLimit applies a per-client sliding window backed by Redis. Authenticated
// callers are keyed by user id, anonymous ones by IP. Fails open if Redis is down.
func RateLimit(c *cache.Cache, scope string, limit int, window time.Duration, log *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			key := scope + ":ip:" + clientIP(r)
			if id, ok := UserID(r.Context()); ok {
				key = scope + ":u:" + id.String()
			}
			res, err := c.Allow(r.Context(), key, limit, window)
			if err != nil {
				log.Warn("rate limiter unavailable", "err", err)
				next.ServeHTTP(w, r)
				return
			}
			w.Header().Set("X-RateLimit-Limit", strconv.Itoa(limit))
			remaining := int64(limit) - res.Count
			if remaining < 0 {
				remaining = 0
			}
			w.Header().Set("X-RateLimit-Remaining", strconv.FormatInt(remaining, 10))
			if !res.Allowed {
				secs := int(res.RetryAfter.Seconds()) + 1
				w.Header().Set("Retry-After", strconv.Itoa(secs))
				writeError(w, http.StatusTooManyRequests, "rate_limited", "too many requests, slow down")
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

func clientIP(r *http.Request) string {
	// chi's RealIP middleware has already rewritten RemoteAddr from trusted headers.
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

// Logger emits one structured log line per request.
func Logger(log *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			start := time.Now()
			ww := chimw.NewWrapResponseWriter(w, r.ProtoMajor)
			next.ServeHTTP(ww, r)
			status := ww.Status()
			if status == 0 {
				status = http.StatusOK
			}
			attrs := []any{
				"method", r.Method,
				"path", r.URL.Path,
				"status", status,
				"bytes", ww.BytesWritten(),
				"duration_ms", time.Since(start).Milliseconds(),
				"request_id", chimw.GetReqID(r.Context()),
				"ip", clientIP(r),
			}
			if id, ok := UserID(r.Context()); ok {
				attrs = append(attrs, "user_id", id.String())
			}
			switch {
			case status >= 500:
				log.Error("http request", attrs...)
			case status >= 400:
				log.Warn("http request", attrs...)
			default:
				log.Info("http request", attrs...)
			}
		})
	}
}

// CORS adds cross-origin headers for the configured origins ("*" allows all).
func CORS(allowed []string) func(http.Handler) http.Handler {
	allowAll := false
	set := make(map[string]bool, len(allowed))
	for _, o := range allowed {
		if o == "*" {
			allowAll = true
		}
		set[strings.TrimRight(o, "/")] = true
	}
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			origin := r.Header.Get("Origin")
			if origin != "" && (allowAll || set[origin]) {
				w.Header().Set("Access-Control-Allow-Origin", origin)
				w.Header().Add("Vary", "Origin")
				w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PATCH, PUT, DELETE, OPTIONS")
				w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Request-Id")
				w.Header().Set("Access-Control-Expose-Headers", "X-Request-Id, Retry-After, X-RateLimit-Remaining")
				w.Header().Set("Access-Control-Max-Age", "600")
			}
			if r.Method == http.MethodOptions && r.Header.Get("Access-Control-Request-Method") != "" {
				w.WriteHeader(http.StatusNoContent)
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

// SecurityHeaders sets conservative response headers for a JSON API.
func SecurityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("Cache-Control", "no-store")
		next.ServeHTTP(w, r)
	})
}
