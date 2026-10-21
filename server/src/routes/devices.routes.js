import crypto from 'node:crypto';
import path from 'node:path';
import fs from 'node:fs';
import { Router } from 'express';
import multer from 'multer';
import { q, nowIso } from '../db.js';
import { issueToken } from '../lib/jwt.js';
import { config } from '../config.js';
import { ok, fail, asyncHandler, toInt, toNum, toBool, cleanText, isoOrNow } from '../lib/util.js';
import { requireParent, requireDevice, requireOwnDevice } from '../middleware/auth.js';
import { broadcastToUser } from '../realtime.js';

export const devicesRouter = Router();

const CODE_TTL_MIN = 10;
const CODE_LENGTH = 8;

/** Uploads land on disk with a random name; the DB keeps the original metadata. */
const upload = multer({
  storage: multer.diskStorage({
    destination: (_req, _file, cb) => cb(null, config.uploadDir),
    filename: (_req, file, cb) => {
      const ext = {
        'image/png': '.png',
        'image/jpeg': '.jpg',
        'image/webp': '.webp',
      }[file.mimetype] || '.bin';
      cb(null, `${Date.now()}-${crypto.randomBytes(8).toString('hex')}${ext}`);
    },
  }),
  limits: { fileSize: config.maxUploadBytes, files: 1 },
});

function touchDevice(deviceId, patch = {}) {
  const fields = [];
  const values = [];
  for (const [key, value] of Object.entries(patch)) {
    if (value === undefined) continue;
    fields.push(`${key} = ?`);
    values.push(value);
  }
  if (!fields.length) return;
  fields.push('last_seen = ?');
  values.push(nowIso(), deviceId);
  q.run(`UPDATE devices SET ${fields.join(', ')} WHERE id = ?`, ...values);
}

function deviceWithLocation(device) {
  const loc = q.get(
    'SELECT lat, lng, accuracy, speed, provider, battery, at FROM locations WHERE device_id = ? ORDER BY at DESC LIMIT 1',
    device.id,
  );
  return {
    id: device.id,
    name: device.name,
    manufacturer: device.manufacturer,
    model: device.model,
    androidVersion: device.android_version,
    appVersion: device.app_version,
    status: device.status,
    lastSeen: device.last_seen,
    battery: device.battery,
    charging: Boolean(device.charging),
    wifi: device.wifi,
    networkType: device.network_type,
    screenOn: device.screen_on === null ? null : Boolean(device.screen_on),
    createdAt: device.created_at,
    location: loc
      ? {
          lat: loc.lat,
          lng: loc.lng,
          accuracy: loc.accuracy,
          speed: loc.speed,
          provider: loc.provider,
          battery: loc.battery,
          at: loc.at,
        }
      : null,
  };
}

// ---------------------------------------------------------------- enrollment

/** Parent generates a short-lived one-time code that the child's phone redeems. */
devicesRouter.post(
  '/enroll-code',
  requireParent,
  asyncHandler(async (req, res) => {
    // Invalidate any still-open codes so only the newest one works.
    q.run('DELETE FROM enrollment_codes WHERE user_id = ? AND used_at IS NULL', req.user.id);

    let code = null;
    for (let attempt = 0; attempt < 10 && !code; attempt += 1) {
      const candidate = String(crypto.randomInt(0, 10 ** CODE_LENGTH)).padStart(CODE_LENGTH, '0');
      if (!q.get('SELECT id FROM enrollment_codes WHERE code = ?', candidate)) code = candidate;
    }
    if (!code) return fail(res, 500, 'INTERNAL', 'تعذّر توليد كود التسجيل');

    const expiresAt = new Date(Date.now() + CODE_TTL_MIN * 60_000).toISOString();
    q.run(
      'INSERT INTO enrollment_codes (user_id, code, expires_at, created_at) VALUES (?, ?, ?, ?)',
      req.user.id,
      code,
      expiresAt,
      nowIso(),
    );

    return ok(res, { code, expiresAt, ttlMinutes: CODE_TTL_MIN }, undefined, 201);
  }),
);

