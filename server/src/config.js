import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');

/** Minimal .env loader: fills process.env without overwriting real env vars. */
function loadEnvFile(file) {
  if (!fs.existsSync(file)) return;
  const text = fs.readFileSync(file, 'utf8');
  for (const rawLine of text.split('\n')) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq === -1) continue;
    const key = line.slice(0, eq).trim();
    let value = line.slice(eq + 1).trim();
    if (
      (value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1);
    }
    if (process.env[key] === undefined) process.env[key] = value;
  }
}

loadEnvFile(path.join(root, '.env'));

const int = (value, fallback) => {
  const n = Number.parseInt(value ?? '', 10);
  return Number.isFinite(n) ? n : fallback;
};

const DATA_DIR = path.resolve(root, process.env.DATA_DIR || './data');
const UPLOAD_DIR = path.resolve(root, process.env.UPLOAD_DIR || './uploads');

fs.mkdirSync(DATA_DIR, { recursive: true });
fs.mkdirSync(UPLOAD_DIR, { recursive: true });

export const config = {
  root,
  port: int(process.env.PORT, 3000),
  publicUrl: (process.env.PUBLIC_URL || '').replace(/\/+$/, ''),
  jwtSecret: process.env.JWT_SECRET || '',
  parentTokenDays: int(process.env.PARENT_TOKEN_DAYS, 30),
  deviceTokenDays: int(process.env.DEVICE_TOKEN_DAYS, 365),
  dataDir: DATA_DIR,
  uploadDir: UPLOAD_DIR,
  dbFile: path.join(DATA_DIR, 'guardian.db'),
  maxUploadBytes: int(process.env.MAX_UPLOAD_BYTES, 10 * 1024 * 1024),
  authRateLimit: int(process.env.AUTH_RATE_LIMIT, 10),
  authRateWindowMin: int(process.env.AUTH_RATE_WINDOW_MIN, 15),
  deviceRateLimit: int(process.env.DEVICE_RATE_LIMIT, 600),
  isProd: process.env.NODE_ENV !== 'development',
};

if (!config.jwtSecret) {
  // A generated secret keeps dev usable but breaks sessions on restart, so warn loudly.
  console.warn(
    '[guardian] JWT_SECRET is not set — using an ephemeral random secret. ' +
      'Sessions will not survive a restart. Set JWT_SECRET in .env for production.',
  );
  config.jwtSecret = fs.readFileSync('/dev/urandom', { encoding: 'utf8', flag: 'r' }).slice(0, 64);
}
