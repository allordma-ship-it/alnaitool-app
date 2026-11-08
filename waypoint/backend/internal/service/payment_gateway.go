package service

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"

	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/domain"
)

// Provider intent statuses (Stripe PaymentIntent vocabulary).
const (
	IntentRequiresPaymentMethod = "requires_payment_method"
	IntentRequiresConfirmation  = "requires_confirmation"
	IntentRequiresAction        = "requires_action"
	IntentProcessing            = "processing"
	IntentRequiresCapture       = "requires_capture"
	IntentCanceled              = "canceled"
	IntentSucceeded             = "succeeded"
)

// Intent is a provider-side payment authorization.
type Intent struct {
	ID               string
	ClientSecret     string
	Status           string
	Amount           int
	AmountCapturable int
	AmountReceived   int
	AmountRefunded   int
	LastError        string
	Metadata         map[string]string
}

// HoldRequest asks the provider to authorize (not capture) funds.
type HoldRequest struct {
	IdempotencyKey     string
	AmountCents        int
	FeeCents           int
	Currency           string
	CustomerID         string
	DestinationAccount string
	Description        string
	Metadata           map[string]string
}

// ConnectedAccount is a driver's payout account status.
type ConnectedAccount struct {
	ID             string
	PayoutsEnabled bool
	ChargesEnabled bool
	DetailsDone    bool
}

// PaymentGateway abstracts the escrow-capable payment processor.
type PaymentGateway interface {
	Name() string
	PublishableKey() string
	CreateCustomer(ctx context.Context, email, name, userID string) (string, error)
	CreateHold(ctx context.Context, req HoldRequest) (*Intent, error)
	RenewHold(ctx context.Context, oldIntentID string, req HoldRequest) (*Intent, error)
	GetIntent(ctx context.Context, id string) (*Intent, error)
	Capture(ctx context.Context, id string, amountCents, feeCents int, idempotencyKey string) (*Intent, error)
	Cancel(ctx context.Context, id string, idempotencyKey string) (*Intent, error)
	CreateConnectedAccount(ctx context.Context, email, country, userID string) (string, error)
	OnboardingLink(ctx context.Context, accountID, refreshURL, returnURL string) (string, error)
	GetAccount(ctx context.Context, accountID string) (*ConnectedAccount, error)
}

// ---------------------------------------------------------------------------
// Stripe (REST, Connect destination charges with manual capture)
// ---------------------------------------------------------------------------

const (
	stripeAPIBase    = "https://api.stripe.com/v1"
	stripeAPIVersion = "2024-06-20"
)

// StripeGateway implements PaymentGateway against the Stripe REST API.
type StripeGateway struct {
	secretKey      string
	publishableKey string
	http           *http.Client
	baseURL        string
}

// NewStripeGateway builds a Stripe gateway.
func NewStripeGateway(secretKey, publishableKey string) *StripeGateway {
	return &StripeGateway{
		secretKey:      secretKey,
		publishableKey: publishableKey,
		http:           &http.Client{Timeout: 20 * time.Second},
		baseURL:        stripeAPIBase,
	}
}

// Name implements PaymentGateway.
func (s *StripeGateway) Name() string { return "stripe" }

// PublishableKey implements PaymentGateway.
func (s *StripeGateway) PublishableKey() string { return s.publishableKey }

type stripeError struct {
	Error struct {
		Type        string `json:"type"`
		Code        string `json:"code"`
		DeclineCode string `json:"decline_code"`
		Message     string `json:"message"`
	} `json:"error"`
}

// StripeAPIError is a non-2xx response from Stripe.
type StripeAPIError struct {
	Status  int
	Type    string
	Code    string
	Message string
}

func (e *StripeAPIError) Error() string {
	return fmt.Sprintf("stripe %d %s/%s: %s", e.Status, e.Type, e.Code, e.Message)
}

