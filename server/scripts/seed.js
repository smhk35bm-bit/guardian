#!/usr/bin/env node
/**
 * Guardian — demo data seeder.
 *
 * Creates a parent account and one device, then back-fills a week of plausible
 * history so the dashboard has something to show immediately.
 *
 *   node scripts/seed.js --email dad@example.com --password 'Str0ngPass!'
 */
import crypto from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { hashPassword } from '../src/lib/password.js';
import { config } from '../src/config.js';
// Importing db.js also creates the schema if this is a fresh database.
import { q, db } from '../src/db.js';

function arg(name, fallback = null) {
  const idx = process.argv.indexOf(`--${name}`);
  return idx !== -1 && process.argv[idx + 1] ? process.argv[idx + 1] : fallback;
}

const EMAIL = arg('email', 'dad@example.com');
const PASSWORD = arg('password', 'Guardian!2026');
const DAYS = Number(arg('days', '7'));

const iso = (d) => d.toISOString();

// ------------------------------------------------------------- parent
let user = q.get('SELECT * FROM users WHERE email = ?', EMAIL);
if (!user) {
  const { lastInsertRowid } = q.run(
    'INSERT INTO users (email, password_hash, name, created_at) VALUES (?, ?, ?, ?)',
    EMAIL,
    hashPassword(PASSWORD),
    'ولي الأمر',
    iso(new Date()),
  );
  user = q.get('SELECT * FROM users WHERE id = ?', lastInsertRowid);
  console.log(`[seed] created parent ${EMAIL}`);
} else {
  console.log(`[seed] parent ${EMAIL} already exists`);
}

// ------------------------------------------------------------- device
const deviceToken = crypto.randomBytes(32).toString('base64url');
const { lastInsertRowid: deviceId } = q.run(
  `INSERT INTO devices
     (user_id, name, manufacturer, model, android_version, sdk_int, app_version, device_token, status, created_at)
   VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'active', ?)`,
  user.id,
  'هاتف الطفل',
  'Xiaomi',
  'Redmi Note 12',
  '13',
  33,
  '1.0.0',
  deviceToken,
  iso(new Date()),
);
console.log(`[seed] created device #${deviceId}`);

// ------------------------------------------------------------- history
const APPS = [
  { package: 'com.instagram.android', label: 'Instagram', base: 45 },
  { package: 'com.whatsapp', label: 'WhatsApp', base: 35 },
  { package: 'com.google.android.youtube', label: 'YouTube', base: 60 },
  { package: 'com.snapchat.android', label: 'Snapchat', base: 25 },
  { package: 'com.android.chrome', label: 'Chrome', base: 20 },
  { package: 'com.game.puzzle', label: 'لعبة ألغاز', base: 30 },
];

const CONTACTS = [
  { number: '+966500111222', name: 'أم محمد' },
  { number: '+966500333444', name: 'المدرسة' },
  { number: '+966500555666', name: 'الجد' },
  { number: '+966500777888', name: 'صديق أحمد' },
];

const CENTER = { lat: 24.7136, lng: 46.6753 };
const rand = (min, max) => min + Math.random() * (max - min);
const pick = (arr) => arr[Math.floor(Math.random() * arr.length)];

const insertLocation = db.prepare(
  `INSERT INTO locations (device_id, lat, lng, accuracy, speed, altitude, bearing, provider, battery, at)
   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
);
const insertUsage = db.prepare(
  `INSERT INTO app_usage (device_id, package, label, duration_ms, opened_at, foreground, event_type, at)
   VALUES (?, ?, ?, ?, ?, 1, 'app_usage', ?)`,
);
const insertCall = db.prepare(
  `INSERT INTO calls (device_id, number, contact_name, call_type, duration_sec, at) VALUES (?, ?, ?, ?, ?, ?)`,
);
const insertSms = db.prepare(
  `INSERT INTO sms (device_id, address, body, msg_type, at) VALUES (?, ?, ?, ?, ?)`,
);
const insertApp = db.prepare(
  `INSERT OR REPLACE INTO installed_apps (device_id, package, label, version, is_system, installed_at, at)
   VALUES (?, ?, ?, ?, ?, ?, ?)`,
);

db.exec('BEGIN');
try {
  for (const app of APPS) {
    insertApp.run(deviceId, app.package, app.label, '1.0.0', 0, iso(new Date(Date.now() - 30 * 86400_000)), iso(new Date()));
  }
  insertApp.run(deviceId, 'com.android.settings', 'الإعدادات', '13', 1, null, iso(new Date()));
  insertApp.run(deviceId, 'com.android.chrome', 'Chrome', '120', 1, null, iso(new Date()));

  const steps = DAYS * 48; // a location point every ~30 minutes
  for (let i = 0; i < steps; i += 1) {
    const at = new Date(Date.now() - i * 30 * 60_000);
    const angle = i / 7;
    const radius = 0.006 + Math.sin(i / 11) * 0.004;
    const battery = Math.max(10, 100 - (i % 60));

    insertLocation.run(
      deviceId,
      CENTER.lat + Math.sin(angle) * radius,
      CENTER.lng + Math.cos(angle) * radius,
      Math.round(rand(5, 25)),
      Number(rand(0, 6).toFixed(2)),
      Math.round(rand(590, 640)),
      Math.round(rand(0, 359)),
      i % 3 === 0 ? 'network' : 'gps',
      battery,
      iso(at),
    );

    if (i % 3 === 0) {
      const app = pick(APPS);
      const minutes = Math.max(1, Math.round(rand(app.base * 0.4, app.base)));
      insertUsage.run(
        deviceId,
        app.package,
        app.label,
        minutes * 60_000,
        iso(new Date(at.getTime() - minutes * 60_000)),
        iso(at),
      );
    }

    if (i % 23 === 0) {
      const c = pick(CONTACTS);
      const types = ['incoming', 'outgoing', 'incoming', 'missed'];
      insertCall.run(deviceId, c.number, c.name, pick(types), Math.round(rand(0, 400)), iso(at));
    }

    if (i % 17 === 0) {
      const c = pick(CONTACTS);
      insertSms.run(
        deviceId,
        c.number,
        pick([
          'متى تصل؟',
          'راجعت الواجب اليوم',
          'ما شاء الله عليك 👏',
          'اتصل على جدك',
          'نلتقي غداً إن شاء الله',
        ]),
        pick(['inbox', 'inbox', 'sent']),
        iso(at),
      );
    }
  }
  db.exec('COMMIT');
} catch (err) {
  db.exec('ROLLBACK');
  console.error('[seed] failed:', err.message);
  process.exit(1);
}

const counts = {
  locations: q.get('SELECT COUNT(*) AS c FROM locations WHERE device_id = ?', deviceId).c,
  usage: q.get('SELECT COUNT(*) AS c FROM app_usage WHERE device_id = ?', deviceId).c,
  calls: q.get('SELECT COUNT(*) AS c FROM calls WHERE device_id = ?', deviceId).c,
  sms: q.get('SELECT COUNT(*) AS c FROM sms WHERE device_id = ?', deviceId).c,
};

console.log('[seed] done:', counts);
console.log(`[seed] login with  ${EMAIL}  /  ${PASSWORD}`);
console.log(`[seed] database    ${path.relative(process.cwd(), config.dbFile)}`);
