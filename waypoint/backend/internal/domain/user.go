package domain

import (
	"math"
	"net/mail"
	"strings"
	"time"

	"github.com/google/uuid"
)

// User is a registered member who can act as driver and/or passenger.
type User struct {
	ID               uuid.UUID `json:"id"`
	Email            string    `json:"email"`
	Phone            *string   `json:"phone,omitempty"`
	PasswordHash     string    `json:"-"`
	FirstName        string    `json:"first_name"`
	LastName         string    `json:"last_name"`
	Bio              string    `json:"bio"`
	AvatarURL        *string   `json:"avatar_url,omitempty"`
	RatingSum        int       `json:"-"`
	RatingCount      int       `json:"rating_count"`
	StripeCustomerID *string   `json:"-"`
	StripeAccountID  *string   `json:"-"`
	PayoutsEnabled   bool      `json:"payouts_enabled"`
	CreatedAt        time.Time `json:"created_at"`
	UpdatedAt        time.Time `json:"updated_at"`
}

// Rating returns the average rating rounded to one decimal, or 0 when unrated.
func (u *User) Rating() float64 {
	if u.RatingCount == 0 {
		return 0
	}
	return math.Round(float64(u.RatingSum)/float64(u.RatingCount)*10) / 10
}

// PublicProfile is the subset of a user exposed to other members.
type PublicProfile struct {
	ID          uuid.UUID `json:"id"`
	FirstName   string    `json:"first_name"`
	LastInitial string    `json:"last_initial"`
	Bio         string    `json:"bio"`
	AvatarURL   *string   `json:"avatar_url,omitempty"`
	Rating      float64   `json:"rating"`
	RatingCount int       `json:"rating_count"`
	MemberSince time.Time `json:"member_since"`
}

// Public converts a user into its public profile.
func (u *User) Public() PublicProfile {
	return PublicProfileFor(u)
}

// lastInitial returns the first letter of a family name, skipping the Arabic
// definite article "ال" (الحلبي → ح.) so initials stay meaningful.
func lastInitial(lastName string) string {
	r := []rune(strings.TrimSpace(lastName))
	if len(r) > 2 && r[0] == 'ا' && r[1] == 'ل' {
		r = r[2:]
	}
	if len(r) == 0 {
		return ""
	}
	return string(r[0]) + "."
}

// PublicProfileFor converts a user into its public profile.
func PublicProfileFor(u *User) PublicProfile {
	initial := lastInitial(u.LastName)
	return PublicProfile{
		ID:          u.ID,
		FirstName:   u.FirstName,
		LastInitial: initial,
		Bio:         u.Bio,
		AvatarURL:   u.AvatarURL,
		Rating:      u.Rating(),
		RatingCount: u.RatingCount,
		MemberSince: u.CreatedAt,
	}
}

// MeView is the authenticated user's own view of their account.
type MeView struct {
	ID             uuid.UUID `json:"id"`
	Email          string    `json:"email"`
	Phone          *string   `json:"phone,omitempty"`
	FirstName      string    `json:"first_name"`
	LastName       string    `json:"last_name"`
	Bio            string    `json:"bio"`
	AvatarURL      *string   `json:"avatar_url,omitempty"`
	Rating         float64   `json:"rating"`
	RatingCount    int       `json:"rating_count"`
	PayoutsEnabled bool      `json:"payouts_enabled"`
	CreatedAt      time.Time `json:"created_at"`
}

// Me converts a user into the self view.
func (u *User) Me() MeView {
	return MeView{
		ID:             u.ID,
		Email:          u.Email,
		Phone:          u.Phone,
		FirstName:      u.FirstName,
		LastName:       u.LastName,
		Bio:            u.Bio,
		AvatarURL:      u.AvatarURL,
		Rating:         u.Rating(),
		RatingCount:    u.RatingCount,
		PayoutsEnabled: u.PayoutsEnabled,
		CreatedAt:      u.CreatedAt,
	}
}

// RegisterInput is the payload for account creation.
type RegisterInput struct {
	Email     string  `json:"email"`
	Password  string  `json:"password"`
	FirstName string  `json:"first_name"`
	LastName  string  `json:"last_name"`
	Phone     *string `json:"phone,omitempty"`
}

// Normalize trims whitespace and lowercases the email.
func (in *RegisterInput) Normalize() {
	in.Email = strings.ToLower(strings.TrimSpace(in.Email))
	in.FirstName = strings.TrimSpace(in.FirstName)
	in.LastName = strings.TrimSpace(in.LastName)
	if in.Phone != nil {
		p := strings.TrimSpace(*in.Phone)
		if p == "" {
			in.Phone = nil
		} else {
			in.Phone = &p
		}
	}
}

// Validate checks registration input.
func (in *RegisterInput) Validate() error {
	if _, err := mail.ParseAddress(in.Email); err != nil || !strings.Contains(in.Email, "@") {
		return NewValidationError("email", "must be a valid email address")
	}
	if len(in.Password) < 8 || len(in.Password) > 72 {
		return NewValidationError("password", "must be between 8 and 72 characters")
	}
	if in.FirstName == "" || len(in.FirstName) > 80 {
		return NewValidationError("first_name", "is required (max 80 characters)")
	}
	if in.LastName == "" || len(in.LastName) > 80 {
		return NewValidationError("last_name", "is required (max 80 characters)")
	}
	return nil
}

// UpdateProfileInput holds optional profile changes.
type UpdateProfileInput struct {
	FirstName *string `json:"first_name,omitempty"`
	LastName  *string `json:"last_name,omitempty"`
	Bio       *string `json:"bio,omitempty"`
	Phone     *string `json:"phone,omitempty"`
	AvatarURL *string `json:"avatar_url,omitempty"`
}

// Validate checks the profile update payload.
func (in *UpdateProfileInput) Validate() error {
	if in.FirstName != nil {
		v := strings.TrimSpace(*in.FirstName)
		if v == "" || len(v) > 80 {
			return NewValidationError("first_name", "must be 1-80 characters")
		}
		in.FirstName = &v
	}
	if in.LastName != nil {
		v := strings.TrimSpace(*in.LastName)
		if v == "" || len(v) > 80 {
			return NewValidationError("last_name", "must be 1-80 characters")
		}
		in.LastName = &v
	}
	if in.Bio != nil && len(*in.Bio) > 1000 {
		return NewValidationError("bio", "must be at most 1000 characters")
	}
	if in.AvatarURL != nil && *in.AvatarURL != "" && !strings.HasPrefix(*in.AvatarURL, "https://") {
		return NewValidationError("avatar_url", "must be an https URL")
	}
	return nil
}
