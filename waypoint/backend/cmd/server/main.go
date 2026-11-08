// Command server runs the Wanees (ونيس) carpooling API.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/cache"
	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/config"
	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/handler"
	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/repository/postgres"
	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/service"
)

func main() {
	if len(os.Args) > 1 && os.Args[1] == "healthcheck" {
		os.Exit(healthcheck())
	}
	if err := run(); err != nil {
		slog.Error("server exited with error", "err", err)
		os.Exit(1)
	}
}

// healthcheck probes the local /healthz endpoint (for distroless container health checks).
func healthcheck() int {
	addr := os.Getenv("HTTP_ADDR")
	if addr == "" {
		addr = ":8080"
	}
	if strings.HasPrefix(addr, ":") {
		addr = "127.0.0.1" + addr
	}
	client := &http.Client{Timeout: 3 * time.Second}
	resp, err := client.Get("http://" + addr + "/healthz")
	if err != nil {
		return 1
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return 1
	}
	return 0
}

func run() error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}

	level := slog.LevelInfo
	if !cfg.IsProduction() {
		level = slog.LevelDebug
	}
	log := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: level}))
	slog.SetDefault(log)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	bootCtx, cancelBoot := context.WithTimeout(ctx, 90*time.Second)
	defer cancelBoot()

	pool, err := postgres.Connect(bootCtx, cfg.DatabaseURL, cfg.DBMaxConns)
	if err != nil {
		return err
	}
	defer pool.Close()
	if cfg.AutoMigrate {
		if err := postgres.Migrate(bootCtx, pool); err != nil {
			return err
		}
		log.Info("database migrations applied")
	}
	store := postgres.NewStore(pool)

	rc, err := cache.Connect(bootCtx, cfg.RedisURL)
	if err != nil {
		return err
	}
	defer rc.Close()

	routing, err := service.NewRoutingClient(cfg.RoutingProvider, cfg.RoutingBaseURL, cfg.RoutingTimeout)
	if err != nil {
		return err
	}

	var gateway service.PaymentGateway
	switch cfg.PaymentProvider {
	case "stripe":
		gateway = service.NewStripeGateway(cfg.StripeSecretKey, cfg.StripePublishableKey)
	case "cash":
		gateway = service.NewCashGateway()
		log.Info("cash settlement: passengers pay drivers in person")
	default:
		gateway = service.NewSimulatedGateway()
		log.Warn("using SIMULATED payment gateway - no real money moves")
	}

	pricing := service.Pricing{
		Currency:      cfg.DefaultCurrency,
		StepMinor:     cfg.PriceStepMinor,
		MinSeatMinor:  cfg.MinSeatPriceMinor,
		FeeBps:        cfg.PlatformFeeBps,
		FeeFixedCents: cfg.PlatformFeeFixedCents,
		CentsPerKm:    cfg.PriceCentsPerKm,
	}
	escrow := service.NewEscrowService(store, gateway, log)
	authSvc := service.NewAuthService(store, rc, cfg.JWTSecret, cfg.AccessTokenTTL, cfg.RefreshTokenTTL)
	userSvc := service.NewUserService(store, escrow, cfg.StripeConnectRefresh, cfg.StripeConnectReturn)
	bookingSvc := service.NewBookingService(store, rc, escrow, pricing, service.CancellationPolicy{
		LateWindow:  cfg.LateCancelWindow,
		LatePercent: cfg.LateCancelPercent,
	}, log)
	rideSvc := service.NewRideService(store, rc, routing, pricing, escrow, bookingSvc, log)
	matcher := service.NewSpatialMatcher(store, rc, pricing, log)
	geocoder := service.NewGeocoder(cfg.GeocoderBaseURL, cfg.GeocoderUserAgent, cfg.GeocoderBBox, rc, log)

	webhookSecret := ""
	if cfg.PaymentProvider == "stripe" {
		webhookSecret = cfg.StripeWebhookSecret
	}
	router := handler.NewRouter(handler.RouterDeps{
		Log:            log,
		Store:          store,
		Cache:          rc,
		Auth:           authSvc,
		Users:          userSvc,
		Rides:          rideSvc,
		Bookings:       bookingSvc,
		Escrow:         escrow,
		Matcher:        matcher,
		Geocoder:       geocoder,
		WebhookSecret:  webhookSecret,
		AllowedOrigins: cfg.AllowedOrigins,
		TrustProxy:     cfg.TrustProxy,
		RatePerMinute:  cfg.RateLimitPerMinute,
		Market: handler.MarketConfig{
			Country:         cfg.Country,
			Currency:        cfg.DefaultCurrency,
			Timezone:        cfg.DefaultTimezone,
			PaymentProvider: gateway.Name(),
			PayoutsRequired: escrow.RequiresConnectedAccount(),
			PriceStepMinor:  cfg.PriceStepMinor,
			ServiceFeeBps:   cfg.PlatformFeeBps,
		},
	})

	worker := service.NewWorker(service.WorkerConfig{
		Interval:           cfg.WorkerInterval,
		PaymentPendingTTL:  cfg.PaymentPendingTTL,
		RequestResponseTTL: cfg.RequestResponseTTL,
		AutoCompleteAfter:  cfg.AutoCompleteAfter,
	}, store, rc, rideSvc, bookingSvc, log)
	workerDone := make(chan struct{})
	go func() {
		defer close(workerDone)
		worker.Run(ctx)
	}()

	srv := &http.Server{
		Addr:              cfg.HTTPAddr,
		Handler:           router,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      35 * time.Second,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    1 << 20,
	}
	errCh := make(chan error, 1)
	go func() {
		log.Info("http server listening", "addr", cfg.HTTPAddr, "env", cfg.Env,
			"routing", cfg.RoutingProvider, "payments", gateway.Name())
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
		close(errCh)
	}()

	select {
	case <-ctx.Done():
		log.Info("shutdown signal received")
	case err := <-errCh:
		if err != nil {
			return err
		}
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		log.Error("graceful shutdown failed", "err", err)
	}
	stop()
	<-workerDone
	log.Info("server stopped")
	return nil
}
