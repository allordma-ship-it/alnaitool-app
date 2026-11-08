package service

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/domain"
	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/repository/postgres"
)

// EscrowService drives the payment escrow state machine:
//
//	pending ──authorize──▶ authorized ──capture──▶ captured ──refund──▶ refunded
//	   │                       │
//	   ├──fail──▶ failed ──────┤ (retry authorize)
//	   └──────void─────────────┴──void──▶ voided
//
// Every transition is validated against domain.PaymentStatus.CanTransitionTo,
// persisted under a row lock, and appended to the payment_events audit log.
// Provider calls carry idempotency keys derived from the payment id so retries
// can never double-capture or double-release a hold.
type EscrowService struct {
	store *postgres.Store
	gw    PaymentGateway
	log   *slog.Logger
	now   func() time.Time
}

// NewEscrowService builds the escrow service.
func NewEscrowService(store *postgres.Store, gw PaymentGateway, log *slog.Logger) *EscrowService {
	return &EscrowService{store: store, gw: gw, log: log, now: time.Now}
}

// Provider returns the payment provider name.
func (e *EscrowService) Provider() string { return e.gw.Name() }

// RequiresConnectedAccount reports whether drivers must onboard before publishing.
func (e *EscrowService) RequiresConnectedAccount() bool { return e.gw.Name() == "stripe" }

// Gateway exposes the underlying gateway (for Connect onboarding).
func (e *EscrowService) Gateway() PaymentGateway { return e.gw }

// View builds the passenger-facing payment view.
func (e *EscrowService) View(p *domain.Payment, includeSecret bool) *domain.PaymentView {
	if p == nil {
		return nil
	}
	v := &domain.PaymentView{
		Provider:      p.Provider,
		Status:        p.Status,
		AmountCents:   p.AmountCents,
		CapturedCents: p.CapturedCents,
		RefundedCents: p.RefundedCents,
		Currency:      p.Currency,
	}
	if includeSecret && (p.Status == domain.PaymentPending || p.Status == domain.PaymentFailed) {
		v.ClientSecret = p.ClientSecret
		v.PublishableKey = e.gw.PublishableKey()
	}
	return v
}

// transition validates and applies a state change with timestamps.
func (e *EscrowService) transition(p *domain.Payment, to domain.PaymentStatus) error {
	if p.Status == to {
		return nil
	}
	if !p.Status.CanTransitionTo(to) {
		return fmt.Errorf("%w: payment %s -> %s", domain.ErrInvalidTransition, p.Status, to)
	}
	now := e.now()
	switch to {
	case domain.PaymentAuthorized:
		p.AuthorizedAt = &now
		p.LastError = ""
	case domain.PaymentCaptured:
		p.CapturedAt = &now
	case domain.PaymentVoided:
		p.VoidedAt = &now
	case domain.PaymentRefunded:
		p.RefundedAt = &now
	}
	p.Status = to
	return nil
}

// OpenHold creates the provider authorization for a booking and its payment row.
// Must NOT be called inside a DB transaction (performs network I/O before insert).
func (e *EscrowService) OpenHold(ctx context.Context, b *domain.Booking, passenger, driver *domain.User) (*domain.Payment, error) {
	req := HoldRequest{
		IdempotencyKey: "hold-" + b.ID.String(),
		AmountCents:    b.TotalCents,
		FeeCents:       b.ServiceFeeCents,
		Currency:       b.Currency,
		Description:    fmt.Sprintf("WayPoint ride %s (%d seat(s))", b.RideID, b.Seats),
		Metadata: map[string]string{
			"booking_id":   b.ID.String(),
			"ride_id":      b.RideID.String(),
			"passenger_id": passenger.ID.String(),
			"driver_id":    driver.ID.String(),
		},
	}
	if e.gw.Name() == "stripe" {
		if driver.StripeAccountID == nil || !driver.PayoutsEnabled {
			return nil, fmt.Errorf("%w: driver cannot receive payouts yet", domain.ErrPaymentRequired)
		}
		req.DestinationAccount = *driver.StripeAccountID
		customerID, err := e.ensureCustomer(ctx, passenger)
		if err != nil {
			return nil, err
		}
		req.CustomerID = customerID
	}

	intent, err := e.gw.CreateHold(ctx, req)
	if err != nil {
		return nil, fmt.Errorf("create hold: %w", err)
	}

	var payment *domain.Payment
	err = e.store.WithTx(ctx, func(tx *postgres.Store) error {
		p, err := tx.Payments.Create(ctx, &domain.Payment{
			BookingID:        b.ID,
			Provider:         e.gw.Name(),
			ProviderIntentID: intent.ID,
			ClientSecret:     intent.ClientSecret,
			AmountCents:      b.TotalCents,
			FeeCents:         b.ServiceFeeCents,
			Currency:         b.Currency,
		})
		if err != nil {
			return err
		}
		if _, err := e.applyIntent(ctx, tx, p, intent, "initial authorization"); err != nil {
			return err
		}
		payment = p
		return nil
	})
	if err != nil {
		// Release the orphaned hold so the passenger is not left with a pending charge.
		if _, cerr := e.gw.Cancel(context.WithoutCancel(ctx), intent.ID, "orphan-cancel-"+intent.ID); cerr != nil {
			e.log.Error("failed to cancel orphaned hold", "intent", intent.ID, "err", cerr)
		}
		return nil, err
	}
	return payment, nil
}

