import path from 'node:path';
import fs from 'node:fs';
import { Router } from 'express';
import { q } from '../db.js';
import { config } from '../config.js';
import { ok, fail, asyncHandler, parseLimit, parseRange, toInt } from '../lib/util.js';
import { requireParent } from '../middleware/auth.js';

export const dataRouter = Router();

/** Every read endpoint is scoped to the requesting parent's own device. */
function ownedDevice(req) {
  const id = Number.parseInt(req.params.id, 10);
  if (!Number.isFinite(id)) return null;
  return q.get('SELECT * FROM devices WHERE id = ? AND user_id = ?', id, req.user.id);
}

function guard(req, res) {
  const device = ownedDevice(req);
  if (!device) {
    fail(res, 404, 'NOT_FOUND', 'الجهاز غير موجود');
    return null;
  }
  return device;
}

dataRouter.get(
  '/:id/location',
  requireParent,
  asyncHandler(async (req, res) => {
    if (!guard(req, res)) return;
    const loc = q.get(
      'SELECT * FROM locations WHERE device_id = ? ORDER BY at DESC LIMIT 1',
      req.params.id,
    );
    return ok(res, loc ?? null);
  }),
);

dataRouter.get(
  '/:id/locations',
  requireParent,
  asyncHandler(async (req, res) => {
    if (!guard(req, res)) return;
    const { from, to } = parseRange(req.query, 7);
    const limit = parseLimit(req.query.limit, 1000);
    const rows = q.all(
      'SELECT * FROM locations WHERE device_id = ? AND at BETWEEN ? AND ? ORDER BY at DESC LIMIT ?',
      req.params.id,
      from,
      to,
      limit,
    );
    return ok(res, rows, { count: rows.length, limit, from, to });
  }),
);

dataRouter.get(
  '/:id/usage',
  requireParent,
  asyncHandler(async (req, res) => {
    if (!guard(req, res)) return;
    const { from, to } = parseRange(req.query, 7);
    const limit = parseLimit(req.query.limit, 200);
    const rows = q.all(
      `SELECT package,
              MAX(label)            AS label,
              SUM(duration_ms)      AS total_ms,
              COUNT(*)              AS sessions,
              MAX(at)               AS last_used
         FROM app_usage
        WHERE device_id = ? AND at BETWEEN ? AND ?
        GROUP BY package
        ORDER BY total_ms DESC
        LIMIT ?`,
      req.params.id,
      from,
      to,
      limit,
    );
    return ok(res, rows, { count: rows.length, from, to });
  }),
);

dataRouter.get(
  '/:id/calls',
  requireParent,
  asyncHandler(async (req, res) => {
    if (!guard(req, res)) return;
    const { from, to } = parseRange(req.query, 7);
    const limit = parseLimit(req.query.limit, 300);
    const rows = q.all(
      'SELECT * FROM calls WHERE device_id = ? AND at BETWEEN ? AND ? ORDER BY at DESC LIMIT ?',
      req.params.id,
      from,
      to,
      limit,
    );
    return ok(res, rows, { count: rows.length, from, to });
  }),
);

dataRouter.get(
  '/:id/sms',
  requireParent,
  asyncHandler(async (req, res) => {
    if (!guard(req, res)) return;
    const { from, to } = parseRange(req.query, 7);
    const limit = parseLimit(req.query.limit, 300);
    const rows = q.all(
      'SELECT * FROM sms WHERE device_id = ? AND at BETWEEN ? AND ? ORDER BY at DESC LIMIT ?',
      req.params.id,
      from,
      to,
      limit,
    );
    return ok(res, rows, { count: rows.length, from, to });
  }),
);

dataRouter.get(
  '/:id/apps',
  requireParent,
  asyncHandler(async (req, res) => {
    if (!guard(req, res)) return;
    const rows = q.all(
      'SELECT package, label, version, is_system, installed_at, at FROM installed_apps WHERE device_id = ? ORDER BY label COLLATE NOCASE',
      req.params.id,
    );
    return ok(res, rows, { count: rows.length });
  }),
);

dataRouter.get(
  '/:id/media',
  requireParent,
  asyncHandler(async (req, res) => {
    if (!guard(req, res)) return;
    const { from, to } = parseRange(req.query, 14);
    const limit = parseLimit(req.query.limit, 100);
    const kind = req.query.kind ? String(req.query.kind) : null;

    const rows = kind
      ? q.all(
          'SELECT * FROM media WHERE device_id = ? AND kind = ? AND at BETWEEN ? AND ? ORDER BY at DESC LIMIT ?',
          req.params.id,
          kind,
          from,
          to,
          limit,
        )
      : q.all(
          'SELECT * FROM media WHERE device_id = ? AND at BETWEEN ? AND ? ORDER BY at DESC LIMIT ?',
          req.params.id,
          from,
          to,
          limit,
        );

    return ok(
      res,
      rows.map((r) => ({
        id: r.id,
        kind: r.kind,
        mime: r.mime,
        size: r.size,
        at: r.at,
        url: `/api/devices/${req.params.id}/media/${r.id}`,
      })),
      { count: rows.length, from, to },
    );
  }),
);

