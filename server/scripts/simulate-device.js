#!/usr/bin/env node
/**
 * Guardian — device simulator.
 *
 * Pretends to be the child's phone: enrolls itself, then streams realistic
 * telemetry to the server so the dashboard can be exercised end-to-end
 * without building the Android app first.
 *
 *   node scripts/simulate-device.js --email dad@example.com --password 'Str0ngPass!'
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');

function arg(name, fallback = null) {
  const idx = process.argv.indexOf(`--${name}`);
  return idx !== -1 && process.argv[idx + 1] ? process.argv[idx + 1] : fallback;
}

const BASE = (arg('url', 'http://localhost:3000')).replace(/\/+$/, '');
const EMAIL = arg('email', 'dad@example.com');
const PASSWORD = arg('password', 'Guardian!2026');
const DEVICE_NAME = arg('name', 'هاتف تجريبي');
const SPEED = Number(arg('speed', '1')); // time multiplier for the demo loop

const AUTH_FILE = path.join(root, 'data', 'simulator-auth.json');

async function call(pathname, { method = 'GET', token, body, headers = {} } = {}) {
  const h = { ...headers };
  if (token) h.Authorization = `Bearer ${token}`;
  if (body !== undefined) h['Content-Type'] = 'application/json';
  const res = await fetch(`${BASE}${pathname}`, {
    method,
    headers: h,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(`${pathname} -> ${res.status} ${json?.error?.message || ''}`);
  }
  return json;
}

function saveAuth(data) {
  fs.mkdirSync(path.dirname(AUTH_FILE), { recursive: true });
  fs.writeFileSync(AUTH_FILE, JSON.stringify(data, null, 2));
}

function loadAuth() {
  if (!fs.existsSync(AUTH_FILE)) return null;
  try {
    return JSON.parse(fs.readFileSync(AUTH_FILE, 'utf8'));
  } catch {
    return null;
  }
}

async function ensureParent() {
  const cached = loadAuth();
  if (cached?.parentToken && cached?.email === EMAIL) {
    // Validate the cached token before trusting it.
    try {
      await call('/api/auth/me', { token: cached.parentToken });
      console.log(`[sim] using cached parent session for ${EMAIL}`);
      return cached.parentToken;
    } catch {
      /* stale — fall through to a fresh login */
    }
  }

  try {
    const res = await call('/api/auth/login', {
      method: 'POST',
      body: { email: EMAIL, password: PASSWORD },
    });
    console.log(`[sim] logged in as ${EMAIL}`);
    return res.data.token;
  } catch {
    const res = await call('/api/auth/register', {
      method: 'POST',
      body: { email: EMAIL, password: PASSWORD, name: 'ولي الأمر (محاكاة)' },
    });
    console.log(`[sim] created parent account ${EMAIL}`);
    return res.data.token;
  }
}