func (e *EscrowService) ensureCustomer(ctx context.Context, u *domain.User) (string, error) {
	if u.StripeCustomerID != nil && *u.StripeCustomerID != "" {
		return *u.StripeCustomerID, nil
	}
	id, err := e.gw.CreateCustomer(ctx, u.Email, u.FirstName+" "+u.LastName, u.ID.String())
	if err != nil {
		return "", fmt.Errorf("create customer: %w", err)
	}
	if err := e.store.Users.SetStripeCustomer(ctx, u.ID, id); err != nil {
		return "", err
	}
	u.StripeCustomerID = &id
	return id, nil
}

// applyIntent maps a provider intent onto the escrow state machine and persists it.
// Returns the previous status.
func (e *EscrowService) applyIntent(ctx context.Context, tx *postgres.Store, p *domain.Payment, in *Intent, reason string) (domain.PaymentStatus, error) {
	from := p.Status
	amount := 0
	switch in.Status {
	case IntentRequiresCapture:
		if p.Status == domain.PaymentPending || p.Status == domain.PaymentFailed {
			if err := e.transition(p, domain.PaymentAuthorized); err != nil {
				return from, err
			}
			amount = in.AmountCapturable
		}
	case IntentSucceeded:
		if p.Status == domain.PaymentAuthorized {
			if err := e.transition(p, domain.PaymentCaptured); err != nil {
				return from, err
			}
			p.CapturedCents = in.AmountReceived
			amount = in.AmountReceived
		}
	case IntentCanceled:
		if p.Status == domain.PaymentPending || p.Status == domain.PaymentAuthorized || p.Status == domain.PaymentFailed {
			if err := e.transition(p, domain.PaymentVoided); err != nil {
				return from, err
			}
		}
	case IntentRequiresPaymentMethod:
		if in.LastError != "" && p.Status == domain.PaymentPending {
			if err := e.transition(p, domain.PaymentFailed); err != nil {
				return from, err
			}
			p.LastError = in.LastError
		}
	}
	if in.ClientSecret != "" {
		p.ClientSecret = in.ClientSecret
	}
	if err := tx.Payments.Save(ctx, p, from, amount, reason); err != nil {
		return from, err
	}
	return from, nil
}

// ApplyIntentUpdate processes an asynchronous provider update (webhook) for an intent.
func (e *EscrowService) ApplyIntentUpdate(ctx context.Context, tx *postgres.Store, in *Intent) (*domain.Payment, domain.PaymentStatus, error) {
	p, err := tx.Payments.LockByIntent(ctx, in.ID)
	if err != nil {
		return nil, "", err
	}
	from, err := e.applyIntent(ctx, tx, p, in, "provider webhook")
	return p, from, err
}

// SyncLocked re-reads the intent from the provider and reconciles state.
// p must be locked by the caller's transaction.
func (e *EscrowService) SyncLocked(ctx context.Context, tx *postgres.Store, p *domain.Payment) (domain.PaymentStatus, error) {
	in, err := e.gw.GetIntent(ctx, p.ProviderIntentID)
	if err != nil {
		return p.Status, fmt.Errorf("fetch intent: %w", err)
	}
	return e.applyIntent(ctx, tx, p, in, "provider sync")
}

