# WayPoint — Carpooling & Ride-Sharing Platform

WayPoint lets drivers publish long-distance trips and lets passengers book seats on
**any part** of a driver's route. Payments sit in escrow and are released to the
driver after the trip.

| Layer | Technology |
| --- | --- |
| API | Go 1.23, `chi`, `pgx/v5`, `go-redis/v9`, JWT (rotating refresh tokens) |
| Database | PostgreSQL 16 + PostGIS 3.4 (`GEOGRAPHY(LineString, 4326)` routes, GiST indexes) |
| Routing | OSRM (default, self-hosted in compose) or Valhalla over HTTP |
| Geocoding | Photon (OpenStreetMap), proxied and cached by the API |
| Payments | Stripe Connect (manual-capture PaymentIntents, destination charges) or a simulated gateway |
| Mobile | Flutter 3, Riverpod, go_router, Dio, flutter_map (OpenStreetMap), flutter_stripe |
| Infra | docker-compose: API, PostGIS, Redis, OSRM pipeline, Adminer, Stripe CLI |

## Quick start

```bash
cd waypoint
make up            # creates .env (random JWT secret), builds and starts everything
make logs          # follow the API
```

The first `make up` downloads the OpenStreetMap extract in `OSRM_PBF_URL`
(Baden-Württemberg by default) and preprocesses it with the OSRM MLD pipeline. This
takes a few minutes; later starts reuse the data volume. Use `make osrm-reset` after
changing the region.

* API: <http://localhost:8080> (`/healthz`, `/readyz`)
* Adminer: `make up-tools` then <http://localhost:8081> (server `postgres`)
* Stripe webhooks in test mode: set `PAYMENT_PROVIDER=stripe` and the `STRIPE_*`
  keys in `.env`, then `make stripe-listen` and copy the printed `whsec_…` into
  `STRIPE_WEBHOOK_SECRET`.

### Mobile app

```bash
make mobile-bootstrap                         # flutter create + native config + pub get
make mobile-run                               # Android emulator → http://10.0.2.2:8080
make mobile-run API_URL=http://192.168.1.20:8080   # physical device on your LAN
```

`tool/configure_platforms.sh` applies what `flutter_stripe` needs
(`FlutterFragmentActivity`, AppCompat themes, iOS 13), registers the `waypoint://`
deep-link scheme and allows cleartext HTTP to the local API in **debug builds only**.
Map tiles come from `MAP_TILE_URL` (defaults to tile.openstreetmap.org — use your own
tile server or a provider for production traffic).

## Tests

```bash
make test              # Go unit tests (pricing, capacity, polyline, webhook signatures, state machines)
make test-integration  # full HTTP stack against the compose PostGIS + Redis with a fake OSRM
make lint              # gofmt + go vet
make mobile-analyze    # flutter analyze
cd mobile && flutter test
```

The integration test publishes a Stuttgart → Munich ride and verifies partial-route
search (Ulm → Augsburg), direction filtering, segment-aware seat capacity, early
cancellation releasing the hold, completion capturing escrow, and ratings.

## How matching works

`internal/service/spatial_matcher.go` + `internal/repository/postgres/ride_repo.go`

1. **Spatial prefilter (PostGIS)** — `ST_DWithin(route, point, radius)` on the
   GiST-indexed geography for both the passenger's start and destination.
2. **Direction** — `ST_LineLocatePoint` gives each point's fraction along the route;
   the pickup fraction must precede the drop-off fraction.
3. **Timing** — the pickup ETA (`departure + duration × fraction`) must fall on the
   requested day in the passenger's time zone.
4. **Segment-aware capacity** — a seat is free if no point of the passenger's segment
   is over-subscribed, so two passengers on disjoint legs can share one seat.
5. **Pro-rated pricing** — seat price × route fraction travelled, rounded to €0.50,
   with a €2 floor.
6. **Ranking** — by departure, price, or a proximity score (walking distance to the
   meeting points, driver rating, instant booking).

Meeting points are the closest points on the driver's route
(`ST_LineInterpolatePoint`). Results are cached in Redis for 45 s; any publish,
booking or cancellation bumps a generation counter so stale results disappear at once.

## Escrow state machine

`internal/service/escrow_service.go`

```
pending ──authorize──▶ authorized ──capture──▶ captured ──refund──▶ refunded
   │                       │
   ├──fail──▶ failed ──────┤ (passenger retries the card)
   └──────void─────────────┴──void──▶ voided
```