/** The device registers itself using a fresh enrollment code from the parent. */
async function enrollDevice(parentToken) {
  const codeRes = await call('/api/devices/enroll-code', { method: 'POST', token: parentToken });
  const code = codeRes.data.code;

  const res = await fetch(`${BASE}/api/devices/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      code,
      deviceName: DEVICE_NAME,
      manufacturer: 'Simulated',
      model: 'Guardian-Sim',
      androidVersion: '14',
      sdkInt: 34,
      appVersion: '1.0.0-sim',
    }),
  });
  const json = await res.json();
  if (!res.ok) throw new Error(`register -> ${res.status} ${json?.error?.message || ''}`);
  console.log(`[sim] enrolled device #${json.data.deviceId} with code ${code}`);
  return json.data;
}

/** A 1x1 PNG — enough to prove the media pipeline works. */
const TINY_PNG = Buffer.from(
  '89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000a49444154789c6300010000050001' +
    '0d0a2db40000000049454e44ae426082',
  'hex',
);

async function uploadScreenshot(deviceId, deviceToken, appLabel) {
  const form = new FormData();
  form.append('file', new Blob([TINY_PNG], { type: 'image/png' }), 'screen.png');
  form.append('meta', JSON.stringify({ app: 'com.sim.app', label: appLabel }));

  const res = await fetch(`${BASE}/api/devices/${deviceId}/screenshots`, {
    method: 'POST',
    headers: { Authorization: `Device ${deviceToken}` },
    body: form,
  });
  if (!res.ok) console.warn('[sim] screenshot upload failed', res.status);
}

const APPS = [
  { package: 'com.instagram.android', label: 'Instagram', weight: 5 },
  { package: 'com.whatsapp', label: 'WhatsApp', weight: 4 },
  { package: 'com.google.android.youtube', label: 'YouTube', weight: 6 },
  { package: 'com.snapchat.android', label: 'Snapchat', weight: 3 },
  { package: 'com.microsoft.office.outlook', label: 'Outlook', weight: 2 },
  { package: 'com.game.example', label: 'لعبة تجريبية', weight: 5 },
];

const CONTACTS = [
  { number: '+966500111222', name: 'أم محمد' },
  { number: '+966500333444', name: 'المدرسة' },
  { number: '+966500555666', name: 'الجد' },
];

// A slow loop around central Riyadh so the map trace looks like movement.
const CENTER = { lat: 24.7136, lng: 46.6753 };

async function main() {
  console.log(`[sim] target server: ${BASE}`);
  const parentToken = await ensureParent();
  const { deviceId, deviceToken } = await enrollDevice(parentToken);
  saveAuth({ email: EMAIL, parentToken, deviceId });

  let angle = 0;
  let tick = 0;
  const battery = 100;

  console.log('[sim] streaming telemetry every 5s — press Ctrl+C to stop\n');

  const timer = setInterval(async () => {
    tick += 1;
    angle += 0.25;

    try {
      // --- heartbeat -------------------------------------------------
      const bat = Math.max(8, 100 - Math.floor(tick / 6));
      await call(`/api/devices/${deviceId}/heartbeat`, {
        method: 'POST',
        token: deviceToken,
        body: {
          battery: bat,
          charging: tick % 24 < 6,
          wifi: 'Home-5G',
          networkType: 'wifi',
          storageFreeMb: 8000 + tick,
          uptimeSeconds: tick * 5,
          screenOn: true,
          appVersion: '1.0.0-sim',
        },
      });

      // --- location --------------------------------------------------
      const radius = 0.004 + Math.sin(tick / 9) * 0.003;
      const lat = CENTER.lat + Math.sin(angle) * radius;
      const lng = CENTER.lng + Math.cos(angle) * radius;
      await call(`/api/devices/${deviceId}/location`, {
        method: 'POST',
        token: deviceToken,
        body: {
          lat,
          lng,
          accuracy: 8 + (tick % 12),
          speed: 1.2,
          altitude: 612,
          provider: tick % 2 ? 'gps' : 'network',
          battery: bat,
        },
      });

      // --- app usage -------------------------------------------------
      if (tick % 3 === 0) {
        const app = APPS[tick % APPS.length];
        await call(`/api/devices/${deviceId}/events`, {
          method: 'POST',
          token: deviceToken,
          body: {
            type: 'app_usage',
            package: app.package,
            label: app.label,
            durationMs: app.weight * 60_000,
            openedAt: new Date().toISOString(),
            foreground: true,
          },
        });
      }

      // --- calls -----------------------------------------------------
      if (tick % 11 === 0) {
        const contact = CONTACTS[tick % CONTACTS.length];
        await call(`/api/devices/${deviceId}/calls`, {
          method: 'POST',
          token: deviceToken,
          body: {
            number: contact.number,
            contactName: contact.name,
            type: tick % 22 === 0 ? 'missed' : tick % 2 ? 'incoming' : 'outgoing',
            durationSec: 30 + (tick % 200),
          },
        });
      }

      // --- sms -------------------------------------------------------
      if (tick % 13 === 0) {
        const contact = CONTACTS[tick % CONTACTS.length];
        await call(`/api/devices/${deviceId}/sms`, {
          method: 'POST',
          token: deviceToken,
          body: {
            address: contact.number,
            body: 'رسالة تجريبية من المحاكي — greetings from the simulator.',
            type: tick % 2 ? 'inbox' : 'sent',
          },
        });
      }

      // --- installed apps --------------------------------------------
      if (tick % 40 === 1) {
        await call(`/api/devices/${deviceId}/apps`, {
          method: 'POST',
          token: deviceToken,
          body: {
            apps: [
              ...APPS.map((a, i) => ({
                package: a.package,
                label: a.label,
                version: `1.${i}.0`,
                system: false,
                installedAt: new Date(Date.now() - i * 86400_000).toISOString(),
              })),
              { package: 'com.android.settings', label: 'الإعدادات', version: '14', system: true },
              { package: 'com.android.chrome', label: 'Chrome', version: '120', system: true },
            ],
          },
        });
      }

      // --- screenshot ------------------------------------------------
      if (tick % 7 === 0) {
        await uploadScreenshot(deviceId, deviceToken, APPS[tick % APPS.length].label);
      }
    } catch (err) {
      console.error('[sim] error:', err.message);
    }
  }, 5000 / SPEED);

  const stop = () => {
    clearInterval(timer);
    console.log('\n[sim] stopped');
    process.exit(0);
  };
  process.on('SIGINT', stop);
  process.on('SIGTERM', stop);
}

main().catch((err) => {
  console.error('[sim] fatal:', err.message);
  process.exit(1);
});