// CaptureLocked captures amountCents (≤ authorized amount) with feeCents retained by the platform.
// A zero amount releases the hold instead. p must be locked.
func (e *EscrowService) CaptureLocked(ctx context.Context, tx *postgres.Store, p *domain.Payment, amountCents, feeCents int, reason string) error {
	if amountCents <= 0 {
		return e.VoidLocked(ctx, tx, p, reason)
	}
	if p.Status == domain.PaymentCaptured {
		return nil // idempotent
	}
	if p.Status != domain.PaymentAuthorized {
		return fmt.Errorf("%w: cannot capture payment in state %s", domain.ErrInvalidTransition, p.Status)
	}
	if amountCents > p.AmountCents {
		amountCents = p.AmountCents
	}
	if feeCents > amountCents {
		feeCents = amountCents
	}
	in, err := e.gw.Capture(ctx, p.ProviderIntentID, amountCents, feeCents, "capture-"+p.ID.String())
	if err != nil {
		var apiErr *StripeAPIError
		if errors.As(err, &apiErr) && apiErr.Code == "payment_intent_unexpected_state" {
			if _, serr := e.SyncLocked(ctx, tx, p); serr != nil {
				return errors.Join(err, serr)
			}
			if p.Status == domain.PaymentCaptured {
				return nil
			}
		}
		return fmt.Errorf("capture: %w", err)
	}
	from := p.Status
	if err := e.transition(p, domain.PaymentCaptured); err != nil {
		return err
	}
	p.CapturedCents = in.AmountReceived
	if p.CapturedCents == 0 {
		p.CapturedCents = amountCents
	}
	p.FeeCents = feeCents
	return tx.Payments.Save(ctx, p, from, p.CapturedCents, reason)
}

// VoidLocked releases the authorization hold without charging. p must be locked.
func (e *EscrowService) VoidLocked(ctx context.Context, tx *postgres.Store, p *domain.Payment, reason string) error {
	switch p.Status {
	case domain.PaymentVoided:
		return nil
	case domain.PaymentCaptured, domain.PaymentRefunded:
		return fmt.Errorf("%w: cannot void payment in state %s", domain.ErrInvalidTransition, p.Status)
	}
	if _, err := e.gw.Cancel(ctx, p.ProviderIntentID, "void-"+p.ID.String()); err != nil {
		var apiErr *StripeAPIError
		if errors.As(err, &apiErr) && apiErr.Code == "payment_intent_unexpected_state" {
			if _, serr := e.SyncLocked(ctx, tx, p); serr != nil {
				return errors.Join(err, serr)
			}
			if p.Status == domain.PaymentVoided {
				return nil
			}
		}
		return fmt.Errorf("void: %w", err)
	}
	from := p.Status
	if err := e.transition(p, domain.PaymentVoided); err != nil {
		return err
	}
	return tx.Payments.Save(ctx, p, from, 0, reason)
}

// RenewLocked re-authorizes an ageing hold off-session (card holds expire after ~7 days).
func (e *EscrowService) RenewLocked(ctx context.Context, tx *postgres.Store, p *domain.Payment, b *domain.Booking, driver *domain.User) error {
	if p.Status != domain.PaymentAuthorized {
		return nil
	}
	req := HoldRequest{
		IdempotencyKey: fmt.Sprintf("renew-%s-%d", p.ID, p.AuthorizedAt.Unix()),
		AmountCents:    p.AmountCents,
		FeeCents:       p.FeeCents,
		Currency:       p.Currency,
		Description:    fmt.Sprintf("WayPoint ride %s (renewed hold)", b.RideID),
		Metadata:       map[string]string{"booking_id": b.ID.String(), "ride_id": b.RideID.String()},
	}
	if driver.StripeAccountID != nil {
		req.DestinationAccount = *driver.StripeAccountID
	}
	in, err := e.gw.RenewHold(ctx, p.ProviderIntentID, req)
	if in != nil && in.ID != "" && in.ID != p.ProviderIntentID {
		if rerr := tx.Payments.ReplaceIntent(ctx, p.ID, in.ID, in.ClientSecret); rerr != nil {
			return errors.Join(err, rerr)
		}
		p.ProviderIntentID = in.ID
	}
	if err != nil {
		return fmt.Errorf("renew hold: %w", err)
	}
	from := p.Status
	if in.Status == IntentRequiresCapture {
		now := e.now()
		p.AuthorizedAt = &now
		return tx.Payments.Save(ctx, p, from, in.AmountCapturable, "hold renewed")
	}
	p.LastError = in.LastError
	if p.LastError == "" {
		p.LastError = "re-authorization returned status " + in.Status
	}
	if err := e.transition(p, domain.PaymentFailed); err != nil {
		return err
	}
	return tx.Payments.Save(ctx, p, from, 0, "hold renewal failed")
}

// ApplyRefund records a refund issued at the provider (e.g. by support in the dashboard).
func (e *EscrowService) ApplyRefund(ctx context.Context, tx *postgres.Store, intentID string, refundedCents int) error {
	p, err := tx.Payments.LockByIntent(ctx, intentID)
	if err != nil {
		return err
	}
	if p.Status != domain.PaymentCaptured && p.Status != domain.PaymentRefunded {
		return nil
	}
	from := p.Status
	if refundedCents > p.CapturedCents {
		refundedCents = p.CapturedCents
	}
	p.RefundedCents = refundedCents
	if err := e.transition(p, domain.PaymentRefunded); err != nil {
		return err
	}
	return tx.Payments.Save(ctx, p, from, refundedCents, "refund issued")
}
