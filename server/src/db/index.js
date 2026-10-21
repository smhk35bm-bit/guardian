import { config } from '../config.js';
import { SCHEMA_SQL } from './schema.js';
import { createSqliteDriver } from './sqlite-driver.js';
import { createTursoDriver } from './turso-driver.js';

let driver = null;

/**
 * Creates the active driver.
 *
 *   DB_DRIVER=sqlite   → local file (node:sqlite) — VPS / development
 *   DB_DRIVER=libsql   → Turso over HTTP          — ephemeral-disk hosts
 */
export function createDriver() {
  if (config.dbDriver === 'libsql') {
    return createTursoDriver({ url: config.libsqlUrl, token: config.libsqlToken });
  }
  return createSqliteDriver({ file: config.dbFile });
}

/** Creates the schema if it does not exist yet. Safe to call on every boot. */
export async function initDb() {
  driver = createDriver();
  await driver.exec(SCHEMA_SQL);
  console.log(`[guardian] database driver: ${driver.name}`);
  return driver;
}

function active() {
  if (!driver) {
    throw new Error('database is not initialised yet — call initDb() first');
  }
  return driver;
}

/**
 * The query helpers used across the server.
 * Every call returns a promise, whether the driver is local or remote.
 */
export const q = {
  get: (sql, ...args) => active().get(sql, ...args),
  all: (sql, ...args) => active().all(sql, ...args),
  run: (sql, ...args) => active().run(sql, ...args),
  /** Runs `[{ sql, args }]` in one round-trip and returns one result per item. */
  batch: (items) => active().batch(items),
};

/** Normalises a row so BigInt never leaks into JSON responses. */
export function plain(row) {
  if (row === undefined || row === null) return row;
  return { ...row };
}

export function plainAll(rows) {
  return rows.map((r) => plain(r));
}

export const nowIso = () => new Date().toISOString();

/**
 * Trims history beyond the retention window so the free-tier disk stays healthy.
 * Devices and users are never touched — only their telemetry.
 */
export async function purgeOldData(retentionDays = 90) {
  const cutoff = new Date(Date.now() - retentionDays * 86400_000).toISOString();
  const tables = ['locations', 'app_usage', 'calls', 'sms', 'media', 'events'];
  let removed = 0;
  for (const t of tables) {
    const { changes } = await q.run(`DELETE FROM ${t} WHERE at < ?`, cutoff);
    removed += changes;
  }
  // Chunks of purged media rows are orphaned — drop them too.
  await q.run('DELETE FROM media_blobs WHERE media_id NOT IN (SELECT id FROM media)');
  if (removed) console.log(`[guardian] purged ${removed} old rows (older than ${retentionDays}d)`);
  return removed;
}

export function closeDb() {
  driver?.close();
  driver = null;
}
