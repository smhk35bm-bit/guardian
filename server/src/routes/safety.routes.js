import { Router } from 'express';
import { q, nowIso } from '../db/index.js';
import { ok, fail, asyncHandler, parseLimit, toNum, toInt, cleanText, toBool } from '../lib/util.js';
import { requireParent } from '../middleware/auth.js';

/**
 * Parent-facing safety features: safe zones, the alert inbox and the address
 * book of the child device.
 *
 * Every route is scoped to a device that belongs to the authenticated parent;
 * the ownership check is shared with the other routers so a wrong id can never
 * leak another family's data.
 */
export const safetyRouter = Router();

async function ownedDevice(req, res) {
  const id = toInt(req.params.id);
  if (id === null) {
    fail(res, 400, 'BAD_REQUEST', 'معرّف جهاز غير صالح');
    return null;
  }
  const device = await q.get('SELECT * FROM devices WHERE id = ? AND user_id = ?', id, req.user.id);
  if (!device) {
    fail(res, 404, 'NOT_FOUND', 'الجهاز غير موجود');
    return null;
  }
  return device;
}

/* ------------------------------------------------------------ safe zones */

safetyRouter.get(
  '/devices/:id/geofences',
  requireParent,
  asyncHandler(async (req, res) => {
    const device = await ownedDevice(req, res);
    if (!device) return;
    const rows = await q.all(
      'SELECT * FROM geofences WHERE device_id = ? ORDER BY created_at DESC',
      device.id,
    );
    return ok(res, rows, { count: rows.length });
  }),
);

safetyRouter.post(
  '/devices/:id/geofences',
  requireParent,
  asyncHandler(async (req, res) => {
    const device = await ownedDevice(req, res);
    if (!device) return;

    const body = req.body ?? {};
    const lat = toNum(body.lat);
    const lng = toNum(body.lng);
    if (lat === null || lng === null || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
      return fail(res, 400, 'BAD_REQUEST', 'إحداثيات غير صالحة');
    }
    const radius = Math.round(toNum(body.radiusM) ?? 200);
    if (radius < 30 || radius > 20_000) {
      return fail(res, 400, 'BAD_REQUEST', 'نصف القطر يجب أن يكون بين ٣٠ و٢٠٠٠٠ متر');
    }
    const name = cleanText(body.name, 120) || 'موقع آمن';
    const createdAt = nowIso();
    const { lastInsertRowid } = await q.run(
      `INSERT INTO geofences (device_id, name, lat, lng, radius_m, inside, active, created_at)
       VALUES (?, ?, ?, ?, ?, NULL, 1, ?)`,
      device.id,
      name,
      lat,
      lng,
      radius,
      createdAt,
    );
    const row = await q.get('SELECT * FROM geofences WHERE id = ?', lastInsertRowid);
    return ok(res, row, undefined, 201);
  }),
);

safetyRouter.patch(
  '/devices/:id/geofences/:geoId',
  requireParent,
  asyncHandler(async (req, res) => {
    const device = await ownedDevice(req, res);
    if (!device) return;
    const geoId = toInt(req.params.geoId);
    if (geoId === null) return fail(res, 400, 'BAD_REQUEST', 'معرّف غير صالح');

    const existing = await q.get(
      'SELECT * FROM geofences WHERE id = ? AND device_id = ?',
      geoId,
      device.id,
    );
    if (!existing) return fail(res, 404, 'NOT_FOUND', 'الموقع غير موجود');

    const body = req.body ?? {};
    const name = body.name === undefined ? existing.name : cleanText(body.name, 120) || existing.name;
    const radius = body.radiusM === undefined
      ? existing.radius_m
      : Math.round(toNum(body.radiusM) ?? existing.radius_m);
    const active = body.active === undefined ? existing.active : (toBool(body.active) === false ? 0 : 1);
    const lat = body.lat === undefined ? existing.lat : toNum(body.lat);
    const lng = body.lng === undefined ? existing.lng : toNum(body.lng);
    if (lat === null || lng === null) return fail(res, 400, 'BAD_REQUEST', 'إحداثيات غير صالحة');

    await q.run(
      'UPDATE geofences SET name = ?, lat = ?, lng = ?, radius_m = ?, active = ? WHERE id = ?',
      name,
      lat,
      lng,
      radius,
      active,
      geoId,
    );
    const row = await q.get('SELECT * FROM geofences WHERE id = ?', geoId);
    return ok(res, row);
  }),
);

