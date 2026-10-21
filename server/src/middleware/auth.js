import { verifyToken } from '../lib/jwt.js';
import { q } from '../db.js';
import { fail, forbidden } from '../lib/util.js';

function bearerFrom(req) {
  const header = req.get('authorization') || '';
  const match = /^Bearer\s+(.+)$/i.exec(header.trim());
  return match ? match[1].trim() : null;
}

function deviceFrom(req) {
  const header = req.get('authorization') || '';
  // `Authorization: Device <token>` is the device-specific scheme.
  const deviceMatch = /^Device\s+(.+)$/i.exec(header.trim());
  if (deviceMatch) return deviceMatch[1].trim();
  // Fall back to Bearer — the contract allows it for device clients.
  return bearerFrom(req);
}

/** Requires a valid parent token; attaches req.user. */
export function requireParent(req, res, next) {
  const token = bearerFrom(req) || req.cookies?.pg_session;
  const claims = token ? verifyToken(token) : null;
  if (!claims || claims.kind !== 'parent') {
    return fail(res, 401, 'INVALID_TOKEN', 'توكن غير صالح أو منتهي');
  }
  const user = q.get('SELECT id, email, name, created_at FROM users WHERE id = ?', claims.sub);
  if (!user) return fail(res, 401, 'INVALID_TOKEN', 'المستخدم غير موجود');
  req.user = user;
  req.parentClaims = claims;
  return next();
}

/** Requires a valid device token; attaches req.device. */
export function requireDevice(req, res, next) {
  const token = deviceFrom(req);
  const claims = token ? verifyToken(token) : null;
  if (!claims || claims.kind !== 'device') {
    return fail(res, 401, 'INVALID_TOKEN', 'توكن الجهاز غير صالح أو منتهي');
  }
  const device = q.get('SELECT * FROM devices WHERE id = ?', claims.sub);
  if (!device) return fail(res, 401, 'INVALID_TOKEN', 'الجهاز غير موجود');
  if (device.device_token !== claims.jti) {
    // The token was rotated (device deleted or re-enrolled) — force re-enrollment.
    return fail(res, 401, 'INVALID_TOKEN', 'تم إلغاء ربط هذا الجهاز');
  }
  req.device = device;
  return next();
}

/**
 * Ensures the :id in the path matches the authenticated device.
 * Prevents one enrolled device from writing into another device's history.
 */
export function requireOwnDevice(req, res, next) {
  const id = Number.parseInt(req.params.id, 10);
  if (!Number.isFinite(id) || id !== req.device.id) {
    return next(forbidden('هذا الجهاز غير مخوّل للوصول لهذا المورد'));
  }
  return next();
}
