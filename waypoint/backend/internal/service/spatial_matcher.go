package service

import (
	"context"
	"fmt"
	"log/slog"
	"math"
	"sort"
	"time"

	"github.com/google/uuid"

	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/cache"
	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/domain"
	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/repository/postgres"
)

// SearchSort selects the ordering of search results.
type SearchSort string

const (
	SortDeparture SearchSort = "departure"
	SortPrice     SearchSort = "price"
	SortProximity SearchSort = "proximity"
)

// SearchRequest is the matcher input: query, time window, ordering and viewer.
type SearchRequest struct {
	Query      domain.RideSearchQuery
	WindowFrom time.Time
	WindowTo   time.Time
	Sort       SearchSort
	ViewerID   uuid.UUID
}

// SpatialMatcher finds rides whose route can serve a passenger's trip as a sub-segment.
//
// Algorithm:
//  1. PostGIS prefilter: ST_DWithin(route, point, radius) for both endpoints on the
//     GiST-indexed geography route, with ST_LineLocatePoint ensuring the pickup
//     precedes the dropoff along the driver's direction of travel.
//  2. Segment-aware capacity: seats are free only if no point of the passenger's
//     segment is over-subscribed by overlapping bookings.
//  3. Pro-rated pricing by route fraction travelled.
//  4. Ranking by departure, price or proximity (walking detour to the meeting points).
type SpatialMatcher struct {
	store   *postgres.Store
	cache   *cache.Cache
	pricing Pricing
	log     *slog.Logger
}

// NewSpatialMatcher builds the matcher.
func NewSpatialMatcher(store *postgres.Store, c *cache.Cache, pricing Pricing, log *slog.Logger) *SpatialMatcher {
	return &SpatialMatcher{store: store, cache: c, pricing: pricing, log: log}
}

const searchCacheTTL = 45 * time.Second

// Search returns ranked ride matches.
func (m *SpatialMatcher) Search(ctx context.Context, req SearchRequest) ([]domain.RideMatch, error) {
	if err := req.Query.Validate(); err != nil {
		return nil, err
	}
	now := time.Now()
	if req.WindowFrom.Before(now) {
		req.WindowFrom = now
	}
	if !req.WindowTo.After(req.WindowFrom) {
		return []domain.RideMatch{}, nil
	}
	switch req.Sort {
	case SortDeparture, SortPrice, SortProximity:
	default:
		req.Sort = SortDeparture
	}

	cacheKey := m.cacheKey(ctx, req)
	if cacheKey != "" {
		var cached []domain.RideMatch
		if err := m.cache.GetJSON(ctx, cacheKey, &cached); err == nil {
			return cached, nil
		}
	}

	q := req.Query
	candidates, err := m.store.Rides.FindAlongRoute(ctx, postgres.SpatialSearchParams{
		Origin:        q.Origin,
		Destination:   q.Destination,
		PickupFrom:    req.WindowFrom,
		PickupTo:      req.WindowTo,
		MinSeats:      q.Seats,
		RadiusMeters:  q.RadiusMeters,
		ExcludeDriver: req.ViewerID,
		Limit:         200,
	})
	if err != nil {
		return nil, err
	}
	if len(candidates) == 0 {
		m.storeCache(ctx, cacheKey, []domain.RideMatch{})
		return []domain.RideMatch{}, nil
	}

	rideIDs := make([]uuid.UUID, 0, len(candidates))
	driverIDs := make([]uuid.UUID, 0, len(candidates))
	for _, c := range candidates {
		rideIDs = append(rideIDs, c.Ride.ID)
		driverIDs = append(driverIDs, c.Ride.DriverID)
	}
	intervals, err := m.store.Bookings.ActiveIntervals(ctx, rideIDs)
	if err != nil {
		return nil, err
	}
	drivers, err := m.store.Users.GetMany(ctx, driverIDs)
	if err != nil {
		return nil, err
	}

	matches := make([]domain.RideMatch, 0, len(candidates))
	for _, c := range candidates {
		free := AvailableSeats(c.Ride.SeatsTotal, intervals[c.Ride.ID], c.PickupFraction, c.DropoffFraction)
		if free < q.Seats {
			continue
		}
		driver, ok := drivers[c.Ride.DriverID]
		if !ok {
			continue
		}
		price := m.pricing.SegmentPrice(c.Ride.PricePerSeatCents, c.PickupFraction, c.DropoffFraction)
		match := domain.RideMatch{
			Ride:   c.Ride,
			Driver: driver.Public(),
			Segment: domain.SegmentMatch{
				Pickup:           c.Pickup,
				Dropoff:          c.Dropoff,
				PickupFraction:   c.PickupFraction,
				DropoffFraction:  c.DropoffFraction,
				PickupDistanceM:  math.Round(c.PickupDistanceM),
				DropoffDistanceM: math.Round(c.DropoffDistanceM),
				PickupETA:        c.Ride.ETAAt(c.PickupFraction).Truncate(time.Minute),
				DropoffETA:       c.Ride.ETAAt(c.DropoffFraction).Truncate(time.Minute),
			},
			SeatsAvailable: free,
			PricePerSeat:   price,
		}
		match.Score = score(match, q.RadiusMeters)
		matches = append(matches, match)
	}

	sortMatches(matches, req.Sort)
	if len(matches) > q.Limit {
		matches = matches[:q.Limit]
	}
	m.storeCache(ctx, cacheKey, matches)
	return matches, nil
}

