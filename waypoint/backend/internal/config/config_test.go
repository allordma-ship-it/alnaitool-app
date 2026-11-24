package config

import (
	"strings"
	"testing"
)

func validConfig() *Config {
	return &Config{
		DatabaseURL:       "postgres://localhost/wanees",
		JWTSecret:         strings.Repeat("ab", 32),
		RoutingProvider:   "osrm",
		PaymentProvider:   "cash",
		DefaultTimezone:   "Asia/Damascus",
		DefaultCurrency:   "SYP",
		PriceStepMinor:    5000,
		MinSeatPriceMinor: 10000,
		PriceCentsPerKm:   250,
		LateCancelPercent: 50,
	}
}

func TestValidateAcceptsRandomSecret(t *testing.T) {
	if err := validConfig().validate(); err != nil {
		t.Fatalf("valid config rejected: %v", err)
	}
}

func TestValidateRejectsPlaceholderSecret(t *testing.T) {
	c := validConfig()
	c.JWTSecret = "change-me-to-a-random-64-character-hex-string-0000000000000000" // the value in .env.example
	err := c.validate()
	if err == nil || !strings.Contains(err.Error(), "placeholder") {
		t.Fatalf("placeholder JWT_SECRET accepted, err = %v", err)
	}
}

func TestValidateRejectsShortSecret(t *testing.T) {
	c := validConfig()
	c.JWTSecret = "short"
	if err := c.validate(); err == nil || !strings.Contains(err.Error(), "32 characters") {
		t.Fatalf("short JWT_SECRET accepted, err = %v", err)
	}
}
