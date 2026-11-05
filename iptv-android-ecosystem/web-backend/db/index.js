import { readFile, mkdir } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));

/**
 * Thin database adapter exposing the same API for PostgreSQL (production) and
 * SQLite (development / tests). All SQL is written with PostgreSQL-style `$n`
 * placeholders; for SQLite they are rewritten to numbered `?n` parameters.
 *
 *   db.query(sql, params)  -> rows[]
 *   db.one(sql, params)    -> first row | null
 *   db.transaction(async (tx) => ...)
 */
export async function createDb(config) {
  return config.databaseUrl ? createPostgres(config.databaseUrl) : createSqlite(config.sqlitePath);
}

async function createPostgres(connectionString) {
  const { default: pg } = await import('pg');
  const pool = new pg.Pool({
    connectionString,
    max: Number(process.env.PG_POOL_MAX ?? 10),
    idleTimeoutMillis: 30_000,
  });

  const wrap = (client) => ({
    dialect: 'postgres',
    async query(sql, params = []) {
      const res = await client.query(sql, params);
      return res.rows;
    },
    async one(sql, params = []) {
      const res = await client.query(sql, params);
      return res.rows[0] ?? null;
    },
  });

  const base = wrap(pool);
  return {
    ...base,
    async migrate() {
      const sql = await readFile(join(here, 'schema.sql'), 'utf8');
      await pool.query(sql);
    },
    async transaction(fn) {
      const client = await pool.connect();
      try {
        await client.query('BEGIN');
        const result = await fn(wrap(client));
        await client.query('COMMIT');
        return result;
      } catch (err) {
        await client.query('ROLLBACK').catch(() => {});
        throw err;
      } finally {
        client.release();
      }
    },
    async close() {
      await pool.end();
    },
  };
}

async function createSqlite(path) {
  const { DatabaseSync } = await import('node:sqlite');
  if (path !== ':memory:') await mkdir(dirname(path), { recursive: true });
  const db = new DatabaseSync(path);
  db.exec('PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;');

  const cache = new Map();
  const prepare = (sql) => {
    let stmt = cache.get(sql);
    if (!stmt) {
      stmt = db.prepare(sql.replace(/\$(\d+)/g, '?$1'));
      cache.set(sql, stmt);
    }
    return stmt;
  };
  const normalize = (params) =>
    params.map((p) => {
      if (p === undefined) return null;
      if (p instanceof Date) return p.toISOString();
      if (typeof p === 'boolean') return p ? 1 : 0;
      if (p !== null && typeof p === 'object') return JSON.stringify(p);
      return p;
    });
  const toPlain = (row) => (row ? { ...row } : null);

  const api = {
    dialect: 'sqlite',
    async query(sql, params = []) {
      return prepare(sql).all(...normalize(params)).map(toPlain);
    },
    async one(sql, params = []) {
      return toPlain(prepare(sql).get(...normalize(params)));
    },
  };

  // node:sqlite is synchronous; a simple mutex keeps transactions serialised.
  let chain = Promise.resolve();
  return {
    ...api,
    async migrate() {
      db.exec(await readFile(join(here, 'schema.sqlite.sql'), 'utf8'));
    },
    transaction(fn) {
      const run = chain.then(async () => {
        db.exec('BEGIN IMMEDIATE');
        try {
          const result = await fn(api);
          db.exec('COMMIT');
          return result;
        } catch (err) {
          db.exec('ROLLBACK');
          throw err;
        }
      });
      chain = run.catch(() => {});
      return run;
    },
    async close() {
      db.close();
    },
  };
}
