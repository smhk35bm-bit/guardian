import { Router } from 'express';
import { q } from '../db/index.js';
import { readMedia } from '../media.js';
import { ok, fail, asyncHandler, parseLimit, parseRange, toInt } from '../lib/util.js';
import { requireParent } from '../middleware/auth.js';

export const dataRouter = Router();

/** Every read endpoint is scoped to the requesting parent's own device. */
async function ownedDevice(req) {
  const id = Number.parseInt(req.params.id, 10);
  if (!Number.isFinite(id)) return null;
  return q.get('SELECT * FROM devices WHERE id = ? AND user_id = ?', id, req.user.id);
}

async function guard(req, res) {
  const device = await ownedDevice(req);
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
    if (!(await guard(req, res))) return;
    const loc = await q.get(
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
    if (!(await guard(req, res))) return;
    const { from, to } = parseRange(req.query, 7);
    const limit = parseLimit(req.query.limit, 1000);
    const rows = await q.all(
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
    if (!(await guard(req, res))) return;
    const { from, to } = parseRange(req.query, 7);
    const limit = parseLimit(req.query.limit, 200);
    const rows = await q.all(
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
    if (!(await guard(req, res))) return;
    const { from, to } = parseRange(req.query, 7);
    const limit = parseLimit(req.query.limit, 300);
    const rows = await q.all(
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
    if (!(await guard(req, res))) return;
    const { from, to } = parseRange(req.query, 7);
    const limit = parseLimit(req.query.limit, 300);
    const rows = await q.all(
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
    if (!(await guard(req, res))) return;
    const rows = await q.all(
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
    if (!(await guard(req, res))) return;
    const { from, to } = parseRange(req.query, 14);
    const limit = parseLimit(req.query.limit, 100);
    const kind = req.query.kind ? String(req.query.kind) : null;

    const rows = kind
      ? await q.all(
          'SELECT * FROM media WHERE device_id = ? AND kind = ? AND at BETWEEN ? AND ? ORDER BY at DESC LIMIT ?',
          req.params.id,
          kind,
          from,
          to,
          limit,
        )
      : await q.all(
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
    if (!(await guard(req, res))) return;
    const mediaId = toInt(req.params.mediaId);
    if (mediaId === null) return fail(res, 400, 'BAD_REQUEST', 'معرّف ملف غير صالح');

    const item = await readMedia(mediaId);
    if (!item || String(item.buffer?.length ?? 0) === '0') {
      return fail(res, 404, 'NOT_FOUND', 'الملف غير موجود');
    }

    const ext = String(item.filename || '').split('.').pop() || 'bin';
    res.setHeader('Content-Type', item.mime || 'application/octet-stream');
    res.setHeader('Content-Disposition', `inline; filename="${mediaId}.${ext}"`);
    res.setHeader('Cache-Control', 'private, max-age=3600');
    return res.send(item.buffer);
  }),
);

dataRouter.get(
  '/:id/timeline',
  requireParent,
  asyncHandler(async (req, res) => {
    if (!(await guard(req, res))) return;
    const { from, to } = parseRange(req.query, 3);
    const limit = parseLimit(req.query.limit, 300);
    const deviceId = req.params.id;

    // One round-trip for the four feeds — a remote database would otherwise
    // need four separate requests.
    const [usageRes, callRes, smsRes, eventRes] = await q.batch([
      {
        sql: 'SELECT id, at, package, label, duration_ms FROM app_usage WHERE device_id = ? AND at BETWEEN ? AND ? ORDER BY at DESC LIMIT ?',
        args: [deviceId, from, to, limit],
      },
      {
        sql: 'SELECT id, at, number, contact_name, call_type, duration_sec FROM calls WHERE device_id = ? AND at BETWEEN ? AND ? ORDER BY at DESC LIMIT ?',
        args: [deviceId, from, to, limit],
      },
      {
        sql: 'SELECT id, at, address, body, msg_type FROM sms WHERE device_id = ? AND at BETWEEN ? AND ? ORDER BY at DESC LIMIT ?',
        args: [deviceId, from, to, limit],
      },
      {
        sql: 'SELECT id, at, type, payload FROM events WHERE device_id = ? AND at BETWEEN ? AND ? ORDER BY at DESC LIMIT ?',
        args: [deviceId, from, to, limit],
      },
    ]);

    const usage = usageRes.rows.map((r) => ({
      id: `usage-${r.id}`,
      at: r.at,
      kind: 'app_usage',
      title: r.label || r.package,
      subtitle: r.package,
      durationMs: r.duration_ms,
    }));

    const calls = callRes.rows.map((r) => ({
      id: `call-${r.id}`,
      at: r.at,
      kind: 'call',
      title: r.contact_name || r.number || 'مكالمة',
      subtitle: r.call_type,
      durationSec: r.duration_sec,
    }));

    const messages = smsRes.rows.map((r) => ({
      id: `sms-${r.id}`,
      at: r.at,
      kind: 'sms',
      title: r.address || 'رسالة',
      subtitle: r.body,
    }));

    const events = eventRes.rows.map((r) => {
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
    if (!(await guard(req, res))) return;
    const deviceId = req.params.id;
    const dayAgo = new Date(Date.now() - 86400_000).toISOString();
    const weekAgo = new Date(Date.now() - 7 * 86400_000).toISOString();

    const [screenToday, screenWeek, callsToday, callsWeek, locationsToday, topAppsRes] =
      await q.batch([
        {
          sql: 'SELECT COALESCE(SUM(duration_ms), 0) AS total FROM app_usage WHERE device_id = ? AND at >= ?',
          args: [deviceId, dayAgo],
        },
        {
          sql: 'SELECT COALESCE(SUM(duration_ms), 0) AS total FROM app_usage WHERE device_id = ? AND at >= ?',
          args: [deviceId, weekAgo],
        },
        {
          sql: 'SELECT COUNT(*) AS total FROM calls WHERE device_id = ? AND at >= ?',
          args: [deviceId, dayAgo],
        },
        {
          sql: 'SELECT COUNT(*) AS total FROM calls WHERE device_id = ? AND at >= ?',
          args: [deviceId, weekAgo],
        },
        {
          sql: 'SELECT COUNT(*) AS total FROM locations WHERE device_id = ? AND at >= ?',
          args: [deviceId, dayAgo],
        },
        {
          sql: `SELECT package, MAX(label) AS label, SUM(duration_ms) AS total_ms
                  FROM app_usage WHERE device_id = ? AND at >= ?
                 GROUP BY package ORDER BY total_ms DESC LIMIT 10`,
          args: [deviceId, weekAgo],
        },
      ]);

    return ok(res, {
      screenTimeTodayMs: screenToday.rows[0]?.total ?? 0,
      screenTimeWeekMs: screenWeek.rows[0]?.total ?? 0,
      callsToday: callsToday.rows[0]?.total ?? 0,
      callsWeek: callsWeek.rows[0]?.total ?? 0,
      locationsToday: locationsToday.rows[0]?.total ?? 0,
      topApps: topAppsRes.rows,
    });
  }),
);
