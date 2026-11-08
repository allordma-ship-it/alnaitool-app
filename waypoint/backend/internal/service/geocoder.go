package service

import (
	"context"
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/cache"
	"github.com/allordma-ship-it/alnaitool-app/waypoint/backend/internal/domain"
)

// GeocodeResult is a place suggestion.
type GeocodeResult struct {
	Name     string        `json:"name"`
	Label    string        `json:"label"`
	Location domain.LatLng `json:"location"`
	Kind     string        `json:"kind"`
}

// Geocoder resolves free-text place queries using a Photon (OSM) server, with Redis caching.
type Geocoder struct {
	bbox      string // minLon,minLat,maxLon,maxLat restricting results to the market
	baseURL   string
	userAgent string
	http      *http.Client
	cache     *cache.Cache
	log       *slog.Logger
}

// NewGeocoder builds the geocoder.
func NewGeocoder(baseURL, userAgent, bbox string, c *cache.Cache, log *slog.Logger) *Geocoder {
	return &Geocoder{bbox: bbox, baseURL: baseURL, userAgent: userAgent, http: &http.Client{Timeout: 6 * time.Second}, cache: c, log: log}
}

type photonResponse struct {
	Features []struct {
		Geometry struct {
			Coordinates []float64 `json:"coordinates"`
		} `json:"geometry"`
		Properties struct {
			Name        string `json:"name"`
			Street      string `json:"street"`
			HouseNumber string `json:"housenumber"`
			City        string `json:"city"`
			Postcode    string `json:"postcode"`
			State       string `json:"state"`
			Country     string `json:"country"`
			OSMValue    string `json:"osm_value"`
			Type        string `json:"type"`
		} `json:"properties"`
	} `json:"features"`
}

// Search returns up to `limit` suggestions, biased towards `near` when provided.
func (g *Geocoder) Search(ctx context.Context, query string, near *domain.LatLng, lang string, limit int) ([]GeocodeResult, error) {
	query = strings.TrimSpace(query)
	if len([]rune(query)) < 2 {
		return []GeocodeResult{}, nil
	}
	if len(query) > 200 {
		return nil, domain.NewValidationError("q", "is too long")
	}
	if limit <= 0 || limit > 15 {
		limit = 8
	}
	params := url.Values{}
	params.Set("q", query)
	params.Set("limit", strconv.Itoa(limit))
	// Photon translates names only into these languages. For any other
	// language (e.g. Arabic) the parameter is omitted so places come back
	// with their local names ("Stuttgart", "München") rather than English.
	switch lang {
	case "de", "en", "fr", "it":
		params.Set("lang", lang)
	}
	if g.bbox != "" {
		params.Set("bbox", g.bbox)
	}
	if near != nil && near.Valid() {
		params.Set("lat", strconv.FormatFloat(near.Lat, 'f', 3, 64))
		params.Set("lon", strconv.FormatFloat(near.Lng, 'f', 3, 64))
	}

	sum := sha1.Sum([]byte(params.Encode()))
	key := "geo:v1:" + hex.EncodeToString(sum[:])
	var cached []GeocodeResult
	if err := g.cache.GetJSON(ctx, key, &cached); err == nil {
		return cached, nil
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, g.baseURL+"/api/?"+params.Encode(), nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", g.userAgent)
	req.Header.Set("Accept", "application/json")
	resp, err := g.http.Do(req)
	if err != nil {
		return nil, fmt.Errorf("%w: geocoder: %v", domain.ErrUpstream, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("%w: geocoder status %d", domain.ErrUpstream, resp.StatusCode)
	}
	var pr photonResponse
	if err := json.NewDecoder(io.LimitReader(resp.Body, 4<<20)).Decode(&pr); err != nil {
		return nil, fmt.Errorf("%w: geocoder decode: %v", domain.ErrUpstream, err)
	}

	out := make([]GeocodeResult, 0, len(pr.Features))
	seen := map[string]bool{}
	for _, f := range pr.Features {
		if len(f.Geometry.Coordinates) < 2 {
			continue
		}
		p := f.Properties
		name := p.Name
		if name == "" {
			name = strings.TrimSpace(p.Street + " " + p.HouseNumber)
		}
		if name == "" {
			name = p.City
		}
		if name == "" {
			continue
		}
		parts := []string{name}
		if p.Street != "" && p.Name != "" {
			parts = append(parts, strings.TrimSpace(p.Street+" "+p.HouseNumber))
		}
		if p.City != "" && p.City != name {
			parts = append(parts, strings.TrimSpace(p.Postcode+" "+p.City))
		}
		if p.Country != "" {
			parts = append(parts, p.Country)
		}
		label := strings.Join(parts, ", ")
		if seen[label] {
			continue
		}
		seen[label] = true
		kind := p.Type
		if kind == "" {
			kind = p.OSMValue
		}
		out = append(out, GeocodeResult{
			Name:     name,
			Label:    label,
			Location: domain.LatLng{Lat: f.Geometry.Coordinates[1], Lng: f.Geometry.Coordinates[0]},
			Kind:     kind,
		})
	}
	if err := g.cache.SetJSON(ctx, key, out, 24*time.Hour); err != nil {
		g.log.Warn("geocode cache store failed", "err", err)
	}
	return out, nil
}
