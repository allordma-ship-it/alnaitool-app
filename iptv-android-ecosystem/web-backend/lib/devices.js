import { decodePortalToken, verifyDeviceSecret, verifyPortalSignature } from './security.js';
import { normalizeMac } from './validation.js';

export const toIso = (v) => (v == null ? null : v instanceof Date ? v.toISOString() : new Date(v).toISOString());

/**
 * Computes the effective licence status and persists an automatic transition
 * to `expired` once a trial (or paid licence) runs out.
 */
export async function resolveLicense(db, device) {
  const now = Date.now();
  let status = device.status;
  if (status === 'trial' && new Date(device.trial_ends_at).getTime() <= now) status = 'expired';
  if (status === 'active' && device.license_ends_at && new Date(device.license_ends_at).getTime() <= now) {
    status = 'expired';
  }
  if (status !== device.status) {
    await db.query('UPDATE devices SET status = $1 WHERE id = $2', [status, device.id]);
    device.status = status;
  }
  return {
    status,
    trialEndsAt: toIso(device.trial_ends_at),
    licenseEndsAt: toIso(device.license_ends_at),
    daysLeft:
      status === 'trial'
        ? Math.max(0, Math.ceil((new Date(device.trial_ends_at).getTime() - now) / 86_400_000))
        : null,
  };
}

export function findDeviceByMac(db, mac) {
  return db.one('SELECT * FROM devices WHERE mac_address = $1', [mac]);
}

export function pairingUrl(config, mac, deviceKey) {
  const qs = new URLSearchParams({ mac, key: deviceKey });
  return `${config.publicPortalUrl}/?${qs.toString()}`;
}

/** Fastify preHandler: authenticates the TV via X-Device-Mac / X-Device-Secret headers. */
export function deviceAuth(app) {
  return async function authenticateDevice(request, reply) {
    const mac = normalizeMac(request.headers['x-device-mac']);
    const secret = request.headers['x-device-secret'];
    if (!mac || !secret) {
      return reply.code(401).send({ error: 'DEVICE_AUTH_REQUIRED', message: 'Missing device credentials' });
    }
    const device = await findDeviceByMac(app.db, mac);
    if (!device || !verifyDeviceSecret(secret, device.device_secret_hash)) {
      return reply.code(401).send({ error: 'DEVICE_AUTH_INVALID', message: 'Invalid device credentials' });
    }
    request.device = device;
  };
}

/** Fastify preHandler: authenticates a portal session (Authorization: Bearer <token>). */
export function portalAuth(app) {
  return async function authenticatePortal(request, reply) {
    const header = request.headers.authorization ?? '';
    const token = header.startsWith('Bearer ') ? header.slice(7).trim() : null;
    const decoded = decodePortalToken(token);
    if (!decoded) {
      return reply.code(401).send({ error: 'SESSION_INVALID', message: 'Session expired, please sign in again' });
    }
    const device = await findDeviceByMac(app.db, decoded.mac);
    if (!device || !verifyPortalSignature(app.config.appSecret, decoded, device.device_key)) {
      return reply.code(401).send({ error: 'SESSION_INVALID', message: 'Session expired, please sign in again' });
    }
    request.device = device;
  };
}
