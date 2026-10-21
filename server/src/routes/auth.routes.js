import { Router } from 'express';
import { q, nowIso } from '../db.js';
import { hashPassword, verifyPassword } from '../lib/password.js';
import { issueToken } from '../lib/jwt.js';
import { config } from '../config.js';
import { ok, fail, asyncHandler } from '../lib/util.js';
import { requireParent } from '../middleware/auth.js';

export const authRouter = Router();

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const COOKIE_NAME = 'pg_session';

function setSessionCookie(res, token) {
  res.cookie(COOKIE_NAME, token, {
    httpOnly: true,
    sameSite: 'lax',
    secure: config.isProd,
    maxAge: config.parentTokenDays * 86400_000,
    path: '/',
  });
}

function publicUser(user) {
  return { id: user.id, email: user.email, name: user.name, createdAt: user.created_at };
}

authRouter.post(
  '/register',
  asyncHandler(async (req, res) => {
    const email = String(req.body?.email ?? '').trim().toLowerCase();
    const password = String(req.body?.password ?? '');
    const name = String(req.body?.name ?? '').trim().slice(0, 80) || 'ولي الأمر';

    if (!EMAIL_RE.test(email)) {
      return fail(res, 400, 'BAD_REQUEST', 'بريد إلكتروني غير صالح');
    }
    if (password.length < 8) {
      return fail(res, 400, 'BAD_REQUEST', 'كلمة المرور يجب أن تكون 8 أحرف على الأقل');
    }
    if (q.get('SELECT id FROM users WHERE email = ?', email)) {
      return fail(res, 409, 'BAD_REQUEST', 'هذا البريد مسجّل مسبقاً');
    }

    const { lastInsertRowid } = q.run(
      'INSERT INTO users (email, password_hash, name, created_at) VALUES (?, ?, ?, ?)',
      email,
      hashPassword(password),
      name,
      nowIso(),
    );

    const user = q.get('SELECT * FROM users WHERE id = ?', lastInsertRowid);
    const token = issueToken({ sub: user.id, email: user.email }, { kind: 'parent' });
    setSessionCookie(res, token);
    return ok(res, { token, user: publicUser(user) }, undefined, 201);
  }),
);

authRouter.post(
  '/login',
  asyncHandler(async (req, res) => {
    const email = String(req.body?.email ?? '').trim().toLowerCase();
    const password = String(req.body?.password ?? '');

    const user = q.get('SELECT * FROM users WHERE email = ?', email);
    // Same message for "no such user" and "wrong password" — do not leak which emails exist.
    if (!user || !verifyPassword(password, user.password_hash)) {
      return fail(res, 401, 'INVALID_TOKEN', 'البريد أو كلمة المرور غير صحيحة');
    }

    const token = issueToken({ sub: user.id, email: user.email }, { kind: 'parent' });
    setSessionCookie(res, token);
    return ok(res, { token, user: publicUser(user) });
  }),
);

authRouter.post('/logout', (req, res) => {
  res.clearCookie(COOKIE_NAME, { path: '/' });
  return ok(res, { loggedOut: true });
});

authRouter.get('/me', requireParent, (req, res) => ok(res, { user: publicUser(req.user) }));
