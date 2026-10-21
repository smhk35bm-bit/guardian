/**
 * Database schema shared by every driver.
 *
 * The statements live in an array because the libSQL/Turso wire protocol
 * accepts one statement per request, while node:sqlite happily runs a script.
 * `SCHEMA_SQL` is the joined form for drivers that accept multi-statement SQL.
 *
 * Keep this file free of semicolons inside string literals — the statements
 * are also replayed one-by-one over HTTP.
 */
export const SCHEMA_STATEMENTS = [
  `CREATE TABLE IF NOT EXISTS users (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  email         TEXT NOT NULL UNIQUE COLLATE NOCASE,
  password_hash TEXT NOT NULL,
  name          TEXT NOT NULL DEFAULT '',
  created_at    TEXT NOT NULL
)`,

  `CREATE TABLE IF NOT EXISTS devices (
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
)`,
  `CREATE INDEX IF NOT EXISTS idx_devices_user ON devices(user_id)`,

  `CREATE TABLE IF NOT EXISTS enrollment_codes (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  code       TEXT NOT NULL UNIQUE,
  device_id  INTEGER REFERENCES devices(id) ON DELETE SET NULL,
  expires_at TEXT NOT NULL,
  used_at    TEXT,
  created_at TEXT NOT NULL
)`,
  `CREATE INDEX IF NOT EXISTS idx_codes_user ON enrollment_codes(user_id)`,

  `CREATE TABLE IF NOT EXISTS locations (
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
)`,
  `CREATE INDEX IF NOT EXISTS idx_locations_device_at ON locations(device_id, at DESC)`,

  `CREATE TABLE IF NOT EXISTS app_usage (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id   INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  package     TEXT NOT NULL,
  label       TEXT,
  duration_ms INTEGER NOT NULL DEFAULT 0,
  opened_at   TEXT,
  foreground  INTEGER NOT NULL DEFAULT 1,
  event_type  TEXT NOT NULL DEFAULT 'app_usage',
  at          TEXT NOT NULL
)`,
  `CREATE INDEX IF NOT EXISTS idx_usage_device_at ON app_usage(device_id, at DESC)`,
  `CREATE INDEX IF NOT EXISTS idx_usage_device_pkg ON app_usage(device_id, package)`,

  `CREATE TABLE IF NOT EXISTS calls (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id    INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  number       TEXT,
  contact_name TEXT,
  call_type    TEXT NOT NULL DEFAULT 'outgoing',
  duration_sec INTEGER NOT NULL DEFAULT 0,
  at           TEXT NOT NULL
)`,
  `CREATE INDEX IF NOT EXISTS idx_calls_device_at ON calls(device_id, at DESC)`,

  `CREATE TABLE IF NOT EXISTS sms (
  id      INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  address TEXT,
  body    TEXT,
  msg_type TEXT NOT NULL DEFAULT 'inbox',
  at      TEXT NOT NULL
)`,
  `CREATE INDEX IF NOT EXISTS idx_sms_device_at ON sms(device_id, at DESC)`,

  `CREATE TABLE IF NOT EXISTS media (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  kind      TEXT NOT NULL DEFAULT 'screenshot',
  filename  TEXT NOT NULL,
  mime      TEXT,
  size      INTEGER NOT NULL DEFAULT 0,
  meta      TEXT,
  at        TEXT NOT NULL
)`,
  `CREATE INDEX IF NOT EXISTS idx_media_device_at ON media(device_id, at DESC)`,

  // Binary payload of a media row, split into chunks so a screenshot never
  // exceeds the row-size limit of the remote database (2 MB on D1/libSQL).
  `CREATE TABLE IF NOT EXISTS media_blobs (
  media_id INTEGER NOT NULL,
  seq      INTEGER NOT NULL,
  data     BLOB NOT NULL,
  PRIMARY KEY (media_id, seq)
)`,

  `CREATE TABLE IF NOT EXISTS installed_apps (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id    INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  package      TEXT NOT NULL,
  label        TEXT,
  version      TEXT,
  is_system    INTEGER NOT NULL DEFAULT 0,
  installed_at TEXT,
  at           TEXT NOT NULL,
  UNIQUE(device_id, package)
)`,
  `CREATE INDEX IF NOT EXISTS idx_apps_device ON installed_apps(device_id)`,

  `CREATE TABLE IF NOT EXISTS events (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  type      TEXT NOT NULL,
  payload   TEXT,
  at        TEXT NOT NULL
)`,
  `CREATE INDEX IF NOT EXISTS idx_events_device_at ON events(device_id, at DESC)`,
];

export const SCHEMA_SQL = `${SCHEMA_STATEMENTS.join(';\n')};`;
