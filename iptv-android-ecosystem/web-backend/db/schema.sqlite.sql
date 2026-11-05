-- AuraStream backend schema (SQLite, development only). Mirrors db/schema.sql.
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS devices (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    mac_address         TEXT    NOT NULL UNIQUE,
    device_key          TEXT    NOT NULL,
    status              TEXT    NOT NULL DEFAULT 'trial'
                        CHECK (status IN ('trial', 'active', 'expired')),
    trial_ends_at       TEXT    NOT NULL,
    created_at          TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    device_secret_hash  TEXT    NOT NULL,
    license_ends_at     TEXT,
    model               TEXT,
    app_version         TEXT,
    last_seen_at        TEXT,
    failed_attempts     INTEGER NOT NULL DEFAULT 0,
    locked_until        TEXT
);

CREATE TABLE IF NOT EXISTS playlists (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    device_mac    TEXT    NOT NULL REFERENCES devices (mac_address) ON DELETE CASCADE ON UPDATE CASCADE,
    name          TEXT    NOT NULL,
    source_type   TEXT    NOT NULL CHECK (source_type IN ('m3u_url', 'xtream', 'file')),
    source_url    TEXT,
    credentials   TEXT,
    raw_content   TEXT,
    epg_url       TEXT,
    channel_count INTEGER,
    created_at    TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at    TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_playlists_device_mac ON playlists (device_mac);
CREATE INDEX IF NOT EXISTS idx_devices_status_trial ON devices (status, trial_ends_at);