func (s *StripeGateway) call(ctx context.Context, method, path string, form url.Values, idemKey string, out any) error {
	var body io.Reader
	if form != nil && method != http.MethodGet {
		body = strings.NewReader(form.Encode())
	}
	endpoint := s.baseURL + path
	if form != nil && method == http.MethodGet {
		endpoint += "?" + form.Encode()
	}
	req, err := http.NewRequestWithContext(ctx, method, endpoint, body)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+s.secretKey)
	req.Header.Set("Stripe-Version", stripeAPIVersion)
	if body != nil {
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	}
	if idemKey != "" {
		req.Header.Set("Idempotency-Key", idemKey)
	}

	var resp *http.Response
	for attempt := 0; attempt < 3; attempt++ {
		if attempt > 0 && body != nil {
			req.Body = io.NopCloser(strings.NewReader(form.Encode()))
		}
		resp, err = s.http.Do(req)
		if err == nil && resp.StatusCode < 500 && resp.StatusCode != http.StatusTooManyRequests {
			break
		}
		if err == nil {
			resp.Body.Close()
		}
		if idemKey == "" && method != http.MethodGet {
			break // never blindly retry non-idempotent writes
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(time.Duration(200*(1<<attempt)) * time.Millisecond):
		}
	}
	if err != nil {
		return fmt.Errorf("%w: stripe: %v", domain.ErrUpstream, err)
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return fmt.Errorf("%w: stripe read: %v", domain.ErrUpstream, err)
	}
	if resp.StatusCode >= 300 {
		var se stripeError
		_ = json.Unmarshal(raw, &se)
		apiErr := &StripeAPIError{Status: resp.StatusCode, Type: se.Error.Type, Code: se.Error.Code, Message: se.Error.Message}
		if resp.StatusCode >= 500 || resp.StatusCode == http.StatusTooManyRequests {
			return fmt.Errorf("%w: %v", domain.ErrUpstream, apiErr)
		}
		return apiErr
	}
	if out != nil {
		if err := json.Unmarshal(raw, out); err != nil {
			return fmt.Errorf("stripe decode: %w", err)
		}
	}
	return nil
}

type stripeIntent struct {
	ID               string            `json:"id"`
	ClientSecret     string            `json:"client_secret"`
	Status           string            `json:"status"`
	Amount           int               `json:"amount"`
	AmountCapturable int               `json:"amount_capturable"`
	AmountReceived   int               `json:"amount_received"`
	PaymentMethod    json.RawMessage   `json:"payment_method"`
	Customer         json.RawMessage   `json:"customer"`
	Metadata         map[string]string `json:"metadata"`
	LastPaymentError *struct {
		Message string `json:"message"`
		Code    string `json:"code"`
	} `json:"last_payment_error"`
	LatestCharge json.RawMessage `json:"latest_charge"`
}

func (si *stripeIntent) toIntent() *Intent {
	in := &Intent{
		ID:               si.ID,
		ClientSecret:     si.ClientSecret,
		Status:           si.Status,
		Amount:           si.Amount,
		AmountCapturable: si.AmountCapturable,
		AmountReceived:   si.AmountReceived,
		Metadata:         si.Metadata,
	}
	if si.LastPaymentError != nil {
		in.LastError = si.LastPaymentError.Message
	}
	return in
}

// expandableID extracts the id from a Stripe field that is either "id" or {"id": ...}.
func expandableID(raw json.RawMessage) string {
	if len(raw) == 0 || string(raw) == "null" {
		return ""
	}
	var s string
	if err := json.Unmarshal(raw, &s); err == nil {
		return s
	}
	var obj struct {
		ID string `json:"id"`
	}
	if err := json.Unmarshal(raw, &obj); err == nil {
		return obj.ID
	}
	return ""
}

func holdForm(req HoldRequest) url.Values {
	f := url.Values{}
	f.Set("amount", strconv.Itoa(req.AmountCents))
	f.Set("currency", strings.ToLower(req.Currency))
	f.Set("capture_method", "manual")
	if req.CustomerID != "" {
		f.Set("customer", req.CustomerID)
	}
	if req.DestinationAccount != "" {
		f.Set("transfer_data[destination]", req.DestinationAccount)
		f.Set("application_fee_amount", strconv.Itoa(req.FeeCents))
	}
	if req.Description != "" {
		f.Set("description", req.Description)
	}
	for k, v := range req.Metadata {
		f.Set("metadata["+k+"]", v)
	}
	return f
}

