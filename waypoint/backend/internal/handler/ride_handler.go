package handler

import (
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/domain"
	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/middleware"
	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/service"
)

// RideHandler serves ride publishing, search and lifecycle endpoints.
type RideHandler struct {
	rides    *service.RideService
	matcher  *service.SpatialMatcher
	geocoder *service.Geocoder
	log      *slog.Logger
}

// NewRideHandler builds the handler.
func NewRideHandler(rides *service.RideService, matcher *service.SpatialMatcher, geocoder *service.Geocoder, log *slog.Logger) *RideHandler {
	return &RideHandler{rides: rides, matcher: matcher, geocoder: geocoder, log: log}
}

func parseFloat(r *http.Request, key string) (float64, error) {
	v, err := strconv.ParseFloat(r.URL.Query().Get(key), 64)
	if err != nil {
		return 0, domain.NewValidationError(key, "must be a number")
	}
	return v, nil
}

func parseLatLng(r *http.Request, prefix string) (domain.LatLng, error) {
	lat, err := parseFloat(r, prefix+"_lat")
	if err != nil {
		return domain.LatLng{}, err
	}
	lng, err := parseFloat(r, prefix+"_lng")
	if err != nil {
		return domain.LatLng{}, err
	}
	p := domain.LatLng{Lat: lat, Lng: lng}
	if !p.Valid() {
		return p, domain.NewValidationError(prefix, "must be a valid coordinate")
	}
	return p, nil
}

// Search handles GET /v1/rides/search
//
//	?from_lat&from_lng&to_lat&to_lng&date=YYYY-MM-DD&tz=Europe/Berlin&seats=1&radius_m=15000&sort=departure|price|proximity
func (h *RideHandler) Search(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	from, err := parseLatLng(r, "from")
	if err != nil {
		respondError(w, r, h.log, err)
		return
	}
	to, err := parseLatLng(r, "to")
	if err != nil {
		respondError(w, r, h.log, err)
		return
	}
	loc := time.UTC
	if tz := q.Get("tz"); tz != "" {
		if loc, err = time.LoadLocation(tz); err != nil {
			respondError(w, r, h.log, domain.NewValidationError("tz", "must be an IANA time zone"))
			return
		}
	}
	day, err := time.ParseInLocation("2006-01-02", q.Get("date"), loc)
	if err != nil {
		respondError(w, r, h.log, domain.NewValidationError("date", "must be YYYY-MM-DD"))
		return
	}
	seats := 1
	if s := q.Get("seats"); s != "" {
		if seats, err = strconv.Atoi(s); err != nil {
			respondError(w, r, h.log, domain.NewValidationError("seats", "must be an integer"))
			return
		}
	}
	radius := 0.0
	if s := q.Get("radius_m"); s != "" {
		if radius, err = strconv.ParseFloat(s, 64); err != nil {
			respondError(w, r, h.log, domain.NewValidationError("radius_m", "must be a number"))
			return
		}
	}
	viewer, _ := middleware.UserID(r.Context())
	matches, err := h.matcher.Search(r.Context(), service.SearchRequest{
		Query: domain.RideSearchQuery{
			Origin: from, Destination: to, Date: day, Seats: seats, RadiusMeters: radius,
		},
		WindowFrom: day,
		WindowTo:   day.AddDate(0, 0, 1),
		Sort:       service.SearchSort(q.Get("sort")),
		ViewerID:   viewer,
	})
	if err != nil {
		respondError(w, r, h.log, err)
		return
	}
	writeJSON(w, http.StatusOK, list(matches))
}

type previewRequest struct {
	Origin      domain.LatLng `json:"origin"`
	Destination domain.LatLng `json:"destination"`
}

// Preview handles POST /v1/rides/preview.
func (h *RideHandler) Preview(w http.ResponseWriter, r *http.Request) {
	var in previewRequest
	if err := decode(w, r, &in); err != nil {
		respondError(w, r, h.log, err)
		return
	}
	res, err := h.rides.Preview(r.Context(), in.Origin, in.Destination)
	if err != nil {
		respondError(w, r, h.log, err)
		return
	}
	writeJSON(w, http.StatusOK, res)
}

