import crypto from 'node:crypto';
import { config } from '../config.js';

const b64u = (buf) => Buffer.from(buf).toString('base64url');

function sign(data) {
  return crypto.createHmac('sha256', config.jwtSecret).update(data).digest('base64url');
}

/** Minimal HS256 JWT — keeps the server dependency-free and auditable. */
export function issueToken(payload, { days = config.parentTokenDays, kind = 'parent' } = {}) {
  const now = Math.floor(Date.now() / 1000);
  const body = {
    ...payload,
    kind,
    iat: now,
    exp: now + days * 86400,
    // Callers may supply their own `jti` (device tokens use the opaque device
    // secret as the identifier) — only generate one when it is missing.
    jti: payload.jti ?? crypto.randomBytes(8).toString('hex'),
  };
  const header = b64u(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const claims = b64u(JSON.stringify(body));
  const signature = sign(`${header}.${claims}`);
  return `${header}.${claims}.${signature}`;
}

/** Returns the claims object, or null when the token is malformed/expired/tampered. */
export function verifyToken(token) {
  if (typeof token !== 'string') return null;
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  const [header, claims, signature] = parts;

  const expected = sign(`${header}.${claims}`);
  const a = Buffer.from(signature);
  const b = Buffer.from(expected);
  // Constant-time compare so a forged signature cannot be brute-forced byte by byte.
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return null;

  let payload;
  try {
    payload = JSON.parse(Buffer.from(claims, 'base64url').toString('utf8'));
  } catch {
    return null;
  }
  if (typeof payload.exp !== 'number' || payload.exp * 1000 < Date.now()) return null;
  return payload;
}