/** The child's app redeems the code and receives its permanent device token. */
devicesRouter.post(
  '/register',
  asyncHandler(async (req, res) => {
    const code = String(req.body?.code ?? '').trim();
    if (!/^\d{8}$/.test(code)) {
      return fail(res, 400, 'CODE_INVALID', 'كود التسجيل غير صحيح');
    }

    const row = q.get('SELECT * FROM enrollment_codes WHERE code = ?', code);
    if (!row) return fail(res, 400, 'CODE_INVALID', 'كود التسجيل غير صحيح');
    if (row.used_at) return fail(res, 409, 'CODE_USED', 'هذا الكود استُخدم مسبقاً');
    if (new Date(row.expires_at).getTime() < Date.now()) {
      return fail(res, 400, 'CODE_INVALID', 'انتهت صلاحية كود التسجيل');
    }

    const deviceToken = crypto.randomBytes(32).toString('base64url');
    const name = cleanText(req.body?.deviceName, 80) || cleanText(req.body?.model, 80) || 'جهاز الطفل';

    const { lastInsertRowid: deviceId } = q.run(
      `INSERT INTO devices
         (user_id, name, manufacturer, model, android_version, sdk_int, app_version, device_token, status, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'active', ?)`,
      row.user_id,
      name,
      cleanText(req.body?.manufacturer, 60),
      cleanText(req.body?.model, 60),
      cleanText(req.body?.androidVersion, 30),
      toInt(req.body?.sdkInt),
      cleanText(req.body?.appVersion, 30),
      deviceToken,
      nowIso(),
    );

    q.run('UPDATE enrollment_codes SET used_at = ?, device_id = ? WHERE id = ?', nowIso(), deviceId, row.id);

    // The JWT carries the opaque token as `jti`; the server compares them on every request.
    const token = issueToken({ sub: deviceId, jti: deviceToken }, { kind: 'device', days: config.deviceTokenDays });

    const device = q.get('SELECT * FROM devices WHERE id = ?', deviceId);
    broadcastToUser(row.user_id, {
      type: 'device.enrolled',
      deviceId,
      data: { name: device.name, model: device.model },
    });

    return ok(res, {
      deviceId,
      deviceToken: token,
      serverUrl: config.publicUrl ? `${config.publicUrl}/api` : '/api',
    });
  }),
);

// ------------------------------------------------------------------ parent

devicesRouter.get(
  '/',
  requireParent,
  asyncHandler(async (req, res) => {
    const devices = q.all('SELECT * FROM devices WHERE user_id = ? ORDER BY id DESC', req.user.id);
    return ok(res, devices.map(deviceWithLocation), { count: devices.length });
  }),
);

devicesRouter.get(
  '/:id',
  requireParent,
  asyncHandler(async (req, res) => {
    const device = q.get('SELECT * FROM devices WHERE id = ? AND user_id = ?', req.params.id, req.user.id);
    if (!device) return fail(res, 404, 'NOT_FOUND', 'الجهاز غير موجود');
    return ok(res, deviceWithLocation(device));
  }),
);

devicesRouter.patch(
  '/:id',
  requireParent,
  asyncHandler(async (req, res) => {
    const device = q.get('SELECT * FROM devices WHERE id = ? AND user_id = ?', req.params.id, req.user.id);
    if (!device) return fail(res, 404, 'NOT_FOUND', 'الجهاز غير موجود');
    const name = cleanText(req.body?.name, 80);
    if (name) q.run('UPDATE devices SET name = ? WHERE id = ?', name, device.id);
    const updated = q.get('SELECT * FROM devices WHERE id = ?', device.id);
    return ok(res, deviceWithLocation(updated));
  }),
);

/** Revokes the device token — the phone must be re-enrolled with a new code. */
devicesRouter.delete(
  '/:id',
  requireParent,
  asyncHandler(async (req, res) => {
    const device = q.get('SELECT * FROM devices WHERE id = ? AND user_id = ?', req.params.id, req.user.id);
    if (!device) return fail(res, 404, 'NOT_FOUND', 'الجهاز غير موجود');
    q.run("UPDATE devices SET device_token = NULL, status = 'revoked' WHERE id = ?", device.id);
    broadcastToUser(req.user.id, { type: 'device.revoked', deviceId: device.id, data: {} });
    return ok(res, { revoked: true, deviceId: device.id });
  }),
);

// ------------------------------------------------------------------ device