// Publish handles POST /v1/rides.
func (h *RideHandler) Publish(w http.ResponseWriter, r *http.Request) {
	var in domain.PublishRideInput
	if err := decode(w, r, &in); err != nil {
		respondError(w, r, h.log, err)
		return
	}
	ride, err := h.rides.Publish(r.Context(), mustUser(r), in)
	if err != nil {
		respondError(w, r, h.log, err)
		return
	}
	writeJSON(w, http.StatusCreated, ride)
}

// Get handles GET /v1/rides/{rideID}.
func (h *RideHandler) Get(w http.ResponseWriter, r *http.Request) {
	id, err := pathUUID(r, "rideID")
	if err != nil {
		respondError(w, r, h.log, err)
		return
	}
	ride, err := h.rides.Get(r.Context(), id)
	if err != nil {
		respondError(w, r, h.log, err)
		return
	}
	writeJSON(w, http.StatusOK, ride)
}

// ListMine handles GET /v1/me/rides?scope=upcoming|past.
func (h *RideHandler) ListMine(w http.ResponseWriter, r *http.Request) {
	upcoming := r.URL.Query().Get("scope") != "past"
	rides, err := h.rides.ListMine(r.Context(), mustUser(r), upcoming)
	if err != nil {
		respondError(w, r, h.log, err)
		return
	}
	writeJSON(w, http.StatusOK, list(rides))
}

func (h *RideHandler) lifecycle(action func(r *http.Request) error) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if err := action(r); err != nil {
			respondError(w, r, h.log, err)
			return
		}
		id, _ := pathUUID(r, "rideID")
		ride, err := h.rides.Get(r.Context(), id)
		if err != nil {
			respondError(w, r, h.log, err)
			return
		}
		writeJSON(w, http.StatusOK, ride)
	}
}

// Cancel handles POST /v1/rides/{rideID}/cancel.
func (h *RideHandler) Cancel(w http.ResponseWriter, r *http.Request) {
	h.lifecycle(func(r *http.Request) error {
		id, err := pathUUID(r, "rideID")
		if err != nil {
			return err
		}
		return h.rides.Cancel(r.Context(), mustUser(r), id)
	})(w, r)
}

// Start handles POST /v1/rides/{rideID}/start.
func (h *RideHandler) Start(w http.ResponseWriter, r *http.Request) {
	h.lifecycle(func(r *http.Request) error {
		id, err := pathUUID(r, "rideID")
		if err != nil {
			return err
		}
		return h.rides.Start(r.Context(), mustUser(r), id)
	})(w, r)
}

// Complete handles POST /v1/rides/{rideID}/complete.
func (h *RideHandler) Complete(w http.ResponseWriter, r *http.Request) {
	h.lifecycle(func(r *http.Request) error {
		id, err := pathUUID(r, "rideID")
		if err != nil {
			return err
		}
		return h.rides.Complete(r.Context(), mustUser(r), id)
	})(w, r)
}

// Geocode handles GET /v1/geocode?q=...&lat=&lng=&lang=.
func (h *RideHandler) Geocode(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	var near *domain.LatLng
	if q.Get("lat") != "" && q.Get("lng") != "" {
		lat, err1 := parseFloat(r, "lat")
		lng, err2 := parseFloat(r, "lng")
		if err1 == nil && err2 == nil {
			near = &domain.LatLng{Lat: lat, Lng: lng}
		}
	}
	limit, _ := strconv.Atoi(q.Get("limit"))
	res, err := h.geocoder.Search(r.Context(), q.Get("q"), near, q.Get("lang"), limit)
	if err != nil {
		respondError(w, r, h.log, err)
		return
	}
	writeJSON(w, http.StatusOK, list(res))
}