// CreateCustomer implements PaymentGateway.
func (s *StripeGateway) CreateCustomer(ctx context.Context, email, name, userID string) (string, error) {
	f := url.Values{}
	f.Set("email", email)
	f.Set("name", name)
	f.Set("metadata[user_id]", userID)
	var out struct {
		ID string `json:"id"`
	}
	if err := s.call(ctx, http.MethodPost, "/customers", f, "customer-"+userID, &out); err != nil {
		return "", err
	}
	return out.ID, nil
}

// CreateHold implements PaymentGateway: a manual-capture PaymentIntent whose
// card is saved for off-session re-authorization.
func (s *StripeGateway) CreateHold(ctx context.Context, req HoldRequest) (*Intent, error) {
	f := holdForm(req)
	f.Set("automatic_payment_methods[enabled]", "true")
	f.Set("automatic_payment_methods[allow_redirects]", "never")
	f.Set("setup_future_usage", "off_session")
	var out stripeIntent
	if err := s.call(ctx, http.MethodPost, "/payment_intents", f, req.IdempotencyKey, &out); err != nil {
		return nil, err
	}
	return out.toIntent(), nil
}

// RenewHold re-authorizes funds off-session with the card used on the previous
// intent, then cancels the old hold. Card authorizations expire after ~7 days.
func (s *StripeGateway) RenewHold(ctx context.Context, oldIntentID string, req HoldRequest) (*Intent, error) {
	var old stripeIntent
	if err := s.call(ctx, http.MethodGet, "/payment_intents/"+url.PathEscape(oldIntentID), nil, "", &old); err != nil {
		return nil, err
	}
	pm := expandableID(old.PaymentMethod)
	if pm == "" {
		return nil, errors.New("previous intent has no payment method to renew")
	}
	f := holdForm(req)
	if cust := expandableID(old.Customer); cust != "" {
		f.Set("customer", cust)
	}
	f.Set("payment_method", pm)
	f.Set("off_session", "true")
	f.Set("confirm", "true")
	var out stripeIntent
	if err := s.call(ctx, http.MethodPost, "/payment_intents", f, req.IdempotencyKey, &out); err != nil {
		return nil, err
	}
	if out.Status == IntentRequiresCapture {
		if _, err := s.Cancel(ctx, oldIntentID, "renew-cancel-"+oldIntentID); err != nil {
			var apiErr *StripeAPIError
			// An already expired/canceled old hold is fine.
			if !errors.As(err, &apiErr) || apiErr.Code != "payment_intent_unexpected_state" {
				return out.toIntent(), fmt.Errorf("cancel previous hold: %w", err)
			}
		}
	}
	return out.toIntent(), nil
}

// GetIntent implements PaymentGateway.
func (s *StripeGateway) GetIntent(ctx context.Context, id string) (*Intent, error) {
	var out stripeIntent
	if err := s.call(ctx, http.MethodGet, "/payment_intents/"+url.PathEscape(id), nil, "", &out); err != nil {
		return nil, err
	}
	return out.toIntent(), nil
}

// Capture implements PaymentGateway (partial capture supported).
func (s *StripeGateway) Capture(ctx context.Context, id string, amountCents, feeCents int, idempotencyKey string) (*Intent, error) {
	f := url.Values{}
	f.Set("amount_to_capture", strconv.Itoa(amountCents))
	f.Set("application_fee_amount", strconv.Itoa(feeCents))
	var out stripeIntent
	if err := s.call(ctx, http.MethodPost, "/payment_intents/"+url.PathEscape(id)+"/capture", f, idempotencyKey, &out); err != nil {
		return nil, err
	}
	return out.toIntent(), nil
}

// Cancel implements PaymentGateway (releases the authorization hold).
func (s *StripeGateway) Cancel(ctx context.Context, id string, idempotencyKey string) (*Intent, error) {
	var out stripeIntent
	if err := s.call(ctx, http.MethodPost, "/payment_intents/"+url.PathEscape(id)+"/cancel", url.Values{}, idempotencyKey, &out); err != nil {
		return nil, err
	}
	return out.toIntent(), nil
}

