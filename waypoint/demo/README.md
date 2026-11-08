# Wanees (ونيس) — app simulation

`wanees-simulation.mp4` and `wanees-storyboard.png` show the real app driven by a
script against a real backend:

* the Flutter app compiled for the web, in a phone-sized browser (Arabic, RTL);
* the Go API with PostgreSQL/PostGIS and Redis;
* real road routes from OpenStreetMap (OSRM) and real Arabic place names (Photon);
* Syrian market settings: SYP prices, cash payment, Damascus time.

Flow: log in → search Homs → Hama → results (Damascus → Aleppo rides passing through)
→ ride details with the route map → instant booking with cash payment → driver
dashboard accepting requests → offering a Latakia → Aleppo ride with a suggested
price → profile and language switch.

## Re-run

```bash
# 1. API (any database / Redis; OSRM + Photon reachable from the machine)
ROUTING_BASE_URL=https://router.project-osrm.org  ... go run ./cmd/server
# 2. Demo data (drivers, rides, requests, ratings)
WANEES_API=http://localhost:8080 DEMO_PSQL_URL=postgres://... python3 demo/seed_demo.py
# 3. Web build of the app, served on :5555
cd mobile && flutter create --platforms=web . && \
  flutter build web --no-web-resources-cdn --dart-define=API_BASE_URL=http://localhost:8080
python3 -m http.server 5555 --directory build/web
# 4. Drive it (Playwright)
APP_URL=http://localhost:5555/ OUT=demo-out node demo/simulate.js
```

Demo login: `ahmad@wanees.demo` / `wanees2026`.
