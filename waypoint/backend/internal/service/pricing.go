package service

import (
	"math"

	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/domain"
)

// Pricing computes segment prices, platform fees and price suggestions.
// All amounts are in minor units of Currency (e.g. piastres for SYP, cents for EUR).
type Pricing struct {
	Currency      string
	FeeBps        int // service fee in basis points of the seat price
	FeeFixedCents int // fixed service fee per booking
	CentsPerKm    int // reference cost-sharing rate per seat-kilometre
	StepMinor     int // prices are rounded to multiples of this (e.g. 50 SYP = 5000)
	MinSeatMinor  int // lowest price for any seat or segment
}

const fractionEpsilon = 1e-9

func (p Pricing) step() int {
	if p.StepMinor <= 0 {
		return 1
	}
	return p.StepMinor
}

func (p Pricing) round(v float64) int {
	s := p.step()
	return int(math.Round(v/float64(s))) * s
}

// SuggestedPrice returns the recommended per-seat price and the allowed bounds
// for a route of the given length, following the cost-sharing principle.
func (p Pricing) SuggestedPrice(distanceM int) (suggested, minPrice, maxPrice int) {
	km := float64(distanceM) / 1000
	base := km * float64(p.CentsPerKm)
	suggested = max(p.round(base), p.MinSeatMinor)
	minPrice = max(p.round(base*0.6), p.step(), p.MinSeatMinor/2)
	maxPrice = max(p.round(base*1.4), suggested)
	return suggested, minPrice, maxPrice
}

// SegmentPrice pro-rates the full-route seat price to the fraction of the route travelled.
func (p Pricing) SegmentPrice(fullPriceCents int, pickupFraction, dropoffFraction float64) int {
	frac := dropoffFraction - pickupFraction
	if frac >= 0.97 {
		return fullPriceCents
	}
	if frac < 0 {
		frac = 0
	}
	price := p.round(float64(fullPriceCents) * frac)
	floor := min(p.MinSeatMinor, fullPriceCents)
	return min(max(price, floor), fullPriceCents)
}

// ServiceFee is the platform fee charged to the passenger on top of the seat price.
func (p Pricing) ServiceFee(priceCents int) int {
	return int(math.Round(float64(priceCents)*float64(p.FeeBps)/10000)) + p.FeeFixedCents
}

// AvailableSeats returns how many seats are free over the whole segment
// [from, to) given existing bookings. Capacity is segment-aware: two passengers
// on disjoint parts of the route can occupy the same seat.
func AvailableSeats(total int, bookings []domain.SeatInterval, from, to float64) int {
	// Occupancy is piecewise constant and only increases at booking pickups,
	// so the maximum over [from, to) is attained at `from` or at a pickup inside it.
	checkpoints := []float64{from}
	for _, b := range bookings {
		if b.PickupFraction > from && b.PickupFraction < to-fractionEpsilon {
			checkpoints = append(checkpoints, b.PickupFraction)
		}
	}
	maxOccupied := 0
	for _, x := range checkpoints {
		occupied := 0
		for _, b := range bookings {
			if b.PickupFraction <= x+fractionEpsilon && b.DropoffFraction > x+fractionEpsilon {
				occupied += b.Seats
			}
		}
		maxOccupied = max(maxOccupied, occupied)
	}
	return max(total-maxOccupied, 0)
}