// CreateConnectedAccount implements PaymentGateway (Express account for payouts).
func (s *StripeGateway) CreateConnectedAccount(ctx context.Context, email, country, userID string) (string, error) {
	f := url.Values{}
	f.Set("type", "express")
	f.Set("email", email)
	f.Set("country", country)
	f.Set("business_type", "individual")
	f.Set("capabilities[transfers][requested]", "true")
	f.Set("capabilities[card_payments][requested]", "true")
	f.Set("metadata[user_id]", userID)
	var out struct {
		ID string `json:"id"`
	}
	if err := s.call(ctx, http.MethodPost, "/accounts", f, "account-"+userID, &out); err != nil {
		return "", err
	}
	return out.ID, nil
}

// OnboardingLink implements PaymentGateway.
func (s *StripeGateway) OnboardingLink(ctx context.Context, accountID, refreshURL, returnURL string) (string, error) {
	f := url.Values{}
	f.Set("account", accountID)
	f.Set("refresh_url", refreshURL)
	f.Set("return_url", returnURL)
	f.Set("type", "account_onboarding")
	var out struct {
		URL string `json:"url"`
	}
	if err := s.call(ctx, http.MethodPost, "/account_links", f, "", &out); err != nil {
		return "", err
	}
	return out.URL, nil
}

// GetAccount implements PaymentGateway.
func (s *StripeGateway) GetAccount(ctx context.Context, accountID string) (*ConnectedAccount, error) {
	var out struct {
		ID               string `json:"id"`
		PayoutsEnabled   bool   `json:"payouts_enabled"`
		ChargesEnabled   bool   `json:"charges_enabled"`
		DetailsSubmitted bool   `json:"details_submitted"`
	}
	if err := s.call(ctx, http.MethodGet, "/accounts/"+url.PathEscape(accountID), nil, "", &out); err != nil {
		return nil, err
	}
	return &ConnectedAccount{ID: out.ID, PayoutsEnabled: out.PayoutsEnabled, ChargesEnabled: out.ChargesEnabled, DetailsDone: out.DetailsSubmitted}, nil
}

// StripeEvent is a parsed webhook envelope.
type StripeEvent struct {
	ID   string `json:"id"`
	Type string `json:"type"`
	Data struct {
		Object json.RawMessage `json:"object"`
	} `json:"data"`
}

// VerifyStripeSignature validates a Stripe-Signature header (v1 scheme) and parses the event.
func VerifyStripeSignature(payload []byte, header, secret string, tolerance time.Duration, now time.Time) (*StripeEvent, error) {
	var timestamp string
	var signatures []string
	for _, part := range strings.Split(header, ",") {
		kv := strings.SplitN(strings.TrimSpace(part), "=", 2)
		if len(kv) != 2 {
			continue
		}
		switch kv[0] {
		case "t":
			timestamp = kv[1]
		case "v1":
			signatures = append(signatures, kv[1])
		}
	}
	if timestamp == "" || len(signatures) == 0 {
		return nil, errors.New("malformed Stripe-Signature header")
	}
	ts, err := strconv.ParseInt(timestamp, 10, 64)
	if err != nil {
		return nil, errors.New("invalid signature timestamp")
	}
	if d := now.Sub(time.Unix(ts, 0)); d > tolerance || d < -tolerance {
		return nil, errors.New("signature timestamp outside tolerance")
	}
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(timestamp))
	mac.Write([]byte("."))
	mac.Write(payload)
	expected := mac.Sum(nil)
	valid := false
	for _, sig := range signatures {
		got, err := hex.DecodeString(sig)
		if err == nil && hmac.Equal(got, expected) {
			valid = true
			break
		}
	}
	if !valid {
		return nil, errors.New("signature mismatch")
	}
	var ev StripeEvent
	if err := json.Unmarshal(payload, &ev); err != nil {
		return nil, fmt.Errorf("decode event: %w", err)
	}
	return &ev, nil
}

// ParseIntentObject decodes a PaymentIntent from a webhook event object.
func ParseIntentObject(raw json.RawMessage) (*Intent, error) {
	var si stripeIntent
	if err := json.Unmarshal(raw, &si); err != nil {
		return nil, err
	}
	return si.toIntent(), nil
}

// ---------------------------------------------------------------------------
// Simulated gateway (local development / CI): deterministic, instant authorization.
// ---------------------------------------------------------------------------