devicesRouter.post(
  '/:id/heartbeat',
  requireDevice,
  requireOwnDevice,
  asyncHandler(async (req, res) => {
    const body = req.body ?? {};
    touchDevice(req.device.id, {
      battery: toInt(body.battery),
      charging: toBool(body.charging) === null ? null : toBool(body.charging) ? 1 : 0,
      wifi: cleanText(body.wifi, 80),
      network_type: cleanText(body.networkType, 30),
      screen_on: toBool(body.screenOn) === null ? null : toBool(body.screenOn) ? 1 : 0,
      app_version: cleanText(body.appVersion, 30),
    });

    const payload = {
      type: 'device.heartbeat',
      deviceId: req.device.id,
      data: {
        battery: toInt(body.battery),
        charging: toBool(body.charging),
        wifi: cleanText(body.wifi, 80),
        networkType: cleanText(body.networkType, 30),
        at: nowIso(),
      },
    };
    broadcastToUser(req.device.user_id, payload);

    return ok(res, {
      serverTime: nowIso(),
      config: { locationIntervalSec: 60, usageIntervalSec: 300, heartbeatIntervalSec: 120 },
    });
  }),
);

devicesRouter.post(
  '/:id/location',
  requireDevice,
  requireOwnDevice,
  asyncHandler(async (req, res) => {
    const body = req.body ?? {};
    const lat = toNum(body.lat);
    const lng = toNum(body.lng);
    if (lat === null || lng === null || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
      return fail(res, 400, 'BAD_REQUEST', 'إحداثيات غير صالحة');
    }
    const at = isoOrNow(body.at);
    const { lastInsertRowid } = q.run(
      `INSERT INTO locations
         (device_id, lat, lng, accuracy, speed, altitude, bearing, provider, battery, at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      req.device.id,
      lat,
      lng,
      toNum(body.accuracy),
      toNum(body.speed),
      toNum(body.altitude),
      toNum(body.bearing),
      cleanText(body.provider, 20),
      toInt(body.battery),
      at,
    );
    touchDevice(req.device.id, { battery: toInt(body.battery) });

    broadcastToUser(req.device.user_id, {
      type: 'device.location',
      deviceId: req.device.id,
      data: {
        id: lastInsertRowid,
        lat,
        lng,
        accuracy: toNum(body.accuracy),
        speed: toNum(body.speed),
        provider: cleanText(body.provider, 20),
        battery: toInt(body.battery),
        at,
      },
    });

    return ok(res, { id: lastInsertRowid, at });
  }),
);

const EVENT_TYPES = new Set([
  'app_usage',
  'app_installed',
  'app_removed',
  'browser_history',
  'notification',
  'custom',
]);

devicesRouter.post(
  '/:id/events',
  requireDevice,
  requireOwnDevice,
  asyncHandler(async (req, res) => {
    const body = req.body ?? {};
    const type = String(body.type ?? 'custom');
    const at = isoOrNow(body.at ?? body.openedAt);
    const pkg = cleanText(body.package, 200);
    const label = cleanText(body.label, 200);
    const durationMs = toInt(body.durationMs, 0) ?? 0;

    if (type === 'app_usage') {
      const { lastInsertRowid } = q.run(
        `INSERT INTO app_usage
           (device_id, package, label, duration_ms, opened_at, foreground, event_type, at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
        req.device.id,
        pkg ?? 'unknown',
        label,
        durationMs,
        isoOrNow(body.openedAt),
        toBool(body.foreground) === false ? 0 : 1,
        type,
        at,
      );
      broadcastToUser(req.device.user_id, {
        type: 'device.usage',
        deviceId: req.device.id,
        data: { id: lastInsertRowid, package: pkg, label, durationMs, at },
      });
      return ok(res, { id: lastInsertRowid });
    }

    const payload = JSON.stringify({
      package: pkg,
      label,
      durationMs,
      title: cleanText(body.title, 200),
      text: cleanText(body.text, 1000),
      url: cleanText(body.url, 1000),
      extra: body.extra ?? null,
    });
    const { lastInsertRowid } = q.run(
      'INSERT INTO events (device_id, type, payload, at) VALUES (?, ?, ?, ?)',
      req.device.id,
      type,
      payload,
      at,
    );
    broadcastToUser(req.device.user_id, {
      type: `device.${type}`,
      deviceId: req.device.id,
      data: { id: lastInsertRowid, at },
    });
    return ok(res, { id: lastInsertRowid });
  }),
);

const CALL_TYPES = new Set(['incoming', 'outgoing', 'missed', 'rejected']);