safetyRouter.delete(
  '/devices/:id/geofences/:geoId',
  requireParent,
  asyncHandler(async (req, res) => {
    const device = await ownedDevice(req, res);
    if (!device) return;
    const geoId = toInt(req.params.geoId);
    if (geoId === null) return fail(res, 400, 'BAD_REQUEST', 'معرّف غير صالح');
    await q.run('DELETE FROM geofences WHERE id = ? AND device_id = ?', geoId, device.id);
    return ok(res, { deleted: true });
  }),
);

/* ---------------------------------------------------------------- alerts */

safetyRouter.get(
  '/alerts',
  requireParent,
  asyncHandler(async (req, res) => {
    const limit = parseLimit(req.query.limit, 100);
    const unreadOnly = String(req.query.unread ?? '') === '1';
    const deviceId = toInt(req.query.deviceId);
    const clauses = ['user_id = ?'];
    const args = [req.user.id];
    if (unreadOnly) clauses.push('read_at IS NULL');
    if (deviceId !== null) {
      clauses.push('device_id = ?');
      args.push(deviceId);
    }
    const rows = await q.all(
      `SELECT * FROM alerts WHERE ${clauses.join(' AND ')} ORDER BY at DESC LIMIT ?`,
      ...args,
      limit,
    );
    const unread = await q.get(
      'SELECT COUNT(*) AS total FROM alerts WHERE user_id = ? AND read_at IS NULL',
      req.user.id,
    );
    return ok(
      res,
      rows.map((row) => ({
        id: row.id,
        deviceId: row.device_id,
        type: row.type,
        title: row.title,
        body: row.body,
        at: row.at,
        read: Boolean(row.read_at),
        payload: safeJson(row.payload),
      })),
      { count: rows.length, unread: unread?.total ?? 0, limit },
    );
  }),
);

safetyRouter.post(
  '/alerts/read',
  requireParent,
  asyncHandler(async (req, res) => {
    const ids = Array.isArray(req.body?.ids)
      ? req.body.ids.map((value) => toInt(value)).filter((value) => value !== null)
      : [];
    const at = nowIso();
    if (ids.length === 0) {
      await q.run('UPDATE alerts SET read_at = ? WHERE user_id = ? AND read_at IS NULL', at, req.user.id);
    } else {
      const placeholders = ids.map(() => '?').join(',');
      await q.run(
        `UPDATE alerts SET read_at = ? WHERE user_id = ? AND id IN (${placeholders})`,
        at,
        req.user.id,
        ...ids,
      );
    }
    return ok(res, { read: ids.length || 'all' });
  }),
);

/* -------------------------------------------------------------- contacts */

safetyRouter.get(
  '/devices/:id/contacts',
  requireParent,
  asyncHandler(async (req, res) => {
    const device = await ownedDevice(req, res);
    if (!device) return;
    const limit = parseLimit(req.query.limit, 5000);
    const search = cleanText(req.query.q, 120);
    const rows = search
      ? await q.all(
          `SELECT name, number, email, at FROM contacts
            WHERE device_id = ? AND (name LIKE ? OR number LIKE ?)
            ORDER BY name COLLATE NOCASE LIMIT ?`,
          device.id,
          `%${search}%`,
          `%${search}%`,
          limit,
        )
      : await q.all(
          `SELECT name, number, email, at FROM contacts
            WHERE device_id = ? ORDER BY name COLLATE NOCASE LIMIT ?`,
          device.id,
          limit,
        );
    return ok(res, rows, { count: rows.length, limit });
  }),
);

function safeJson(raw) {
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}
