-- AuraStream backend schema (PostgreSQL 13+)
-- Idempotent: safe to run on every boot.

CREATE TABLE IF NOT EXISTS devices (
    id                  BIGSERIAL PRIMARY KEY,
    mac_address         VARCHAR(17)  NOT NULL UNIQUE,
    device_key          CHAR(6)      NOT NULL,
    status              VARCHAR(16)  NOT NULL DEFAULT 'trial'
                        CHECK (status IN ('trial', 'active', 'expired')),
    trial_ends_at       TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- operational columns
    device_secret_hash  CHAR(64)     NOT NULL,
    license_ends_at     TIMESTAMPTZ,
    model               VARCHAR(120),
    app_version         VARCHAR(40),
    last_seen_at        TIMESTAMPTZ,
    failed_attempts     INTEGER      NOT NULL DEFAULT 0,
    locked_until        TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS playlists (
    id            BIGSERIAL PRIMARY KEY,
    device_mac    VARCHAR(17)  NOT NULL REFERENCES devices (mac_address) ON DELETE CASCADE ON UPDATE CASCADE,
    name          VARCHAR(80)  NOT NULL,
    source_type   VARCHAR(16)  NOT NULL CHECK (source_type IN ('m3u_url', 'xtream', 'file')),
    source_url    TEXT,
    credentials   JSONB,
    raw_content   TEXT,
    epg_url       TEXT,
    channel_count INTEGER,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_playlists_device_mac ON playlists (device_mac);
CREATE INDEX IF NOT EXISTS idx_devices_status_trial ON devices (status, trial_ends_at);
