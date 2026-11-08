// Package config loads runtime configuration from environment variables.
package config

import (
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

// Config is the fully resolved application configuration.
type Config struct {
	Env            string
	HTTPAddr       string
	DatabaseURL    string
	DBMaxConns     int32
	RedisURL       string
	AutoMigrate    bool
	AllowedOrigins []string
	TrustProxy     bool

	JWTSecret       string
	AccessTokenTTL  time.Duration
	RefreshTokenTTL time.Duration

	RoutingProvider string // osrm | valhalla
	RoutingBaseURL  string
	RoutingTimeout  time.Duration

	GeocoderBaseURL   string
	GeocoderUserAgent string

	PaymentProvider      string // cash | stripe | simulated
	StripeSecretKey      string
	StripePublishableKey string
	StripeWebhookSecret  string
	StripeConnectReturn  string
	StripeConnectRefresh string

	PlatformFeeBps        int
	PlatformFeeFixedCents int
	PriceCentsPerKm       int
	PriceStepMinor        int
	MinSeatPriceMinor     int

	Country         string
	DefaultCurrency string
	DefaultTimezone string
	GeocoderBBox    string

	RateLimitPerMinute int
	WorkerInterval     time.Duration
	PaymentPendingTTL  time.Duration
	RequestResponseTTL time.Duration
	AutoCompleteAfter  time.Duration
	LateCancelWindow   time.Duration
	LateCancelPercent  int
}

// Load reads configuration from the environment and validates it.
func Load() (*Config, error) {
	c := &Config{
		Env:            getenv("APP_ENV", "development"),
		HTTPAddr:       getenv("HTTP_ADDR", ":8080"),
		DatabaseURL:    os.Getenv("DATABASE_URL"),
		DBMaxConns:     int32(getint("DB_MAX_CONNS", 20)),
		RedisURL:       getenv("REDIS_URL", "redis://localhost:6379/0"),
		AutoMigrate:    getbool("AUTO_MIGRATE", true),
		AllowedOrigins: splitCSV(getenv("CORS_ALLOWED_ORIGINS", "*")),
		TrustProxy:     getbool("TRUST_PROXY", false),

		JWTSecret:       os.Getenv("JWT_SECRET"),
		AccessTokenTTL:  getduration("ACCESS_TOKEN_TTL", 15*time.Minute),
		RefreshTokenTTL: getduration("REFRESH_TOKEN_TTL", 30*24*time.Hour),

		RoutingProvider: strings.ToLower(getenv("ROUTING_PROVIDER", "osrm")),
		RoutingBaseURL:  strings.TrimRight(getenv("ROUTING_BASE_URL", "http://localhost:5000"), "/"),
		RoutingTimeout:  getduration("ROUTING_TIMEOUT", 10*time.Second),

		GeocoderBaseURL:   strings.TrimRight(getenv("GEOCODER_BASE_URL", "https://photon.komoot.io"), "/"),
		GeocoderUserAgent: getenv("GEOCODER_USER_AGENT", "Wanees/1.0 (self-hosted carpooling backend)"),

		PaymentProvider:      strings.ToLower(getenv("PAYMENT_PROVIDER", "cash")),
		StripeSecretKey:      os.Getenv("STRIPE_SECRET_KEY"),
		StripePublishableKey: os.Getenv("STRIPE_PUBLISHABLE_KEY"),
		StripeWebhookSecret:  os.Getenv("STRIPE_WEBHOOK_SECRET"),
		StripeConnectReturn:  getenv("STRIPE_CONNECT_RETURN_URL", "wanees://payouts/return"),
		StripeConnectRefresh: getenv("STRIPE_CONNECT_REFRESH_URL", "wanees://payouts/refresh"),

		PlatformFeeBps:        getint("PLATFORM_FEE_BPS", 0),
		PlatformFeeFixedCents: getint("PLATFORM_FEE_FIXED_CENTS", 0),
		PriceCentsPerKm:       getint("PRICE_CENTS_PER_KM", 250),
		PriceStepMinor:        getint("PRICE_STEP_MINOR", 5000),
		MinSeatPriceMinor:     getint("MIN_SEAT_PRICE_MINOR", 10000),

		Country:         strings.ToUpper(getenv("COUNTRY", "SY")),
		DefaultCurrency: strings.ToUpper(getenv("DEFAULT_CURRENCY", "SYP")),
		DefaultTimezone: getenv("DEFAULT_TIMEZONE", "Asia/Damascus"),
		// minLon,minLat,maxLon,maxLat — Syria by default; empty disables the bias.
		GeocoderBBox: getenv("GEOCODER_BBOX", "35.5,32.3,42.4,37.4"),

		RateLimitPerMinute: getint("RATE_LIMIT_PER_MINUTE", 120),
		WorkerInterval:     getduration("WORKER_INTERVAL", time.Minute),
		PaymentPendingTTL:  getduration("PAYMENT_PENDING_TTL", 30*time.Minute),
		RequestResponseTTL: getduration("REQUEST_RESPONSE_TTL", 24*time.Hour),
		AutoCompleteAfter:  getduration("AUTO_COMPLETE_AFTER", 24*time.Hour),
		LateCancelWindow:   getduration("LATE_CANCEL_WINDOW", 24*time.Hour),
		LateCancelPercent:  getint("LATE_CANCEL_PERCENT", 50),
	}
	return c, c.validate()
}

func (c *Config) validate() error {
	var errs []error
	if c.DatabaseURL == "" {
		errs = append(errs, errors.New("DATABASE_URL is required"))
	}
	if len(c.JWTSecret) < 32 {
		errs = append(errs, errors.New("JWT_SECRET must be at least 32 characters"))
	}
	switch c.RoutingProvider {
	case "osrm", "valhalla":
	default:
		errs = append(errs, fmt.Errorf("ROUTING_PROVIDER must be osrm or valhalla, got %q", c.RoutingProvider))
	}
	switch c.PaymentProvider {
	case "cash":
	case "simulated":
		if c.IsProduction() {
			errs = append(errs, errors.New("PAYMENT_PROVIDER=simulated is not allowed in production"))
		}
	case "stripe":
		if !strings.HasPrefix(c.StripeSecretKey, "sk_") && !strings.HasPrefix(c.StripeSecretKey, "rk_") {
			errs = append(errs, errors.New("STRIPE_SECRET_KEY is required when PAYMENT_PROVIDER=stripe"))
		}
		if c.StripeWebhookSecret == "" {
			errs = append(errs, errors.New("STRIPE_WEBHOOK_SECRET is required when PAYMENT_PROVIDER=stripe"))
		}
	default:
		errs = append(errs, fmt.Errorf("PAYMENT_PROVIDER must be cash, stripe or simulated, got %q", c.PaymentProvider))
	}
	if _, err := time.LoadLocation(c.DefaultTimezone); err != nil {
		errs = append(errs, fmt.Errorf("DEFAULT_TIMEZONE: %w", err))
	}
	if len(c.DefaultCurrency) != 3 {
		errs = append(errs, errors.New("DEFAULT_CURRENCY must be an ISO-4217 code"))
	}
	if c.PriceStepMinor <= 0 || c.MinSeatPriceMinor <= 0 || c.PriceCentsPerKm <= 0 {
		errs = append(errs, errors.New("PRICE_CENTS_PER_KM, PRICE_STEP_MINOR and MIN_SEAT_PRICE_MINOR must be positive"))
	}
	if c.PaymentProvider == "cash" && (c.PlatformFeeBps != 0 || c.PlatformFeeFixedCents != 0) {
		errs = append(errs, errors.New("PLATFORM_FEE_* must be 0 with PAYMENT_PROVIDER=cash (fees cannot be collected in cash)"))
	}
	if c.PlatformFeeBps < 0 || c.PlatformFeeBps > 5000 {
		errs = append(errs, errors.New("PLATFORM_FEE_BPS must be between 0 and 5000"))
	}
	if c.LateCancelPercent < 0 || c.LateCancelPercent > 100 {
		errs = append(errs, errors.New("LATE_CANCEL_PERCENT must be between 0 and 100"))
	}
	return errors.Join(errs...)
}

// IsProduction reports whether the app runs in production mode.
func (c *Config) IsProduction() bool { return c.Env == "production" }

func getenv(key, def string) string {
	if v, ok := os.LookupEnv(key); ok && strings.TrimSpace(v) != "" {
		return strings.TrimSpace(v)
	}
	return def
}

func getint(key string, def int) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(strings.TrimSpace(v)); err == nil {
			return n
		}
	}
	return def
}

func getbool(key string, def bool) bool {
	if v := os.Getenv(key); v != "" {
		if b, err := strconv.ParseBool(strings.TrimSpace(v)); err == nil {
			return b
		}
	}
	return def
}

func getduration(key string, def time.Duration) time.Duration {
	if v := os.Getenv(key); v != "" {
		if d, err := time.ParseDuration(strings.TrimSpace(v)); err == nil {
			return d
		}
	}
	return def
}

func splitCSV(s string) []string {
	parts := strings.Split(s, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}
