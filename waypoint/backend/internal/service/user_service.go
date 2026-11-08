package service

import (
	"context"
	"fmt"
	"strings"

	"github.com/google/uuid"

	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/domain"
	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/repository/postgres"
)

// UserService manages profiles and driver payout onboarding (Stripe Connect).
type UserService struct {
	store      *postgres.Store
	escrow     *EscrowService
	refreshURL string
	returnURL  string
}

// NewUserService builds the user service.
func NewUserService(store *postgres.Store, escrow *EscrowService, refreshURL, returnURL string) *UserService {
	return &UserService{store: store, escrow: escrow, refreshURL: refreshURL, returnURL: returnURL}
}

// Me returns the caller's account.
func (s *UserService) Me(ctx context.Context, userID uuid.UUID) (*domain.MeView, error) {
	u, err := s.store.Users.GetByID(ctx, userID)
	if err != nil {
		return nil, err
	}
	me := u.Me()
	return &me, nil
}

// UpdateProfile edits the caller's profile.
func (s *UserService) UpdateProfile(ctx context.Context, userID uuid.UUID, in domain.UpdateProfileInput) (*domain.MeView, error) {
	if err := in.Validate(); err != nil {
		return nil, err
	}
	u, err := s.store.Users.UpdateProfile(ctx, userID, in)
	if err != nil {
		return nil, err
	}
	me := u.Me()
	return &me, nil
}

// PublicProfile returns another member's profile with recent reviews.
func (s *UserService) PublicProfile(ctx context.Context, userID uuid.UUID) (map[string]any, error) {
	u, err := s.store.Users.GetByID(ctx, userID)
	if err != nil {
		return nil, err
	}
	reviews, err := s.store.Reviews.ListForSubject(ctx, userID, 20)
	if err != nil {
		return nil, err
	}
	if reviews == nil {
		reviews = []domain.Review{}
	}
	return map[string]any{"profile": u.Public(), "reviews": reviews}, nil
}

// PayoutOnboarding is the response to a driver starting payout setup.
type PayoutOnboarding struct {
	OnboardingURL  string `json:"onboarding_url"`
	PayoutsEnabled bool   `json:"payouts_enabled"`
}

// StartPayoutOnboarding creates (if needed) a connected account and returns an onboarding link.
func (s *UserService) StartPayoutOnboarding(ctx context.Context, userID uuid.UUID, country string) (*PayoutOnboarding, error) {
	country = strings.ToUpper(strings.TrimSpace(country))
	if country == "" {
		country = "DE"
	}
	if len(country) != 2 {
		return nil, domain.NewValidationError("country", "must be an ISO-3166 alpha-2 code")
	}
	u, err := s.store.Users.GetByID(ctx, userID)
	if err != nil {
		return nil, err
	}
	gw := s.escrow.Gateway()
	accountID := ""
	if u.StripeAccountID != nil {
		accountID = *u.StripeAccountID
	} else {
		accountID, err = gw.CreateConnectedAccount(ctx, u.Email, country, u.ID.String())
		if err != nil {
			return nil, fmt.Errorf("create connected account: %w", err)
		}
		if err := s.store.Users.SetStripeAccount(ctx, u.ID, accountID); err != nil {
			return nil, err
		}
	}
	link, err := gw.OnboardingLink(ctx, accountID, s.refreshURL, s.returnURL)
	if err != nil {
		return nil, fmt.Errorf("create onboarding link: %w", err)
	}
	acct, err := gw.GetAccount(ctx, accountID)
	if err != nil {
		return nil, err
	}
	if acct.PayoutsEnabled != u.PayoutsEnabled {
		if err := s.store.Users.SetPayoutsEnabled(ctx, u.ID, acct.PayoutsEnabled); err != nil {
			return nil, err
		}
	}
	return &PayoutOnboarding{OnboardingURL: link, PayoutsEnabled: acct.PayoutsEnabled && acct.ChargesEnabled}, nil
}

// RefreshPayoutStatus re-reads the connected account and updates the user flag.
func (s *UserService) RefreshPayoutStatus(ctx context.Context, userID uuid.UUID) (*domain.MeView, error) {
	u, err := s.store.Users.GetByID(ctx, userID)
	if err != nil {
		return nil, err
	}
	if u.StripeAccountID != nil {
		acct, err := s.escrow.Gateway().GetAccount(ctx, *u.StripeAccountID)
		if err != nil {
			return nil, err
		}
		enabled := acct.PayoutsEnabled && acct.ChargesEnabled
		if enabled != u.PayoutsEnabled {
			if err := s.store.Users.SetPayoutsEnabled(ctx, u.ID, enabled); err != nil {
				return nil, err
			}
			u.PayoutsEnabled = enabled
		}
	}
	me := u.Me()
	return &me, nil
}

// ApplyAccountUpdate handles Stripe account.updated webhooks.
func (s *UserService) ApplyAccountUpdate(ctx context.Context, accountID string, payoutsEnabled, chargesEnabled bool) error {
	u, err := s.store.Users.GetByStripeAccount(ctx, accountID)
	if err != nil {
		return err
	}
	return s.store.Users.SetPayoutsEnabled(ctx, u.ID, payoutsEnabled && chargesEnabled)
}