devicesRouter.post(
  '/:id/calls',
  requireDevice,
  requireOwnDevice,
  asyncHandler(async (req, res) => {
    const body = req.body ?? {};
    const callType = CALL_TYPES.has(String(body.type)) ? String(body.type) : 'outgoing';
    const at = isoOrNow(body.at);
    const { lastInsertRowid } = q.run(
      'INSERT INTO calls (device_id, number, contact_name, call_type, duration_sec, at) VALUES (?, ?, ?, ?, ?, ?)',
      req.device.id,
      cleanText(body.number, 40),
      cleanText(body.contactName, 120),
      callType,
      toInt(body.durationSec, 0) ?? 0,
      at,
    );
    broadcastToUser(req.device.user_id, {
      type: 'device.call',
      deviceId: req.device.id,
      data: {
        id: lastInsertRowid,
        number: cleanText(body.number, 40),
        contactName: cleanText(body.contactName, 120),
        callType,
        durationSec: toInt(body.durationSec, 0) ?? 0,
        at,
      },
    });
    return ok(res, { id: lastInsertRowid });
  }),
);

const SMS_TYPES = new Set(['inbox', 'sent', 'draft', 'outbox']);

devicesRouter.post(
  '/:id/sms',
  requireDevice,
  requireOwnDevice,
  asyncHandler(async (req, res) => {
    const body = req.body ?? {};
    const msgType = SMS_TYPES.has(String(body.type)) ? String(body.type) : 'inbox';
    const at = isoOrNow(body.at);
    const { lastInsertRowid } = q.run(
      'INSERT INTO sms (device_id, address, body, msg_type, at) VALUES (?, ?, ?, ?, ?)',
      req.device.id,
      cleanText(body.address, 40),
      cleanText(body.body, 4000),
      msgType,
      at,
    );
    broadcastToUser(req.device.user_id, {
      type: 'device.sms',
      deviceId: req.device.id,
      data: { id: lastInsertRowid, at },
    });
    return ok(res, { id: lastInsertRowid });
  }),
);

devicesRouter.post(
  '/:id/apps',
  requireDevice,
  requireOwnDevice,
  asyncHandler(async (req, res) => {
    const list = Array.isArray(req.body?.apps) ? req.body.apps.slice(0, 1000) : [];
    q.run('DELETE FROM installed_apps WHERE device_id = ?', req.device.id);
    let inserted = 0;
    for (const app of list) {
      const pkg = cleanText(app?.package, 200);
      if (!pkg) continue;
      q.run(
        `INSERT OR REPLACE INTO installed_apps
           (device_id, package, label, version, is_system, installed_at, at)
         VALUES (?, ?, ?, ?, ?, ?, ?)`,
        req.device.id,
        pkg,
        cleanText(app?.label, 200),
        cleanText(app?.version, 60),
        toBool(app?.system) ? 1 : 0,
        isoOrNow(app?.installedAt),
        nowIso(),
      );
      inserted += 1;
    }
    return ok(res, { count: inserted });
  }),
);

async function handleMediaUpload(req, res, kind) {
  const file = req.file;
  if (!file) return fail(res, 400, 'BAD_REQUEST', 'لم يتم إرسال أي ملف');

  let meta = null;
  if (req.body?.meta) {
    try {
      meta = JSON.stringify(JSON.parse(req.body.meta));
    } catch {
      meta = null;
    }
  }

  const at = nowIso();
  const { lastInsertRowid } = q.run(
    'INSERT INTO media (device_id, kind, filename, mime, size, meta, at) VALUES (?, ?, ?, ?, ?, ?, ?)',
    req.device.id,
    kind,
    file.filename,
    file.mimetype,
    file.size,
    meta,
    at,
  );

  broadcastToUser(req.device.user_id, {
    type: `device.${kind}`,
    deviceId: req.device.id,
    data: { id: lastInsertRowid, size: file.size, at },
  });

  return ok(
    res,
    {
      id: lastInsertRowid,
      kind,
      url: `/api/devices/${req.device.id}/media/${lastInsertRowid}`,
      size: file.size,
      at,
    },
    undefined,
    201,
  );
}

devicesRouter.post(
  '/:id/screenshots',
  requireDevice,
  requireOwnDevice,
  upload.single('file'),
  asyncHandler(async (req, res) => handleMediaUpload(req, res, 'screenshot')),
);

devicesRouter.post(
  '/:id/photos',
  requireDevice,
  requireOwnDevice,
  upload.single('file'),
  asyncHandler(async (req, res) => handleMediaUpload(req, res, 'camera')),
);

export { deviceWithLocation };
