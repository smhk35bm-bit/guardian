/** Shared helpers for request parsing and consistent JSON envelopes. */

export function ok(res, data, meta, status = 200) {
  const body = { ok: true, data };
  if (meta) body.meta = meta;
  return res.status(status).json(body);
}

export function fail(res, status, code, message) {
  return res.status(status).json({ ok: false, error: { code, message } });
}

export class HttpError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

export const badRequest = (msg = 'طلب غير صالح') => new HttpError(400, 'BAD_REQUEST', msg);
export const notFound = (msg = 'غير موجود') => new HttpError(404, 'NOT_FOUND', msg);
export const forbidden = (msg = 'غير مصرّح') => new HttpError(403, 'FORBIDDEN', msg);

/** Express 5 forwards async errors automatically; this keeps the intent explicit. */
export const asyncHandler = (fn) => (req, res, next) =>
  Promise.resolve(fn(req, res, next)).catch(next);

export function parseLimit(value, fallback = 500, max = 5000) {
  const n = Number.parseInt(value ?? '', 10);
  if (!Number.isFinite(n) || n <= 0) return fallback;
  return Math.min(n, max);
}

/** Normalises ?from / ?to ISO strings; falls back to a window of `defaultDays` days. */
export function parseRange(query, defaultDays = 7) {
  const now = Date.now();
  const to = query.to ? new Date(query.to) : new Date(now);
  const from = query.from ? new Date(query.from) : new Date(now - defaultDays * 86400_000);
  const safeTo = Number.isNaN(to.getTime()) ? new Date(now) : to;
  const safeFrom = Number.isNaN(from.getTime()) ? new Date(now - defaultDays * 86400_000) : from;
  return {
    from: safeFrom.toISOString(),
    to: safeTo.toISOString(),
  };
}

export function toInt(value, fallback = null) {
  const n = Number.parseInt(value ?? '', 10);
  return Number.isFinite(n) ? n : fallback;
}

export function toNum(value, fallback = null) {
  const n = Number.parseFloat(value ?? '');
  return Number.isFinite(n) ? n : fallback;
}

export function toBool(value) {
  if (value === undefined || value === null) return null;
  if (typeof value === 'boolean') return value;
  if (typeof value === 'number') return value !== 0;
  const s = String(value).toLowerCase();
  if (['1', 'true', 'yes', 'on'].includes(s)) return true;
  if (['0', 'false', 'no', 'off'].includes(s)) return false;
  return null;
}

/** Trims and bounds free-text coming from the device (avoid unbounded storage). */
export function cleanText(value, maxLen = 2000) {
  if (value === undefined || value === null) return null;
  const s = String(value).slice(0, maxLen);
  return s.length ? s : null;
}

export function isoOrNow(value) {
  const d = value ? new Date(value) : new Date();
  return Number.isNaN(d.getTime()) ? new Date().toISOString() : d.toISOString();
}