// SimulatedGateway mimics Stripe's manual-capture semantics in memory.
// Cards are authorized immediately; amounts ending in 13 cents decline,
// which allows exercising failure paths end-to-end.
type SimulatedGateway struct {
	mu      sync.Mutex
	intents map[string]*Intent
	idem    map[string]string
	accts   map[string]*ConnectedAccount
}

// NewSimulatedGateway builds the in-memory gateway.
func NewSimulatedGateway() *SimulatedGateway {
	return &SimulatedGateway{
		intents: make(map[string]*Intent),
		idem:    make(map[string]string),
		accts:   make(map[string]*ConnectedAccount),
	}
}

// Name implements PaymentGateway.
func (g *SimulatedGateway) Name() string { return "simulated" }

// PublishableKey implements PaymentGateway.
func (g *SimulatedGateway) PublishableKey() string { return "" }

// CreateCustomer implements PaymentGateway.
func (g *SimulatedGateway) CreateCustomer(_ context.Context, _, _, userID string) (string, error) {
	return "sim_cus_" + strings.ReplaceAll(userID, "-", "")[:16], nil
}

func (g *SimulatedGateway) authorize(req HoldRequest) *Intent {
	id := "sim_pi_" + strings.ReplaceAll(uuid.NewString(), "-", "")
	in := &Intent{ID: id, ClientSecret: id + "_secret", Amount: req.AmountCents, Metadata: req.Metadata}
	if req.AmountCents%100 == 13 {
		in.Status = IntentRequiresPaymentMethod
		in.LastError = "Your card was declined (simulated)."
	} else {
		in.Status = IntentRequiresCapture
		in.AmountCapturable = req.AmountCents
	}
	g.intents[id] = in
	return in
}

// CreateHold implements PaymentGateway.
func (g *SimulatedGateway) CreateHold(_ context.Context, req HoldRequest) (*Intent, error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if id, ok := g.idem[req.IdempotencyKey]; ok && req.IdempotencyKey != "" {
		cp := *g.intents[id]
		return &cp, nil
	}
	in := g.authorize(req)
	if req.IdempotencyKey != "" {
		g.idem[req.IdempotencyKey] = in.ID
	}
	cp := *in
	return &cp, nil
}

// RenewHold implements PaymentGateway.
func (g *SimulatedGateway) RenewHold(_ context.Context, oldIntentID string, req HoldRequest) (*Intent, error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if old, ok := g.intents[oldIntentID]; ok && old.Status == IntentRequiresCapture {
		old.Status = IntentCanceled
		old.AmountCapturable = 0
	}
	in := g.authorize(req)
	cp := *in
	return &cp, nil
}

func (g *SimulatedGateway) lookup(id string) (*Intent, error) {
	in, ok := g.intents[id]
	if !ok {
		// After a process restart the in-memory state is gone; simulated holds are
		// treated as still authorized so local flows keep working.
		in = &Intent{ID: id, Status: IntentRequiresCapture}
		g.intents[id] = in
	}
	return in, nil
}

// GetIntent implements PaymentGateway.
func (g *SimulatedGateway) GetIntent(_ context.Context, id string) (*Intent, error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	in, err := g.lookup(id)
	if err != nil {
		return nil, err
	}
	cp := *in
	return &cp, nil
}

// Capture implements PaymentGateway.
func (g *SimulatedGateway) Capture(_ context.Context, id string, amountCents, _ int, _ string) (*Intent, error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	in, _ := g.lookup(id)
	switch in.Status {
	case IntentSucceeded:
		cp := *in
		return &cp, nil
	case IntentRequiresCapture:
	default:
		return nil, &StripeAPIError{Status: 400, Type: "invalid_request_error", Code: "payment_intent_unexpected_state",
			Message: "intent is " + in.Status}
	}
	in.Status = IntentSucceeded
	in.AmountReceived = amountCents
	in.AmountCapturable = 0
	cp := *in
	return &cp, nil
}

// Cancel implements PaymentGateway.
func (g *SimulatedGateway) Cancel(_ context.Context, id string, _ string) (*Intent, error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	in, _ := g.lookup(id)
	if in.Status == IntentSucceeded {
		return nil, &StripeAPIError{Status: 400, Type: "invalid_request_error", Code: "payment_intent_unexpected_state",
			Message: "cannot cancel a succeeded intent"}
	}
	in.Status = IntentCanceled
	in.AmountCapturable = 0
	cp := *in
	return &cp, nil
}

