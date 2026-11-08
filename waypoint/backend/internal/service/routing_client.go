package service

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/domain"
)

// Route is a computed driving route.
type Route struct {
	Points    []domain.LatLng
	DistanceM int
	DurationS int
}

// RoutingClient computes driving routes between waypoints.
type RoutingClient interface {
	Route(ctx context.Context, waypoints []domain.LatLng) (*Route, error)
}

// NewRoutingClient builds the configured routing backend (osrm or valhalla).
func NewRoutingClient(provider, baseURL string, timeout time.Duration) (RoutingClient, error) {
	hc := &http.Client{Timeout: timeout}
	switch provider {
	case "osrm":
		return &OSRMClient{baseURL: strings.TrimRight(baseURL, "/"), http: hc}, nil
	case "valhalla":
		return &ValhallaClient{baseURL: strings.TrimRight(baseURL, "/"), http: hc}, nil
	default:
		return nil, fmt.Errorf("unknown routing provider %q", provider)
	}
}

// ---------------------------------------------------------------------------
// OSRM
// ---------------------------------------------------------------------------

// OSRMClient talks to an OSRM HTTP server (/route/v1/driving).
type OSRMClient struct {
	baseURL string
	http    *http.Client
}

type osrmResponse struct {
	Code    string `json:"code"`
	Message string `json:"message"`
	Routes  []struct {
		Geometry string  `json:"geometry"`
		Distance float64 `json:"distance"`
		Duration float64 `json:"duration"`
	} `json:"routes"`
}

// Route implements RoutingClient.
func (c *OSRMClient) Route(ctx context.Context, waypoints []domain.LatLng) (*Route, error) {
	if len(waypoints) < 2 {
		return nil, domain.NewValidationError("waypoints", "at least two waypoints are required")
	}
	coords := make([]string, len(waypoints))
	for i, w := range waypoints {
		coords[i] = strconv.FormatFloat(w.Lng, 'f', 6, 64) + "," + strconv.FormatFloat(w.Lat, 'f', 6, 64)
	}
	url := fmt.Sprintf("%s/route/v1/driving/%s?overview=full&geometries=polyline6&alternatives=false&steps=false",
		c.baseURL, strings.Join(coords, ";"))

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, fmt.Errorf("%w: osrm: %v", domain.ErrUpstream, err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 32<<20))
	if err != nil {
		return nil, fmt.Errorf("%w: osrm read: %v", domain.ErrUpstream, err)
	}

	var out osrmResponse
	if err := json.Unmarshal(body, &out); err != nil {
		return nil, fmt.Errorf("%w: osrm decode (status %d): %v", domain.ErrUpstream, resp.StatusCode, err)
	}
	switch out.Code {
	case "Ok":
	case "NoRoute", "NoSegment":
		return nil, domain.NewValidationError("route", "no drivable route found between these places")
	default:
		return nil, fmt.Errorf("%w: osrm %s: %s", domain.ErrUpstream, out.Code, out.Message)
	}
	if len(out.Routes) == 0 {
		return nil, domain.NewValidationError("route", "no drivable route found between these places")
	}
	r := out.Routes[0]
	pts, err := DecodePolyline(r.Geometry, 6)
	if err != nil {
		return nil, fmt.Errorf("%w: osrm geometry: %v", domain.ErrUpstream, err)
	}
	return finalizeRoute(pts, r.Distance, r.Duration)
}

// ---------------------------------------------------------------------------
// Valhalla
// ---------------------------------------------------------------------------

// ValhallaClient talks to a Valhalla HTTP server (/route).
type ValhallaClient struct {
	baseURL string
	http    *http.Client
}

type valhallaLocation struct {
	Lat  float64 `json:"lat"`
	Lon  float64 `json:"lon"`
	Type string  `json:"type"`
}

type valhallaRequest struct {
	Locations      []valhallaLocation `json:"locations"`
	Costing        string             `json:"costing"`
	Units          string             `json:"units"`
	DirectionsType string             `json:"directions_type"`
}

type valhallaResponse struct {
	Trip struct {
		Status        int    `json:"status"`
		StatusMessage string `json:"status_message"`
		Legs          []struct {
			Shape string `json:"shape"`
		} `json:"legs"`
		Summary struct {
			Length float64 `json:"length"` // kilometers
			Time   float64 `json:"time"`   // seconds
		} `json:"summary"`
	} `json:"trip"`
	ErrorCode int    `json:"error_code"`
	Error     string `json:"error"`
}

