package handler

import (
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"time"

	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/repository/postgres"
	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/service"
)

// WebhookHandler receives Stripe events.
type WebhookHandler struct {
	secret   string
	store    *postgres.Store
	bookings *service.BookingService
	escrow   *service.EscrowService
	users    *service.UserService
	log      *slog.Logger
}

// NewWebhookHandler builds the handler.
func NewWebhookHandler(secret string, store *postgres.Store, bookings *service.BookingService,
	escrow *service.EscrowService, users *service.UserService, log *slog.Logger) *WebhookHandler {
	return &WebhookHandler{secret: secret, store: store, bookings: bookings, escrow: escrow, users: users, log: log}
}

// Stripe handles POST /v1/webhooks/stripe.
func (h *WebhookHandler) Stripe(w http.ResponseWriter, r *http.Request) {
	if h.secret == "" {
		writeErr(w, r, http.StatusNotFound, "not_found", "webhooks are disabled", "")
		return
	}
	payload, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 1<<20))
	if err != nil {
		writeErr(w, r, http.StatusBadRequest, "bad_request", "cannot read body", "")
		return
	}
	ev, err := service.VerifyStripeSignature(payload, r.Header.Get("Stripe-Signature"), h.secret, 5*time.Minute, time.Now())
	if err != nil {
		h.log.Warn("rejected stripe webhook", "err", err)
		writeErr(w, r, http.StatusBadRequest, "invalid_signature", "signature verification failed", "")
		return
	}

	ctx := r.Context()
	fresh, err := h.store.MarkStripeEventProcessed(ctx, ev.ID, ev.Type)
	if err != nil {
		respondError(w, r, h.log, err)
		return
	}
	if !fresh {
		writeJSON(w, http.StatusOK, map[string]string{"status": "duplicate"})
		return
	}

	if err := h.dispatch(r, ev); err != nil {
		// Allow Stripe to redeliver.
		if uerr := h.store.UnmarkStripeEvent(ctx, ev.ID); uerr != nil {
			h.log.Error("unmark stripe event", "event", ev.ID, "err", uerr)
		}
		h.log.Error("stripe webhook processing failed", "event", ev.ID, "type", ev.Type, "err", err)
		writeErr(w, r, http.StatusInternalServerError, "processing_failed", "event processing failed", "")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "processed"})
}

func (h *WebhookHandler) dispatch(r *http.Request, ev *service.StripeEvent) error {
	ctx := r.Context()
	switch ev.Type {
	case "payment_intent.amount_capturable_updated",
		"payment_intent.succeeded",
		"payment_intent.canceled",
		"payment_intent.payment_failed",
		"payment_intent.requires_action",
		"payment_intent.processing":
		in, err := service.ParseIntentObject(ev.Data.Object)
		if err != nil {
			return err
		}
		return h.bookings.HandleIntentUpdate(ctx, in)

	case "charge.refunded":
		var ch struct {
			PaymentIntent  string `json:"payment_intent"`
			AmountRefunded int    `json:"amount_refunded"`
		}
		if err := json.Unmarshal(ev.Data.Object, &ch); err != nil {
			return err
		}
		if ch.PaymentIntent == "" {
			return nil
		}
		return h.store.WithTx(ctx, func(tx *postgres.Store) error {
			err := h.escrow.ApplyRefund(ctx, tx, ch.PaymentIntent, ch.AmountRefunded)
			if err != nil && isNotFound(err) {
				return nil
			}
			return err
		})

	case "account.updated":
		var acct struct {
			ID             string `json:"id"`
			PayoutsEnabled bool   `json:"payouts_enabled"`
			ChargesEnabled bool   `json:"charges_enabled"`
		}
		if err := json.Unmarshal(ev.Data.Object, &acct); err != nil {
			return err
		}
		err := h.users.ApplyAccountUpdate(ctx, acct.ID, acct.PayoutsEnabled, acct.ChargesEnabled)
		if err != nil && isNotFound(err) {
			return nil
		}
		return err

	default:
		h.log.Debug("ignored stripe event", "type", ev.Type)
		return nil
	}
}
