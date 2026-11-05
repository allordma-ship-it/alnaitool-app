# AuraStream – IPTV Media Player Ecosystem

Production-grade IPTV player for **Android TV & mobile** plus a **cloud backend / web portal**
for remote playlist management via MAC address + 6-digit Device Key.

```
iptv-android-ecosystem/
├── android-tv-client/   Kotlin · Jetpack Compose for TV · Media3/ExoPlayer · Room · Retrofit/OkHttp
└── web-backend/         Node.js · Fastify 5 · PostgreSQL (SQLite for dev) · Tailwind portal
```

## How it fits together

```
 ┌──────────── Android TV ────────────┐        ┌────────── web-backend ──────────┐        ┌── phone / browser ──┐
 │ 1. POST /api/v1/device/handshake   │ ─────► │ registers MAC, 6-digit key,     │        │                     │
 │    (MAC or ANDROID_ID pseudo-MAC)  │ ◄───── │ 7-day trial, device secret      │        │                     │
 │ 2. shows MAC + Key + QR code ──────┼────────┼─────────────────────────────────┼──────► │ 3. scans QR → portal│
 │                                    │        │ POST /api/v1/device/verify  ◄───┼─────── │    auto-fills & logs│
 │                                    │        │ POST /api/v1/playlists      ◄───┼─────── │ 4. M3U URL / file / │
 │ 5. GET /api/v1/playlists/sync ─────┼──────► │ (AES-GCM encrypted credentials) │        │    Xtream login     │
 │ 6. streams M3U / Xtream into Room  │        └─────────────────────────────────┘        └─────────────────────┘
 │ 7. Live TV · Movies · Series · EPG │
 └────────────────────────────────────┘
```

---

## web-backend

```bash
cd iptv-android-ecosystem/web-backend
npm install
npm test            # 12 API tests (node:test, in-memory SQLite)
npm run dev         # http://localhost:8080 – SQLite at ./data/dev.sqlite
```

Production (PostgreSQL):

```bash
APP_SECRET=$(openssl rand -hex 32) PUBLIC_PORTAL_URL=https://tv.example.com docker compose up -d --build
```

See `.env.example` for all settings. Node ≥ 22.13 is required (built-in `node:sqlite` for development).

### API

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/api/v1/device/handshake` | – | Register / refresh a TV. Returns MAC, Device Key, licence, `pairingUrl` (QR) and – on first contact only – the `deviceSecret`. `{ reset: true }` re-pairs a MAC whose secret was lost (rotates key, purges playlists). |
| GET | `/api/v1/device/status` | device | Licence status |
| POST | `/api/v1/device/verify` | – | Portal login with MAC + Device Key → 30-min session token |
| GET | `/api/v1/playlists` | portal | List playlists (passwords masked) |
| POST | `/api/v1/playlists` | portal | Add `m3u_url` or `xtream` playlist |
| POST | `/api/v1/playlists/upload` | portal | Upload `.m3u/.m3u8` (multipart, validated) |
| DELETE | `/api/v1/playlists/:id` | portal | Remove playlist |
| GET | `/api/v1/playlists/sync` | device | Licence + playlist descriptors (402 when expired) |
| GET | `/api/v1/playlists/:id/content` | device | Raw content of an uploaded playlist |
| POST | `/api/v1/admin/devices/:mac/license` | admin | Activate / extend / expire a device (only when `ADMIN_TOKEN` is set) |

*device* = `X-Device-Mac` + `X-Device-Secret` headers · *portal* = `Authorization: Bearer <token>` · *admin* = `Authorization: Bearer $ADMIN_TOKEN`

### Security

- Rate limiting (global 120/min/IP, stricter on handshake / verify / writes) and **per-device brute-force lockout** (8 wrong keys → 15 min).
- The device secret is shown to the TV once and stored only as SHA-256; portal tokens are HMAC-signed and bound to the current Device Key, so a re-pair invalidates all sessions.
- Xtream credentials are encrypted at rest with **AES-256-GCM**; the portal never receives passwords back.
- JSON-schema validation with `additionalProperties: false`, control/bidi-character stripping, http(s)-only URLs, multipart size limits, Helmet CSP, uniform error envelope (no stack traces).

---

## android-tv-client

Open `iptv-android-ecosystem/android-tv-client` in Android Studio (Ladybug or newer, JDK 17+), or:

```bash
cd iptv-android-ecosystem/android-tv-client
./gradlew assembleDebug -PauraApiBaseUrl=http://<backend-host>:8080/
./gradlew testDebugUnitTest
```

`auraApiBaseUrl` defaults to `http://10.0.2.2:8080/` (host machine from the Android emulator).
**`PUBLIC_PORTAL_URL` on the backend should be the public URL users open on their phone; the app's
`auraApiBaseUrl` must point at the same backend.**

| Area | Implementation |
|---|---|
| Pairing | `core/DeviceIdentity` (eth0/wlan0 MAC → ANDROID_ID-derived locally-administered pseudo-MAC, persisted), `DeviceCredentialStore` (secret encrypted with an Android-Keystore AES-GCM key), QR code drawn as vector from ZXing's bit matrix |
| Import | `M3uStreamParser` – single-pass, line-streamed parser (60 000 entries < 1 s in the JVM test), batched Room inserts on `Dispatchers.IO`; `XtreamApiClient` streams `get_live_streams/get_vod_streams/get_series` with Gson `JsonReader`; `XmltvParser` streams (gzip-aware) XMLTV and keeps a 39-hour window |
| Storage | Room (`channels`, `categories`, `epg`, `episodes`), WAL mode, deterministic stream ids so favourites / locks / resume points survive re-imports |
| Player | `Media3PlayerAdapter` – shared ExoPlayer (mini-player → fullscreen without re-buffering), OkHttp data source with per-stream User-Agent/Referer, `DefaultTrackSelector` audio & subtitle switching (embedded, CEA-608/708, external SRT/VTT/SSA/TTML), FIT/FILL/ZOOM |
| Recovery | `StreamRecoveryManager` – stage 1 reconnect (timeouts/5xx) → stage 2 MPEG-TS ⇄ HLS URL rewrite (403/404/parse errors) → stage 3 software-decoder fallback → give up with retry UI; resets after 15 s of stable playback |
| UI | Compose for TV (`tv-material3`): neon-cyan focus ring + scale, D-pad first but touch-friendly, adaptive layouts for phones, full Arabic translation with automatic RTL mirroring, per-app language switch, parental PIN per category |

Unit tests (`app/src/test`) cover the parser, the TS/HLS URL rules, the recovery policy and the
import classification – all plain JVM code.

> **Build note:** this repository was written in an environment without access to Google's Maven
> repository, so the Android module itself has not been compiled there. The pure-Kotlin parts and
> their unit tests were compiled and run on the JVM (22/22 passing); please run
> `./gradlew assembleDebug` once in Android Studio and report anything the compiler flags.