// CreateConnectedAccount implements PaymentGateway.
func (g *SimulatedGateway) CreateConnectedAccount(_ context.Context, _, _, userID string) (string, error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	id := "sim_acct_" + strings.ReplaceAll(userID, "-", "")[:16]
	g.accts[id] = &ConnectedAccount{ID: id, PayoutsEnabled: true, ChargesEnabled: true, DetailsDone: true}
	return id, nil
}

// OnboardingLink implements PaymentGateway (returns the app return URL directly).
func (g *SimulatedGateway) OnboardingLink(_ context.Context, _, _, returnURL string) (string, error) {
	return returnURL, nil
}

// GetAccount implements PaymentGateway.
func (g *SimulatedGateway) GetAccount(_ context.Context, accountID string) (*ConnectedAccount, error) {
	return &ConnectedAccount{ID: accountID, PayoutsEnabled: true, ChargesEnabled: true, DetailsDone: true}, nil
}

// ---------------------------------------------------------------------------
// Cash settlement (production gateway for markets without card acquiring).
// ---------------------------------------------------------------------------

// CashGateway settles bookings in cash, paid by the passenger to the driver
// during the trip. No money moves through the platform: a "hold" is a seat
// reservation, "capture" marks the trip completed and "cancel" releases it.
// It is stateless, so it survives restarts and scales horizontally.
type CashGateway struct{}

// NewCashGateway builds the cash gateway.
func NewCashGateway() *CashGateway { return &CashGateway{} }

// Name implements PaymentGateway.
func (CashGateway) Name() string { return "cash" }

// PublishableKey implements PaymentGateway.
func (CashGateway) PublishableKey() string { return "" }

var errCashUnsupported = errors.New("not applicable to cash settlement")

// CreateCustomer implements PaymentGateway.
func (CashGateway) CreateCustomer(context.Context, string, string, string) (string, error) {
	return "", errCashUnsupported
}

// CreateHold implements PaymentGateway: the reservation is effective immediately.
func (CashGateway) CreateHold(_ context.Context, req HoldRequest) (*Intent, error) {
	id := "cash_" + strings.ReplaceAll(uuid.NewString(), "-", "")
	return &Intent{ID: id, Status: IntentRequiresCapture, Amount: req.AmountCents,
		AmountCapturable: req.AmountCents, Metadata: req.Metadata}, nil
}

// RenewHold implements PaymentGateway (cash reservations never expire).
func (CashGateway) RenewHold(_ context.Context, oldIntentID string, req HoldRequest) (*Intent, error) {
	return &Intent{ID: oldIntentID, Status: IntentRequiresCapture, Amount: req.AmountCents, AmountCapturable: req.AmountCents}, nil
}

// GetIntent implements PaymentGateway. State lives in the payments table; an
// outstanding cash reservation is always "authorized".
func (CashGateway) GetIntent(_ context.Context, id string) (*Intent, error) {
	return &Intent{ID: id, Status: IntentRequiresCapture}, nil
}

// Capture implements PaymentGateway (trip completed, cash paid to the driver).
func (CashGateway) Capture(_ context.Context, id string, amountCents, _ int, _ string) (*Intent, error) {
	return &Intent{ID: id, Status: IntentSucceeded, AmountReceived: amountCents}, nil
}

// Cancel implements PaymentGateway (reservation released).
func (CashGateway) Cancel(_ context.Context, id string, _ string) (*Intent, error) {
	return &Intent{ID: id, Status: IntentCanceled}, nil
}

// CreateConnectedAccount implements PaymentGateway.
func (CashGateway) CreateConnectedAccount(context.Context, string, string, string) (string, error) {
	return "", errCashUnsupported
}

// OnboardingLink implements PaymentGateway.
func (CashGateway) OnboardingLink(context.Context, string, string, string) (string, error) {
	return "", errCashUnsupported
}

// GetAccount implements PaymentGateway: drivers are paid directly, always "enabled".
func (CashGateway) GetAccount(_ context.Context, accountID string) (*ConnectedAccount, error) {
	return &ConnectedAccount{ID: accountID, PayoutsEnabled: true, ChargesEnabled: true, DetailsDone: true}, nil
}
