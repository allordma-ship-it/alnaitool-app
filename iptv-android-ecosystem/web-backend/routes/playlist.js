import { deviceAuth, portalAuth, resolveLicense, toIso } from '../lib/devices.js';
import { inspectM3u, stripBom } from '../lib/m3u.js';
import { decryptJson, encryptJson } from '../lib/security.js';
import { normalizeHttpUrl, normalizeXtreamHost, sanitizeCredential, sanitizeText } from '../lib/validation.js';

const idParam = {
  params: {
    type: 'object',
    required: ['id'],
    properties: { id: { type: 'string', pattern: '^[0-9]{1,18}$' } },
  },
};

const createSchema = {
  body: {
    type: 'object',
    required: ['sourceType'],
    additionalProperties: false,
    properties: {
      name: { type: 'string', maxLength: 120 },
      sourceType: { type: 'string', enum: ['m3u_url', 'xtream'] },
      sourceUrl: { type: 'string', maxLength: 2048 },
      epgUrl: { type: 'string', maxLength: 2048 },
      xtream: {
        type: 'object',
        additionalProperties: false,
        required: ['host', 'username', 'password'],
        properties: {
          host: { type: 'string', maxLength: 512 },
          username: { type: 'string', maxLength: 128 },
          password: { type: 'string', maxLength: 128 },
        },
      },
    },
  },
};

/**
 * Remote playlist management.
 *
 * Portal (Bearer token from /api/v1/device/verify):
 *   GET    /api/v1/playlists
 *   POST   /api/v1/playlists            M3U URL or Xtream Codes credentials (JSON)
 *   POST   /api/v1/playlists/upload     multipart .m3u / .m3u8 file
 *   DELETE /api/v1/playlists/:id
 *
 * TV (X-Device-Mac + X-Device-Secret headers):
 *   GET    /api/v1/playlists/sync            licence + playlist descriptors
 *   GET    /api/v1/playlists/:id/content     raw uploaded M3U content
 */
