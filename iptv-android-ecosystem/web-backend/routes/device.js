import { findDeviceByMac, pairingUrl, resolveLicense, toIso, deviceAuth } from '../lib/devices.js';
import {
  generateDeviceKey,
  generateDeviceSecret,
  safeEqual,
  sha256Hex,
  signPortalToken,
  verifyDeviceSecret,
} from '../lib/security.js';
import { isDeviceKey, normalizeMac, sanitizeText } from '../lib/validation.js';

const handshakeSchema = {
  body: {
    type: 'object',
    required: ['mac'],
    additionalProperties: false,
    properties: {
      mac: { type: 'string', maxLength: 32 },
      deviceSecret: { type: 'string', maxLength: 128 },
      model: { type: 'string', maxLength: 120 },
      appVersion: { type: 'string', maxLength: 40 },
      reset: { type: 'boolean' },
    },
  },
};

const verifySchema = {
  body: {
    type: 'object',
    required: ['mac', 'deviceKey'],
    additionalProperties: false,
    properties: {
      mac: { type: 'string', maxLength: 32 },
      deviceKey: { type: 'string', maxLength: 6, minLength: 6 },
    },
  },
};

/**
 * Device pairing & licensing.
 *
 *  POST /api/v1/device/handshake  (TV)     register / refresh a device
 *  GET  /api/v1/device/status     (TV)     licence status (header auth)
 *  POST /api/v1/device/verify     (portal) MAC + Device Key -> portal session token
 */
export default async function deviceRoutes(app) {
  const { db, config } = app;
  const authenticateDevice = deviceAuth(app);

  function devicePayload(device, license, extra = {}) {
    return {
      mac: device.mac_address,
      deviceKey: device.device_key,
      ...license,
      portalUrl: config.publicPortalUrl,
      pairingUrl: pairingUrl(config, device.mac_address, device.device_key),
      serverTime: new Date().toISOString(),
      ...extra,
    };
  }

  app.post(
    '/handshake',
    {
      schema: handshakeSchema,
      config: { rateLimit: { max: 20, timeWindow: '1 minute' } },
    },
    async (request, reply) => {
      const mac = normalizeMac(request.body.mac);
      if (!mac) return reply.code(400).send({ error: 'INVALID_MAC', message: 'A valid MAC address is required' });

      const model = sanitizeText(request.body.model ?? '', 120) || null;
      const appVersion = sanitizeText(request.body.appVersion ?? '', 40) || null;
      const now = new Date();

      const existing = await findDeviceByMac(db, mac);

      // --- First contact: register with a fresh trial -----------------------
      if (!existing) {
        const deviceKey = generateDeviceKey();
        const deviceSecret = generateDeviceSecret();
        const trialEndsAt = new Date(now.getTime() + config.trialDays * 86_400_000);
        const inserted = await db.one(
          `INSERT INTO devices (mac_address, device_key, status, trial_ends_at, device_secret_hash, model, app_version, last_seen_at, created_at)
           VALUES ($1, $2, 'trial', $3, $4, $5, $6, $7, $7)
           ON CONFLICT (mac_address) DO NOTHING
           RETURNING *`,
          [mac, deviceKey, trialEndsAt.toISOString(), sha256Hex(deviceSecret), model, appVersion, now.toISOString()],
        );
        if (!inserted) {
          // Lost a registration race against a concurrent handshake for the same MAC.
          return reply.code(409).send({ error: 'DEVICE_SECRET_MISMATCH', message: 'Device already registered' });
        }
        request.log.info({ mac }, 'device registered');
        const license = await resolveLicense(db, inserted);
        return reply.code(201).send(devicePayload(inserted, license, { deviceSecret, registered: true }));
      }

      // --- Known device presenting its secret --------------------------------
      if (verifyDeviceSecret(request.body.deviceSecret, existing.device_secret_hash)) {
        await db.query('UPDATE devices SET last_seen_at = $1, model = COALESCE($2, model), app_version = COALESCE($3, app_version) WHERE id = $4', [
          now.toISOString(),
          model,
          appVersion,
          existing.id,
        ]);
        const license = await resolveLicense(db, existing);
        return devicePayload(existing, license, { registered: false });
      }

      // --- Known device without valid secret (reinstall / factory reset) -----
      if (request.body.reset === true) {
        // Re-pairing rotates both key and secret and purges stored playlists so
        // that whoever knows a MAC address can never read existing provider
        // credentials. The trial window is intentionally NOT reset.
        const deviceKey = generateDeviceKey();
        const deviceSecret = generateDeviceSecret();
        const updated = await db.transaction(async (tx) => {
          await tx.query('DELETE FROM playlists WHERE device_mac = $1', [mac]);
          return tx.one(
            `UPDATE devices SET device_key = $1, device_secret_hash = $2, last_seen_at = $3,
                    failed_attempts = 0, locked_until = NULL,
                    model = COALESCE($4, model), app_version = COALESCE($5, app_version)
             WHERE id = $6 RETURNING *`,
            [deviceKey, sha256Hex(deviceSecret), now.toISOString(), model, appVersion, existing.id],
          );
        });
        request.log.warn({ mac }, 'device re-paired, playlists purged');
        const license = await resolveLicense(db, updated);
        return devicePayload(updated, license, { deviceSecret, registered: false, reset: true });
      }

      return reply.code(409).send({
        error: 'DEVICE_SECRET_MISMATCH',
        message: 'This MAC address is already registered. Re-pair the device to continue.',
      });
    },
  );

  app.get('/status', { preHandler: authenticateDevice }, async (request) => {
    const license = await resolveLicense(db, request.device);
    return devicePayload(request.device, license);
  });

  app.post(
    '/verify',
    {
      schema: verifySchema,
      config: { rateLimit: { max: 10, timeWindow: '1 minute' } },
    },
    async (request, reply) => {
      const mac = normalizeMac(request.body.mac);
      const { deviceKey } = request.body;
      const invalid = () =>
        reply.code(401).send({ error: 'INVALID_CREDENTIALS', message: 'MAC address or Device Key is incorrect' });

      if (!mac || !isDeviceKey(deviceKey)) return invalid();
      const device = await findDeviceByMac(db, mac);
      if (!device) return invalid();

      const now = Date.now();
      if (device.locked_until && new Date(device.locked_until).getTime() > now) {
        return reply.code(423).send({
          error: 'DEVICE_LOCKED',
          message: 'Too many failed attempts. Try again later.',
          lockedUntil: toIso(device.locked_until),
        });
      }

      if (!safeEqual(deviceKey, device.device_key)) {
        const attempts = device.failed_attempts + 1;
        const lock = attempts >= config.maxFailedAttempts;
        await db.query('UPDATE devices SET failed_attempts = $1, locked_until = $2 WHERE id = $3', [
          lock ? 0 : attempts,
          lock ? new Date(now + config.lockoutMs).toISOString() : null,
          device.id,
        ]);
        request.log.warn({ mac, attempts }, 'portal verification failed');
        return invalid();
      }

      if (device.failed_attempts !== 0 || device.locked_until) {
        await db.query('UPDATE devices SET failed_attempts = 0, locked_until = NULL WHERE id = $1', [device.id]);
      }

      const license = await resolveLicense(db, device);
      const session = signPortalToken(config.appSecret, {
        mac,
        deviceKey: device.device_key,
        ttlMs: config.portalTokenTtlMs,
      });
      return { ...session, device: { mac, ...license } };
    },
  );
}
