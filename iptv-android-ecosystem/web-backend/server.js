import Fastify from 'fastify';
import cors from '@fastify/cors';
import helmet from '@fastify/helmet';
import multipart from '@fastify/multipart';
import rateLimit from '@fastify/rate-limit';
import fastifyStatic from '@fastify/static';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import { loadConfig } from './config.js';
import { createDb } from './db/index.js';
import adminRoutes from './routes/admin.js';
import deviceRoutes from './routes/device.js';
import playlistRoutes from './routes/playlist.js';

const here = dirname(fileURLToPath(import.meta.url));

/**
 * Creates a fully configured Fastify instance. Exported for tests.
 */
export async function buildApp(overrides = {}) {
  const config = loadConfig(overrides);
  const app = Fastify({
    logger: config.logger,
    trustProxy: config.trustProxy,
    bodyLimit: 256 * 1024, // JSON bodies are tiny; uploads use multipart limits below
    disableRequestLogging: config.isProduction,
    ajv: { customOptions: { removeAdditional: false, coerceTypes: false, allErrors: false } },
  });

  const db = overrides.db ?? (await createDb(config));
  await db.migrate();
  app.decorate('db', db);
  app.decorate('config', config);
  app.addHook('onClose', async () => db.close());

  await app.register(helmet, {
    contentSecurityPolicy: {
      useDefaults: true,
      directives: {
        'script-src': ["'self'", 'https://cdn.tailwindcss.com'],
        // The Tailwind Play CDN injects a <style> element at runtime.
        'style-src': ["'self'", "'unsafe-inline'", 'https://fonts.googleapis.com'],
        'font-src': ["'self'", 'https://fonts.gstatic.com'],
        'img-src': ["'self'", 'data:'],
        'connect-src': ["'self'"],
        'upgrade-insecure-requests': config.isProduction ? [] : null,
      },
    },
    crossOriginEmbedderPolicy: false,
  });

  await app.register(cors, {
    origin: config.corsOrigins.length ? config.corsOrigins : false,
    methods: ['GET', 'POST', 'DELETE'],
  });

  if (config.rateLimit) {
    await app.register(rateLimit, {
      global: true,
      max: 120,
      timeWindow: '1 minute',
      allowList: [],
      errorResponseBuilder: (_req, ctx) => ({
        statusCode: 429,
        error: 'RATE_LIMITED',
        message: `Too many requests, retry in ${Math.ceil(ctx.ttl / 1000)}s`,
      }),
    });
  }

  await app.register(multipart, {
    limits: {
      fileSize: config.maxUploadBytes,
      files: 1,
      fields: 5,
      fieldSize: 4096,
      parts: 8,
    },
  });

  // Uniform error envelope; never leak stack traces.
  app.setErrorHandler((error, request, reply) => {
    if (error.validation) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', message: error.message });
    }
    if (error.code === 'FST_REQ_FILE_TOO_LARGE' || error.code === 'FST_FILES_LIMIT') {
      return reply.code(413).send({ error: 'FILE_TOO_LARGE', message: 'Playlist file exceeds the upload limit' });
    }
    const status = error.statusCode && error.statusCode >= 400 && error.statusCode < 500 ? error.statusCode : 500;
    if (status === 500) request.log.error({ err: error }, 'unhandled error');
    return reply.code(status).send({
      error: status === 500 ? 'INTERNAL_ERROR' : (error.code ?? 'REQUEST_ERROR'),
      message: status === 500 ? 'Unexpected server error' : error.message,
    });
  });

  app.get('/healthz', { config: { rateLimit: false } }, async () => {
    await db.one('SELECT 1 AS ok');
    return { ok: true, time: new Date().toISOString() };
  });

  await app.register(deviceRoutes, { prefix: '/api/v1/device' });
  await app.register(playlistRoutes, { prefix: '/api/v1/playlists' });
  if (config.adminToken) await app.register(adminRoutes, { prefix: '/api/v1/admin' });

  app.setNotFoundHandler((request, reply) => {
    if (request.url.startsWith('/api/')) {
      return reply.code(404).send({ error: 'NOT_FOUND', message: 'Route not found' });
    }
    return reply.sendFile('index.html');
  });

  await app.register(fastifyStatic, {
    root: join(here, 'public'),
    prefix: '/',
    maxAge: config.isProduction ? '1h' : 0,
  });

  return app;
}

async function start() {
  const app = await buildApp();
  const { port, host } = app.config;
  for (const signal of ['SIGINT', 'SIGTERM']) {
    process.once(signal, async () => {
      app.log.info({ signal }, 'shutting down');
      await app.close();
      process.exit(0);
    });
  }
  try {
    await app.listen({ port, host });
  } catch (err) {
    app.log.error(err);
    process.exit(1);
  }
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  start();
}
