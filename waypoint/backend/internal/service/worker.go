package service

import (
	"context"
	"log/slog"
	"time"

	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/cache"
	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/repository/postgres"
)

// WorkerConfig tunes the background sweeper.
type WorkerConfig struct {
	Interval           time.Duration
	PaymentPendingTTL  time.Duration
	RequestResponseTTL time.Duration
	AutoCompleteAfter  time.Duration
	HoldRenewAfter     time.Duration
}

// Worker runs periodic escrow and booking maintenance. A Redis lock ensures a
// single replica performs each sweep.
type Worker struct {
	cfg      WorkerConfig
	store    *postgres.Store
	cache    *cache.Cache
	rides    *RideService
	bookings *BookingService
	log      *slog.Logger
}

// NewWorker builds the worker.
func NewWorker(cfg WorkerConfig, store *postgres.Store, c *cache.Cache, rides *RideService, bookings *BookingService, log *slog.Logger) *Worker {
	if cfg.HoldRenewAfter == 0 {
		cfg.HoldRenewAfter = 6 * 24 * time.Hour
	}
	return &Worker{cfg: cfg, store: store, cache: c, rides: rides, bookings: bookings, log: log}
}

// Run blocks until ctx is cancelled.
func (w *Worker) Run(ctx context.Context) {
	t := time.NewTicker(w.cfg.Interval)
	defer t.Stop()
	w.log.Info("background worker started", "interval", w.cfg.Interval)
	for {
		w.sweep(ctx)
		select {
		case <-ctx.Done():
			w.log.Info("background worker stopped")
			return
		case <-t.C:
		}
	}
}

func (w *Worker) sweep(ctx context.Context) {
	lock, err := w.cache.TryLock(ctx, "worker:sweep", w.cfg.Interval*3)
	if err != nil {
		w.log.Warn("worker lock unavailable", "err", err)
		return
	}
	if lock == nil {
		return // another replica is sweeping
	}
	defer func() { _ = lock.Release(context.WithoutCancel(ctx)) }()

	sctx, cancel := context.WithTimeout(ctx, w.cfg.Interval*2)
	defer cancel()

	if n, err := w.bookings.ExpireUnpaid(sctx, w.cfg.PaymentPendingTTL); err != nil {
		w.log.Error("expire unpaid bookings", "err", err)
	} else if n > 0 {
		w.log.Info("expired unpaid bookings", "count", n)
	}
	if n, err := w.bookings.ExpireUnanswered(sctx, w.cfg.RequestResponseTTL); err != nil {
		w.log.Error("expire unanswered requests", "err", err)
	} else if n > 0 {
		w.log.Info("expired unanswered requests", "count", n)
	}
	if n, err := w.bookings.RenewAgeingHolds(sctx, w.cfg.HoldRenewAfter); err != nil {
		w.log.Error("renew ageing holds", "err", err)
	} else if n > 0 {
		w.log.Info("renewed escrow holds", "count", n)
	}

	ids, err := w.store.Rides.ListDueForCompletion(sctx, w.cfg.AutoCompleteAfter, 50)
	if err != nil {
		w.log.Error("list rides due for completion", "err", err)
		return
	}
	for _, id := range ids {
		if err := w.rides.AutoComplete(sctx, id); err != nil {
			level := slog.LevelError
			if IsRetryable(err) {
				level = slog.LevelWarn
			}
			w.log.Log(sctx, level, "auto-complete ride", "ride", id, "err", err)
		} else {
			w.log.Info("auto-completed ride and released escrow", "ride", id)
		}
	}
}