dataRouter.get(
  '/:id/media/:mediaId',
  requireParent,
  asyncHandler(async (req, res) => {
    if (!guard(req, res)) return;
    const mediaId = toInt(req.params.mediaId);
    if (mediaId === null) return fail(res, 400, 'BAD_REQUEST', 'معرّف ملف غير صالح');

    const item = q.get('SELECT * FROM media WHERE id = ? AND device_id = ?', mediaId, req.params.id);
    if (!item) return fail(res, 404, 'NOT_FOUND', 'الملف غير موجود');

    // filename is server-generated, but resolve+prefix check keeps a corrupted DB row
    // from escaping the upload directory.
    const filePath = path.resolve(config.uploadDir, path.basename(item.filename));
    if (!filePath.startsWith(path.resolve(config.uploadDir))) {
      return fail(res, 400, 'BAD_REQUEST', 'مسار ملف غير صالح');
    }
    if (!fs.existsSync(filePath)) return fail(res, 404, 'NOT_FOUND', 'الملف غير موجود على القرص');

    res.setHeader('Content-Type', item.mime || 'application/octet-stream');
    res.setHeader('Content-Disposition', `inline; filename="${mediaId}.${path.extname(filePath).slice(1) || 'bin'}"`);
    res.setHeader('Cache-Control', 'private, max-age=3600');
    return fs.createReadStream(filePath).pipe(res);
  }),
);

dataRouter.get(
  '/:id/timeline',
  requireParent,
  asyncHandler(async (req, res) => {
    if (!guard(req, res)) return;
    const { from, to } = parseRange(req.query, 3);
    const limit = parseLimit(req.query.limit, 300);
    const deviceId = req.params.id;

    const usage = q.all(
      'SELECT id, at, package, label, duration_ms FROM app_usage WHERE device_id = ? AND at BETWEEN ? AND ? ORDER BY at DESC LIMIT ?',
      deviceId,
      from,
      to,
      limit,
    ).map((r) => ({
      id: `usage-${r.id}`,
      at: r.at,
      kind: 'app_usage',
      title: r.label || r.package,
      subtitle: r.package,
      durationMs: r.duration_ms,
    }));

    const calls = q.all(
      'SELECT id, at, number, contact_name, call_type, duration_sec FROM calls WHERE device_id = ? AND at BETWEEN ? AND ? ORDER BY at DESC LIMIT ?',
      deviceId,
      from,
      to,
      limit,
    ).map((r) => ({
      id: `call-${r.id}`,
      at: r.at,
      kind: 'call',
      title: r.contact_name || r.number || 'مكالمة',
      subtitle: r.call_type,
      durationSec: r.duration_sec,
    }));

    const messages = q.all(
      'SELECT id, at, address, body, msg_type FROM sms WHERE device_id = ? AND at BETWEEN ? AND ? ORDER BY at DESC LIMIT ?',
      deviceId,
      from,
      to,
      limit,
    ).map((r) => ({
      id: `sms-${r.id}`,
      at: r.at,
      kind: 'sms',
      title: r.address || 'رسالة',
      subtitle: r.body,
    }));

    const events = q.all(
      'SELECT id, at, type, payload FROM events WHERE device_id = ? AND at BETWEEN ? AND ? ORDER BY at DESC LIMIT ?',
      deviceId,
      from,
      to,
      limit,
    ).map((r) => {
      let payload = {};
      try {
        payload = JSON.parse(r.payload || '{}');
      } catch {
        payload = {};
      }
      return {
        id: `event-${r.id}`,
        at: r.at,
        kind: r.type,
        title: payload.title || payload.label || payload.package || r.type,
        subtitle: payload.text || payload.url || payload.extra || '',
      };
    });

    const merged = [...usage, ...calls, ...messages, ...events].sort(
      (a, b) => new Date(b.at) - new Date(a.at),
    );
    return ok(res, merged.slice(0, limit), { count: merged.length, from, to });
  }),
);

dataRouter.get(
  '/:id/stats',
  requireParent,
  asyncHandler(async (req, res) => {
    if (!guard(req, res)) return;
    const deviceId = req.params.id;
    const dayAgo = new Date(Date.now() - 86400_000).toISOString();
    const weekAgo = new Date(Date.now() - 7 * 86400_000).toISOString();

    const screenToday = q.get(
      'SELECT COALESCE(SUM(duration_ms), 0) AS total FROM app_usage WHERE device_id = ? AND at >= ?',
      deviceId,
      dayAgo,
    );
    const screenWeek = q.get(
      'SELECT COALESCE(SUM(duration_ms), 0) AS total FROM app_usage WHERE device_id = ? AND at >= ?',
      deviceId,
      weekAgo,
    );
    const callsToday = q.get(
      'SELECT COUNT(*) AS total FROM calls WHERE device_id = ? AND at >= ?',
      deviceId,
      dayAgo,
    );
    const callsWeek = q.get(
      'SELECT COUNT(*) AS total FROM calls WHERE device_id = ? AND at >= ?',
      deviceId,
      weekAgo,
    );
    const locationsToday = q.get(
      'SELECT COUNT(*) AS total FROM locations WHERE device_id = ? AND at >= ?',
      deviceId,
      dayAgo,
    );
    const topApps = q.all(
      `SELECT package, MAX(label) AS label, SUM(duration_ms) AS total_ms
         FROM app_usage WHERE device_id = ? AND at >= ?
        GROUP BY package ORDER BY total_ms DESC LIMIT 10`,
      deviceId,
      weekAgo,
    );

    return ok(res, {
      screenTimeTodayMs: screenToday.total,
      screenTimeWeekMs: screenWeek.total,
      callsToday: callsToday.total,
      callsWeek: callsWeek.total,
      locationsToday: locationsToday.total,
      topApps,
    });
  }),
);
