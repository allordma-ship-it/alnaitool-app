package service

import (
	"math"

	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/domain"
)

// Pricing computes segment prices, platform fees and price suggestions.
type Pricing struct {
	FeeBps        int // service fee in basis points of the seat price
	FeeFixedCents int // fixed service fee per booking
	CentsPerKm    int // reference cost-sharing rate used for suggestions
}

const (
	priceStepCents     = 50
	minSegmentPriceCts = 200
	fractionEpsilon    = 1e-9
)

func roundToStep(cents float64, step int) int {
	return int(math.Round(cents/float64(step))) * step
}

// SuggestedPrice returns the recommended per-seat price and the allowed bounds
// for a route of the given length, following the cost-sharing principle.
func (p Pricing) SuggestedPrice(distanceM int) (suggested, minPrice, maxPrice int) {
	km := float64(distanceM) / 1000
	base := km * float64(p.CentsPerKm)
	suggested = roundToStep(base, priceStepCents)
	if suggested < 200 {
		suggested = 200
	}
	minPrice = roundToStep(base*0.6, priceStepCents)
	if minPrice < 100 {
		minPrice = 100
	}
	maxPrice = roundToStep(base*1.4, priceStepCents)
	if maxPrice < suggested {
		maxPrice = suggested
	}
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
	price := roundToStep(float64(fullPriceCents)*frac, priceStepCents)
	floor := minSegmentPriceCts
	if fullPriceCents < floor {
		floor = fullPriceCents
	}
	if price < floor {
		price = floor
	}
	if price > fullPriceCents {
		price = fullPriceCents
	}
	return price
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
		if occupied > maxOccupied {
			maxOccupied = occupied
		}
	}
	free := total - maxOccupied
	if free < 0 {
		return 0
	}
	return free
}