* Booking → a **manual-capture** PaymentIntent with `transfer_data[destination]` set to
  the driver's Connect account and the service fee as `application_fee_amount`.
* Driver rejects or cancels, request expires, or the passenger cancels more than
  `LATE_CANCEL_WINDOW` before pickup → **void** (the hold is released).
* Late passenger cancellation → **partial capture** of `LATE_CANCEL_PERCENT` of the seat
  price plus the fee; the rest is released.
* Ride completed by the driver, trip confirmed by the passenger, or the worker
  auto-completes `AUTO_COMPLETE_AFTER` after arrival → **capture**, which pays the
  driver.
* Card holds expire after about 7 days, so the worker **re-authorizes** holds older
  than 6 days off-session with the saved card and cancels the old hold.
* Every transition is checked against the allowed transitions, made under a row lock,
  sent to Stripe with an idempotency key, and recorded in the `payment_events` audit
  table. Webhooks are signature-checked and deduplicated through `stripe_events`.

`PAYMENT_PROVIDER=simulated` authorizes instantly in memory, so you can run everything
locally without Stripe. Totals ending in **13 cents** are declined, which lets you test
the failure path. Production refuses to start with the simulated provider.

## Booking lifecycle

```
payment_pending ──hold authorized──▶ confirmed (instant booking)
                                  └─▶ requested ──accept──▶ confirmed ──trip──▶ completed
requested ──reject──▶ rejected        any active ──cancel──▶ cancelled_by_passenger / _driver
payment_pending / requested ──timeout──▶ expired
```

Seats are allocated inside a transaction holding `SELECT … FOR UPDATE` on the ride
row, and a partial unique index allows only one live booking per passenger per ride.

## API overview (`/v1`)

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| POST | `/auth/register`, `/auth/login`, `/auth/refresh` | – | Sessions (rate-limited 10/min) |
| POST | `/auth/logout` | ✓ | Revoke refresh token(s) |
| GET/PATCH | `/me` | ✓ | Own profile |
| POST | `/me/payouts/onboarding`, `/me/payouts/refresh` | ✓ | Stripe Connect Express onboarding |
| GET | `/me/rides?scope=upcoming\|past`, `/me/bookings` | ✓ | Driver and passenger dashboards |
| GET | `/geocode?q=` | opt. | Place autocomplete |
| GET | `/rides/search?from_lat&from_lng&to_lat&to_lng&date&tz&seats&radius_m&sort` | opt. | Spatial search |
| GET | `/rides/{id}` | opt. | Ride, route GeoJSON, driver, free seats |
| POST | `/rides/preview` | ✓ | Route + suggested price |
| POST | `/rides` | ✓ | Publish |
| POST | `/rides/{id}/start` · `/complete` · `/cancel` | ✓ driver | Ride lifecycle |
| GET/POST | `/rides/{id}/bookings` | ✓ | List (driver) / create (passenger) |
| GET | `/bookings/{id}` | ✓ | Booking with payment state |
| POST | `/bookings/{id}/accept` · `/reject` · `/driver-cancel` | ✓ driver | Manage requests |
| POST | `/bookings/{id}/cancel` · `/confirm-trip` · `/payment/sync` | ✓ passenger | Passenger actions |
| POST | `/bookings/{id}/review` | ✓ | Rate the other party |
| POST | `/webhooks/stripe` | signature | Stripe events |

Errors use a single shape:
`{"error": {"code": "validation_failed", "message": "...", "field": "seats", "request_id": "..."}}`.

## Repository layout

```
waypoint/
├── docker-compose.yml   Makefile   .env.example
├── backend/
│   ├── cmd/server/main.go                   wiring, graceful shutdown, healthcheck
│   └── internal/
│       ├── config/                          env configuration + validation
│       ├── domain/                          entities, state machines, validation
│       ├── handler/                         HTTP handlers, router, webhooks (+ integration test)
│       ├── middleware/                      auth, Redis rate limit, logging, CORS
│       ├── cache/                           Redis: tokens, cache, sliding window, locks
│       ├── repository/postgres/             pgx repositories, embedded migrations
│       └── service/                         matcher, escrow, routing, pricing, worker
└── mobile/lib/
    ├── core/        network (Dio + refresh), theme, models, map + place widgets, payments
    └── features/    auth · search · publish · bookings · profile  (domain / data / presentation)
```