// Route implements RoutingClient.
func (c *ValhallaClient) Route(ctx context.Context, waypoints []domain.LatLng) (*Route, error) {
	if len(waypoints) < 2 {
		return nil, domain.NewValidationError("waypoints", "at least two waypoints are required")
	}
	reqBody := valhallaRequest{Costing: "auto", Units: "kilometers", DirectionsType: "none"}
	for i, w := range waypoints {
		t := "break"
		if i > 0 && i < len(waypoints)-1 {
			t = "through"
		}
		reqBody.Locations = append(reqBody.Locations, valhallaLocation{Lat: w.Lat, Lon: w.Lng, Type: t})
	}
	payload, err := json.Marshal(reqBody)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/route", bytes.NewReader(payload))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, fmt.Errorf("%w: valhalla: %v", domain.ErrUpstream, err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 32<<20))
	if err != nil {
		return nil, fmt.Errorf("%w: valhalla read: %v", domain.ErrUpstream, err)
	}
	var out valhallaResponse
	if err := json.Unmarshal(body, &out); err != nil {
		return nil, fmt.Errorf("%w: valhalla decode (status %d): %v", domain.ErrUpstream, resp.StatusCode, err)
	}
	if resp.StatusCode == http.StatusBadRequest {
		// 442 = no path, 171 = no suitable edges near location.
		if out.ErrorCode == 442 || out.ErrorCode == 171 {
			return nil, domain.NewValidationError("route", "no drivable route found between these places")
		}
		return nil, domain.NewValidationError("route", out.Error)
	}
	if resp.StatusCode != http.StatusOK || out.Trip.Status != 0 {
		return nil, fmt.Errorf("%w: valhalla status %d: %s%s", domain.ErrUpstream, resp.StatusCode, out.Error, out.Trip.StatusMessage)
	}
	var pts []domain.LatLng
	for _, leg := range out.Trip.Legs {
		legPts, err := DecodePolyline(leg.Shape, 6)
		if err != nil {
			return nil, fmt.Errorf("%w: valhalla shape: %v", domain.ErrUpstream, err)
		}
		if len(pts) > 0 && len(legPts) > 0 && pts[len(pts)-1] == legPts[0] {
			legPts = legPts[1:]
		}
		pts = append(pts, legPts...)
	}
	return finalizeRoute(pts, out.Trip.Summary.Length*1000, out.Trip.Summary.Time)
}

func finalizeRoute(pts []domain.LatLng, distanceM, durationS float64) (*Route, error) {
	pts = dedupeConsecutive(pts)
	if len(pts) < 2 {
		return nil, domain.NewValidationError("route", "route geometry is degenerate")
	}
	if distanceM < 1 || durationS < 1 {
		return nil, domain.NewValidationError("route", "origin and destination are too close")
	}
	return &Route{Points: pts, DistanceM: int(distanceM + 0.5), DurationS: int(durationS + 0.5)}, nil
}

func dedupeConsecutive(pts []domain.LatLng) []domain.LatLng {
	if len(pts) == 0 {
		return pts
	}
	out := pts[:1]
	for _, p := range pts[1:] {
		if p != out[len(out)-1] {
			out = append(out, p)
		}
	}
	return out
}

// DecodePolyline decodes a Google-encoded polyline with the given precision (5 or 6).
func DecodePolyline(encoded string, precision int) ([]domain.LatLng, error) {
	factor := 1.0
	for i := 0; i < precision; i++ {
		factor *= 10
	}
	var pts []domain.LatLng
	var lat, lng int64
	idx := 0
	next := func() (int64, error) {
		var result int64
		var shift uint
		for {
			if idx >= len(encoded) {
				return 0, errors.New("truncated polyline")
			}
			b := int64(encoded[idx]) - 63
			idx++
			if b < 0 {
				return 0, errors.New("invalid polyline character")
			}
			result |= (b & 0x1f) << shift
			shift += 5
			if b < 0x20 {
				break
			}
			if shift > 60 {
				return 0, errors.New("polyline value overflow")
			}
		}
		if result&1 != 0 {
			return ^(result >> 1), nil
		}
		return result >> 1, nil
	}
	for idx < len(encoded) {
		dlat, err := next()
		if err != nil {
			return nil, err
		}
		dlng, err := next()
		if err != nil {
			return nil, err
		}
		lat += dlat
		lng += dlng
		pts = append(pts, domain.LatLng{Lat: float64(lat) / factor, Lng: float64(lng) / factor})
	}
	return pts, nil
}

// RouteGeoJSON renders points as a GeoJSON LineString ([lng, lat] order).
func RouteGeoJSON(points []domain.LatLng) json.RawMessage {
	coords := make([][2]float64, len(points))
	for i, p := range points {
		coords[i] = [2]float64{p.Lng, p.Lat}
	}
	b, _ := json.Marshal(map[string]any{"type": "LineString", "coordinates": coords})
	return b
}