export default async function playlistRoutes(app) {
  const { db, config } = app;
  const authenticatePortal = portalAuth(app);
  const authenticateDevice = deviceAuth(app);

  /** Portal-safe projection: never echoes passwords or raw content back to the browser. */
  function toPortalDto(row) {
    let xtream = null;
    if (row.source_type === 'xtream' && row.credentials) {
      try {
        const creds = decryptJson(config.credentialsKey, parseCredentials(row.credentials).enc);
        xtream = { host: creds.host, username: creds.username, password: '••••••••' };
      } catch {
        xtream = { host: null, username: null, password: null };
      }
    }
    return {
      id: String(row.id),
      name: row.name,
      sourceType: row.source_type,
      sourceUrl: row.source_type === 'm3u_url' ? maskUrl(row.source_url) : null,
      xtream,
      epgUrl: row.epg_url ?? null,
      channelCount: row.channel_count ?? null,
      updatedAt: toIso(row.updated_at),
    };
  }

  /** Device projection: contains everything the TV needs to load the playlist. */
  function toDeviceDto(row) {
    const dto = {
      id: String(row.id),
      name: row.name,
      sourceType: row.source_type,
      sourceUrl: null,
      xtream: null,
      epgUrl: row.epg_url ?? null,
      updatedAt: toIso(row.updated_at),
    };
    if (row.source_type === 'm3u_url') dto.sourceUrl = row.source_url;
    if (row.source_type === 'file') dto.sourceUrl = `${config.publicPortalUrl}/api/v1/playlists/${row.id}/content`;
    if (row.source_type === 'xtream' && row.credentials) {
      dto.xtream = decryptJson(config.credentialsKey, parseCredentials(row.credentials).enc);
    }
    return dto;
  }

  async function assertCapacity(mac, reply) {
    const row = await db.one('SELECT COUNT(*) AS n FROM playlists WHERE device_mac = $1', [mac]);
    if (Number(row.n) >= config.maxPlaylistsPerDevice) {
      reply.code(409).send({
        error: 'PLAYLIST_LIMIT',
        message: `A device can hold at most ${config.maxPlaylistsPerDevice} playlists`,
      });
      return false;
    }
    return true;
  }

  // ------------------------------------------------------------------ portal

  app.get('/', { preHandler: authenticatePortal }, async (request) => {
    const rows = await db.query(
      `SELECT id, name, source_type, source_url, credentials, epg_url, channel_count, updated_at
       FROM playlists WHERE device_mac = $1 ORDER BY updated_at DESC, id DESC`,
      [request.device.mac_address],
    );
    const license = await resolveLicense(db, request.device);
    return { device: { mac: request.device.mac_address, ...license }, playlists: rows.map(toPortalDto) };
  });

  app.post(
    '/',
    { preHandler: authenticatePortal, schema: createSchema, config: { rateLimit: { max: 30, timeWindow: '1 minute' } } },
    async (request, reply) => {
      const mac = request.device.mac_address;
      const body = request.body;

      const epgUrl = body.epgUrl ? normalizeHttpUrl(body.epgUrl) : null;
      if (body.epgUrl && !epgUrl) return reply.code(400).send({ error: 'INVALID_EPG_URL', message: 'EPG URL must be http(s)' });

      let sourceUrl = null;
      let credentials = null;
      let defaultName;

      if (body.sourceType === 'm3u_url') {
        sourceUrl = normalizeHttpUrl(body.sourceUrl);
        if (!sourceUrl) return reply.code(400).send({ error: 'INVALID_URL', message: 'A valid http(s) playlist URL is required' });
        defaultName = new URL(sourceUrl).hostname;
      } else {
        const host = normalizeXtreamHost(body.xtream?.host);
        const username = sanitizeCredential(body.xtream?.username);
        const password = sanitizeCredential(body.xtream?.password);
        if (!host || !username || !password) {
          return reply.code(400).send({ error: 'INVALID_XTREAM', message: 'Host, username and password are required' });
        }
        credentials = { enc: encryptJson(config.credentialsKey, { host, username, password }) };
        defaultName = new URL(host).hostname;
      }

      const name = sanitizeText(body.name ?? '') || defaultName;
      if (!(await assertCapacity(mac, reply))) return reply;

      const now = new Date().toISOString();
      const row = await db.one(
        `INSERT INTO playlists (device_mac, name, source_type, source_url, credentials, epg_url, created_at, updated_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $7) RETURNING *`,
        [mac, name, body.sourceType, sourceUrl, credentials ? JSON.stringify(credentials) : null, epgUrl, now],
      );
      request.log.info({ mac, playlistId: row.id, type: body.sourceType }, 'playlist created');
      return reply.code(201).send(toPortalDto(row));
    },
  );

  app.post(
    '/upload',
    { preHandler: authenticatePortal, config: { rateLimit: { max: 10, timeWindow: '1 minute' } } },
    async (request, reply) => {
      const mac = request.device.mac_address;
      if (!request.isMultipart()) {
        return reply.code(415).send({ error: 'MULTIPART_REQUIRED', message: 'Upload the playlist as multipart/form-data' });
      }

      let fileBuffer = null;
      let filename = null;
      const fields = {};
      for await (const part of request.parts()) {
        if (part.type === 'file') {
          if (fileBuffer) {
            part.file.resume();
            continue;
          }
          filename = part.filename;
          fileBuffer = await part.toBuffer(); // throws RequestFileTooLargeError when over the limit
        } else if (typeof part.value === 'string') {
          fields[part.fieldname] = part.value;
        }
      }

      if (!fileBuffer || fileBuffer.length === 0) {
        return reply.code(400).send({ error: 'FILE_REQUIRED', message: 'Choose an .m3u or .m3u8 file' });
      }
      if (filename && !/\.(m3u8?|txt)$/i.test(filename)) {
        return reply.code(400).send({ error: 'INVALID_FILE_TYPE', message: 'Only .m3u / .m3u8 files are accepted' });
      }

      const content = stripBom(fileBuffer.toString('utf8')).replace(/\u0000/g, '');
      const report = inspectM3u(content);
      if (!report.valid) return reply.code(422).send({ error: 'INVALID_PLAYLIST', message: report.reason });

      const epgUrl = normalizeHttpUrl(fields.epgUrl ?? '') ?? (report.epgUrl ? normalizeHttpUrl(report.epgUrl) : null);
      const name = sanitizeText(fields.name ?? '') || sanitizeText((filename ?? 'Playlist').replace(/\.[^.]+$/, '')) || 'Playlist';
      if (!(await assertCapacity(mac, reply))) return reply;

      const now = new Date().toISOString();
      const row = await db.one(
        `INSERT INTO playlists (device_mac, name, source_type, raw_content, epg_url, channel_count, created_at, updated_at)
         VALUES ($1, $2, 'file', $3, $4, $5, $6, $6) RETURNING id, name, source_type, source_url, credentials, epg_url, channel_count, updated_at`,
        [mac, name, content, epgUrl, report.entries, now],
      );
      request.log.info({ mac, playlistId: row.id, entries: report.entries }, 'playlist uploaded');
      return reply.code(201).send({ ...toPortalDto(row), groups: report.groups });
    },
  );

  app.delete('/:id', { preHandler: authenticatePortal, schema: idParam }, async (request, reply) => {
    const rows = await db.query('DELETE FROM playlists WHERE id = $1 AND device_mac = $2 RETURNING id', [
      request.params.id,
      request.device.mac_address,
    ]);
    if (rows.length === 0) return reply.code(404).send({ error: 'NOT_FOUND', message: 'Playlist not found' });
    return reply.code(204).send();
  });

  // ---------------------------------------------------------------------- TV

  app.get('/sync', { preHandler: authenticateDevice }, async (request, reply) => {
    const device = request.device;
    const license = await resolveLicense(db, device);
    await db.query('UPDATE devices SET last_seen_at = $1 WHERE id = $2', [new Date().toISOString(), device.id]);

    if (license.status === 'expired') {
      return reply.code(402).send({ error: 'LICENSE_EXPIRED', message: 'Trial expired. Please activate this device.', ...license });
    }

    const rows = await db.query(
      `SELECT id, name, source_type, source_url, credentials, epg_url, updated_at
       FROM playlists WHERE device_mac = $1 ORDER BY updated_at DESC, id DESC`,
      [device.mac_address],
    );
    const playlists = rows.map(toDeviceDto);
    // Cheap change detector the TV can poll: max(updated_at) + count + ids.
    const version = playlists.length
      ? `${playlists.length}-${playlists.map((p) => p.id).join('.')}-${playlists.reduce((m, p) => (p.updatedAt > m ? p.updatedAt : m), '')}`
      : '0';

    reply.header('Cache-Control', 'no-store');
    return { ...license, playlistsVersion: version, playlists };
  });

  app.get('/:id/content', { preHandler: authenticateDevice, schema: idParam }, async (request, reply) => {
    const license = await resolveLicense(db, request.device);
    if (license.status === 'expired') return reply.code(402).send({ error: 'LICENSE_EXPIRED', ...license });

    const row = await db.one(
      "SELECT raw_content, updated_at FROM playlists WHERE id = $1 AND device_mac = $2 AND source_type = 'file'",
      [request.params.id, request.device.mac_address],
    );
    if (!row) return reply.code(404).send({ error: 'NOT_FOUND', message: 'Playlist not found' });
    reply
      .header('Content-Type', 'audio/x-mpegurl; charset=utf-8')
      .header('Cache-Control', 'no-store')
      .header('Last-Modified', new Date(row.updated_at).toUTCString());
    return row.raw_content;
  });
}

function parseCredentials(value) {
  return typeof value === 'string' ? JSON.parse(value) : value;
}

/** Hides embedded credentials (user:pass@, ?username=&password=) when showing URLs in the portal. */
function maskUrl(href) {
  if (!href) return null;
  try {
    const url = new URL(href);
    if (url.username) url.username = '***';
    if (url.password) url.password = '***';
    for (const key of [...url.searchParams.keys()]) {
      if (/pass|pwd|token|key/i.test(key)) url.searchParams.set(key, '***');
    }
    return url.href;
  } catch {
    return null;
  }
}
