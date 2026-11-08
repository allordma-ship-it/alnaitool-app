package service

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
	"golang.org/x/crypto/bcrypt"

	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/cache"
	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/domain"
	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/repository/postgres"
)

const (
	tokenIssuer = "waypoint"
	typeAccess  = "access"
	typeRefresh = "refresh"
	bcryptCost  = 12
)

// TokenPair is returned on login, registration and refresh.
type TokenPair struct {
	AccessToken      string    `json:"access_token"`
	RefreshToken     string    `json:"refresh_token"`
	TokenType        string    `json:"token_type"`
	AccessExpiresAt  time.Time `json:"access_expires_at"`
	RefreshExpiresAt time.Time `json:"refresh_expires_at"`
}

// AuthResult bundles tokens with the authenticated user.
type AuthResult struct {
	Tokens TokenPair     `json:"tokens"`
	User   domain.MeView `json:"user"`
}

type claims struct {
	Type string `json:"typ"`
	jwt.RegisteredClaims
}

// AuthService handles credentials and JWT session tokens with rotating refresh tokens.
type AuthService struct {
	store      *postgres.Store
	cache      *cache.Cache
	secret     []byte
	accessTTL  time.Duration
	refreshTTL time.Duration
	dummyHash  []byte
}

// NewAuthService builds the auth service.
func NewAuthService(store *postgres.Store, c *cache.Cache, secret string, accessTTL, refreshTTL time.Duration) *AuthService {
	dummy, _ := bcrypt.GenerateFromPassword([]byte("waypoint-timing-equalizer"), bcryptCost)
	return &AuthService{store: store, cache: c, secret: []byte(secret), accessTTL: accessTTL, refreshTTL: refreshTTL, dummyHash: dummy}
}

// Register creates an account and signs the user in.
func (s *AuthService) Register(ctx context.Context, in domain.RegisterInput) (*AuthResult, error) {
	in.Normalize()
	if err := in.Validate(); err != nil {
		return nil, err
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(in.Password), bcryptCost)
	if err != nil {
		return nil, fmt.Errorf("hash password: %w", err)
	}
	u, err := s.store.Users.Create(ctx, in, string(hash))
	if err != nil {
		if errors.Is(err, domain.ErrConflict) {
			return nil, domain.NewValidationError("email", "is already registered")
		}
		return nil, err
	}
	return s.issue(ctx, u)
}

// Login verifies credentials.
func (s *AuthService) Login(ctx context.Context, email, password string) (*AuthResult, error) {
	u, err := s.store.Users.GetByEmail(ctx, normalizeEmail(email))
	if errors.Is(err, domain.ErrNotFound) {
		_ = bcrypt.CompareHashAndPassword(s.dummyHash, []byte(password)) // equalize timing
		return nil, domain.ErrUnauthorized
	}
	if err != nil {
		return nil, err
	}
	if bcrypt.CompareHashAndPassword([]byte(u.PasswordHash), []byte(password)) != nil {
		return nil, domain.ErrUnauthorized
	}
	return s.issue(ctx, u)
}

// Refresh rotates a refresh token. Reuse of a consumed token revokes all sessions.
func (s *AuthService) Refresh(ctx context.Context, refreshToken string) (*AuthResult, error) {
	c, err := s.parse(refreshToken, typeRefresh)
	if err != nil {
		return nil, domain.ErrUnauthorized
	}
	owner, err := s.cache.ConsumeRefreshToken(ctx, c.ID)
	if errors.Is(err, cache.ErrMiss) {
		_ = s.cache.RevokeAllRefreshTokens(ctx, c.Subject) // token reuse => assume theft
		return nil, domain.ErrUnauthorized
	}
	if err != nil {
		return nil, err
	}
	if owner != c.Subject {
		return nil, domain.ErrUnauthorized
	}
	uid, err := uuid.Parse(c.Subject)
	if err != nil {
		return nil, domain.ErrUnauthorized
	}
	u, err := s.store.Users.GetByID(ctx, uid)
	if err != nil {
		return nil, domain.ErrUnauthorized
	}
	return s.issue(ctx, u)
}

// Logout revokes a refresh token, or every session when all is true.
func (s *AuthService) Logout(ctx context.Context, userID uuid.UUID, refreshToken string, all bool) error {
	if all {
		return s.cache.RevokeAllRefreshTokens(ctx, userID.String())
	}
	c, err := s.parse(refreshToken, typeRefresh)
	if err != nil || c.Subject != userID.String() {
		return nil
	}
	_, err = s.cache.ConsumeRefreshToken(ctx, c.ID)
	if errors.Is(err, cache.ErrMiss) {
		return nil
	}
	return err
}

// Authenticate validates an access token and returns the user id.
func (s *AuthService) Authenticate(token string) (uuid.UUID, error) {
	c, err := s.parse(token, typeAccess)
	if err != nil {
		return uuid.Nil, domain.ErrUnauthorized
	}
	id, err := uuid.Parse(c.Subject)
	if err != nil {
		return uuid.Nil, domain.ErrUnauthorized
	}
	return id, nil
}

func (s *AuthService) issue(ctx context.Context, u *domain.User) (*AuthResult, error) {
	now := time.Now()
	accessExp := now.Add(s.accessTTL)
	refreshExp := now.Add(s.refreshTTL)

	access, err := s.sign(u.ID, typeAccess, uuid.NewString(), now, accessExp)
	if err != nil {
		return nil, err
	}
	refreshID := uuid.NewString()
	refresh, err := s.sign(u.ID, typeRefresh, refreshID, now, refreshExp)
	if err != nil {
		return nil, err
	}
	if err := s.cache.StoreRefreshToken(ctx, refreshID, u.ID.String(), s.refreshTTL); err != nil {
		return nil, fmt.Errorf("store refresh token: %w", err)
	}
	return &AuthResult{
		Tokens: TokenPair{
			AccessToken:      access,
			RefreshToken:     refresh,
			TokenType:        "Bearer",
			AccessExpiresAt:  accessExp,
			RefreshExpiresAt: refreshExp,
		},
		User: u.Me(),
	}, nil
}

func (s *AuthService) sign(userID uuid.UUID, typ, jti string, now, exp time.Time) (string, error) {
	t := jwt.NewWithClaims(jwt.SigningMethodHS256, claims{
		Type: typ,
		RegisteredClaims: jwt.RegisteredClaims{
			Issuer:    tokenIssuer,
			Subject:   userID.String(),
			ID:        jti,
			IssuedAt:  jwt.NewNumericDate(now),
			NotBefore: jwt.NewNumericDate(now.Add(-30 * time.Second)),
			ExpiresAt: jwt.NewNumericDate(exp),
		},
	})
	return t.SignedString(s.secret)
}

func (s *AuthService) parse(token, wantType string) (*claims, error) {
	var c claims
	_, err := jwt.ParseWithClaims(token, &c, func(t *jwt.Token) (any, error) {
		return s.secret, nil
	}, jwt.WithValidMethods([]string{jwt.SigningMethodHS256.Alg()}), jwt.WithIssuer(tokenIssuer), jwt.WithExpirationRequired())
	if err != nil {
		return nil, err
	}
	if c.Type != wantType {
		return nil, errors.New("wrong token type")
	}
	return &c, nil
}
