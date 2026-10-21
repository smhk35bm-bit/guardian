import { DatabaseSync } from 'node:sqlite';
import { config } from './config.js';

export const db = new DatabaseSync(config.dbFile);

db.exec('PRAGMA journal_mode = WAL');
db.exec('PRAGMA foreign_keys = ON');
db.exec('PRAGMA busy_timeout = 5000');

db.exec(`
CREATE TABLE IF NOT EXISTS users (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  email         TEXT NOT NULL UNIQUE COLLATE NOCASE,
  password_hash TEXT NOT NULL,
  name          TEXT NOT NULL DEFAULT '',
  created_at    TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS devices (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id         INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name            TEXT NOT NULL DEFAULT 'جهاز',
  manufacturer    TEXT,
  model           TEXT,
  android_version TEXT,
  sdk_int         INTEGER,
  app_version     TEXT,
  device_token    TEXT UNIQUE,
  status          TEXT NOT NULL DEFAULT 'active',
  last_seen       TEXT,
  battery         INTEGER,
  charging        INTEGER,
  wifi            TEXT,
  network_type    TEXT,
  screen_on       INTEGER,
  created_at      TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_devices_user ON devices(user_id);

CREATE TABLE IF NOT EXISTS enrollment_codes (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  code       TEXT NOT NULL UNIQUE,
  device_id  INTEGER REFERENCES devices(id) ON DELETE SET NULL,
  expires_at TEXT NOT NULL,
  used_at    TEXT,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_codes_user ON enrollment_codes(user_id);

CREATE TABLE IF NOT EXISTS locations (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  lat       REAL NOT NULL,
  lng       REAL NOT NULL,
  accuracy  REAL,
  speed     REAL,
  altitude  REAL,
  bearing   REAL,
  provider  TEXT,
  battery   INTEGER,
  at        TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_locations_device_at ON locations(device_id, at DESC);

CREATE TABLE IF NOT EXISTS app_usage (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id   INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  package     TEXT NOT NULL,
  label       TEXT,
  duration_ms INTEGER NOT NULL DEFAULT 0,
  opened_at   TEXT,
  foreground  INTEGER NOT NULL DEFAULT 1,
  event_type  TEXT NOT NULL DEFAULT 'app_usage',
  at          TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_usage_device_at ON app_usage(device_id, at DESC);
CREATE INDEX IF NOT EXISTS idx_usage_device_pkg ON app_usage(device_id, package);

CREATE TABLE IF NOT EXISTS calls (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id    INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  number       TEXT,
  contact_name TEXT,
  call_type    TEXT NOT NULL DEFAULT 'outgoing',
  duration_sec INTEGER NOT NULL DEFAULT 0,
  at           TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_calls_device_at ON calls(device_id, at DESC);

CREATE TABLE IF NOT EXISTS sms (
  id      INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  address TEXT,
  body    TEXT,
  msg_type TEXT NOT NULL DEFAULT 'inbox',
  at      TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sms_device_at ON sms(device_id, at DESC);

CREATE TABLE IF NOT EXISTS media (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  kind      TEXT NOT NULL DEFAULT 'screenshot',
  filename  TEXT NOT NULL,
  mime      TEXT,
  size      INTEGER NOT NULL DEFAULT 0,
  meta      TEXT,
  at        TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_media_device_at ON media(device_id, at DESC);

CREATE TABLE IF NOT EXISTS installed_apps (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id    INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  package      TEXT NOT NULL,
  label        TEXT,
  version      TEXT,
  is_system    INTEGER NOT NULL DEFAULT 0,
  installed_at TEXT,
  at           TEXT NOT NULL,
  UNIQUE(device_id, package)
);
CREATE INDEX IF NOT EXISTS idx_apps_device ON installed_apps(device_id);

CREATE TABLE IF NOT EXISTS events (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  type      TEXT NOT NULL,
  payload   TEXT,
  at        TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_events_device_at ON events(device_id, at DESC);
`);

// Prepared statements are cached: node:sqlite recompiles on schema change otherwise.
const cache = new Map();
function stmt(sql) {
  let s = cache.get(sql);
  if (!s) {
    s = db.prepare(sql);
    cache.set(sql, s);
  }
  return s;
}

export const q = {
  get: (sql, ...params) => stmt(sql).get(...params),
  all: (sql, ...params) => stmt(sql).all(...params),
  run: (sql, ...params) => {
    const r = stmt(sql).run(...params);
    return { changes: Number(r.changes), lastInsertRowid: Number(r.lastInsertRowid) };
  },
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
 * Trims history beyond the retention window so the free-tier VPS disk stays healthy.
 * Devices and users are never touched — only their telemetry.
 */
export function purgeOldData(retentionDays = 90) {
  const cutoff = new Date(Date.now() - retentionDays * 86400_000).toISOString();
  const tables = ['locations', 'app_usage', 'calls', 'sms', 'media', 'events'];
  let removed = 0;
  for (const t of tables) {
    const { changes } = q.run(`DELETE FROM ${t} WHERE at < ?`, cutoff);
    removed += changes;
  }
  if (removed) console.log(`[guardian] purged ${removed} old rows (older than ${retentionDays}d)`);
  return removed;
}