// score rates a match in [0, 100]; higher is better. It rewards short walking
// distances to the meeting points, good driver ratings and instant booking.
func score(mt domain.RideMatch, radius float64) float64 {
	proximity := 1 - (mt.Segment.PickupDistanceM+mt.Segment.DropoffDistanceM)/(2*radius)
	if proximity < 0 {
		proximity = 0
	}
	rating := 0.6 // neutral prior for new drivers
	if mt.Driver.RatingCount > 0 {
		rating = mt.Driver.Rating / 5
	}
	instant := 0.0
	if mt.Ride.Preferences.InstantBooking {
		instant = 1
	}
	s := 65*proximity + 25*rating + 10*instant
	return math.Round(s*10) / 10
}

func sortMatches(ms []domain.RideMatch, by SearchSort) {
	sort.SliceStable(ms, func(i, j int) bool {
		a, b := ms[i], ms[j]
		switch by {
		case SortPrice:
			if a.PricePerSeat != b.PricePerSeat {
				return a.PricePerSeat < b.PricePerSeat
			}
		case SortProximity:
			if a.Score != b.Score {
				return a.Score > b.Score
			}
		}
		if !a.Segment.PickupETA.Equal(b.Segment.PickupETA) {
			return a.Segment.PickupETA.Before(b.Segment.PickupETA)
		}
		return a.Score > b.Score
	})
}

func (m *SpatialMatcher) cacheKey(ctx context.Context, req SearchRequest) string {
	gen, err := m.cache.Generation(ctx, "rides")
	if err != nil {
		m.log.Warn("search cache generation unavailable", "err", err)
		return ""
	}
	q := req.Query
	// Coordinates rounded to ~100m so nearby searches share entries; window to the minute.
	return fmt.Sprintf("search:v1:%d:%.3f,%.3f:%.3f,%.3f:%d:%d:%d:%d:%s:%d:%s",
		gen, q.Origin.Lat, q.Origin.Lng, q.Destination.Lat, q.Destination.Lng,
		req.WindowFrom.Unix()/60, req.WindowTo.Unix()/60, q.Seats, int(q.RadiusMeters), req.Sort, q.Limit, req.ViewerID)
}

func (m *SpatialMatcher) storeCache(ctx context.Context, key string, v []domain.RideMatch) {
	if key == "" {
		return
	}
	if err := m.cache.SetJSON(ctx, key, v, searchCacheTTL); err != nil {
		m.log.Warn("search cache store failed", "err", err)
	}
}

// InvalidateSearch bumps the search generation so cached results are dropped.
func InvalidateSearch(ctx context.Context, c *cache.Cache, log *slog.Logger) {
	if err := c.Bump(ctx, "rides"); err != nil {
		log.Warn("search cache invalidation failed", "err", err)
	}
}
