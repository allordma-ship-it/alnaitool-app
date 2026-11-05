import { findDeviceByMac, resolveLicense } from '../lib/devices.js';
import { safeEqual } from '../lib/security.js';
import { normalizeMac } from '../lib/validation.js';

/**
 * Operator endpoints (only registered when ADMIN_TOKEN is configured).
 *   POST /api/v1/admin/devices/:mac/license  { status: 'active'|'expired'|'trial', days?: number }
 */
export default async function adminRoutes(app) {
  const { db, config } = app;

  app.addHook('onRequest', async (request, reply) => {
    const header = request.headers.authorization ?? '';
    const token = header.startsWith('Bearer ') ? header.slice(7) : '';
    if (!safeEqual(token, config.adminToken)) {
      return reply.code(401).send({ error: 'UNAUTHORIZED' });
    }
  });

  app.post(
    '/devices/:mac/license',
    {
      schema: {
        body: {
          type: 'object',
          required: ['status'],
          additionalProperties: false,
          properties: {
            status: { type: 'string', enum: ['trial', 'active', 'expired'] },
            days: { type: 'integer', minimum: 1, maximum: 3650 },
          },
        },
      },
    },
    async (request, reply) => {
      const mac = normalizeMac(request.params.mac);
      const device = mac ? await findDeviceByMac(db, mac) : null;
      if (!device) return reply.code(404).send({ error: 'NOT_FOUND' });

      const { status, days } = request.body;
      const until = days ? new Date(Date.now() + days * 86_400_000).toISOString() : null;
      // Separate statements keep the SQL portable between PostgreSQL and SQLite.
      let sql = 'UPDATE devices SET status = $1 WHERE id = $2 RETURNING *';
      let params = [status, device.id];
      if (status === 'active') {
        sql = 'UPDATE devices SET status = $1, license_ends_at = $2 WHERE id = $3 RETURNING *';
        params = [status, until, device.id];
      } else if (status === 'trial' && until) {
        sql = 'UPDATE devices SET status = $1, trial_ends_at = $2 WHERE id = $3 RETURNING *';
        params = [status, until, device.id];
      }
      const updated = await db.one(sql, params);
      return { mac, ...(await resolveLicense(db, updated)) };
    },
  );
}
