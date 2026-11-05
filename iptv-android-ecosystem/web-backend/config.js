import { createHash, hkdfSync } from 'node:crypto';

const env = process.env;

function int(name, fallback) {
  const raw = env[name];
  if (raw === undefined || raw === '') return fallback;
  const n = Number.parseInt(raw, 10);
  if (!Number.isFinite(n)) throw new Error(`Environment variable ${name} must be an integer`);
  return n;
}

function bool(name, fallback = false) {
  const raw = env[name];
  if (raw === undefined || raw === '') return fallback;
  return ['1', 'true', 'yes', 'on'].includes(raw.toLowerCase());
}

function decodeKey(raw) {
  if (!raw) return null;
  const buf = /^[0-9a-fA-F]{64}$/.test(raw) ? Buffer.from(raw, 'hex') : Buffer.from(raw, 'base64');
  if (buf.length !== 32) throw new Error('CREDENTIALS_KEY must decode to exactly 32 bytes');
  return buf;
}

/**
 * Builds the runtime configuration. `overrides` is used by the test-suite.
 */
export function loadConfig(overrides = {}) {
  const nodeEnv = overrides.nodeEnv ?? env.NODE_ENV ?? 'development';
  const isProduction = nodeEnv === 'production';

  let appSecret = overrides.appSecret ?? env.APP_SECRET;
  if (!appSecret || appSecret.length < 32) {
    if (isProduction) {
      throw new Error('APP_SECRET must be set to a random string of at least 32 characters in production');
    }
    // Deterministic dev secret so restarts do not invalidate local sessions.
    appSecret = createHash('sha256').update('aurastream-dev-secret').digest('hex');
  }

  const credentialsKey =
    decodeKey(overrides.credentialsKey ?? env.CREDENTIALS_KEY) ??
    Buffer.from(hkdfSync('sha256', appSecret, 'aurastream', 'credentials-v1', 32));

  const port = overrides.port ?? int('PORT', 8080);

  return Object.freeze({
    nodeEnv,
    isProduction,
    port,
    host: overrides.host ?? env.HOST ?? '0.0.0.0',
    trustProxy: overrides.trustProxy ?? bool('TRUST_PROXY'),
    publicPortalUrl: (overrides.publicPortalUrl ?? env.PUBLIC_PORTAL_URL ?? `http://localhost:${port}`).replace(/\/+$/, ''),
    databaseUrl: overrides.databaseUrl ?? env.DATABASE_URL ?? null,
    sqlitePath: overrides.sqlitePath ?? env.SQLITE_PATH ?? './data/dev.sqlite',
    appSecret,
    credentialsKey,
    adminToken: overrides.adminToken ?? env.ADMIN_TOKEN ?? null,
    corsOrigins: (overrides.corsOrigins ?? env.CORS_ORIGINS ?? '')
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean),
    trialDays: overrides.trialDays ?? int('TRIAL_DAYS', 7),
    maxUploadBytes: (overrides.maxUploadMb ?? int('MAX_UPLOAD_MB', 40)) * 1024 * 1024,
    maxPlaylistsPerDevice: overrides.maxPlaylistsPerDevice ?? int('MAX_PLAYLISTS_PER_DEVICE', 10),
    portalTokenTtlMs: (overrides.portalTokenTtlMin ?? int('PORTAL_TOKEN_TTL_MIN', 30)) * 60 * 1000,
    maxFailedAttempts: overrides.maxFailedAttempts ?? 8,
    lockoutMs: overrides.lockoutMs ?? 15 * 60 * 1000,
    rateLimit: overrides.rateLimit ?? true,
    logger: overrides.logger ?? (isProduction ? { level: 'info' } : { level: 'debug', transport: undefined }),
  });
}
