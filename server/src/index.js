import http from 'node:http';
import path from 'node:path';
import express from 'express';
import helmet from 'helmet';
import cookieParser from 'cookie-parser';

import { config } from './config.js';
import { purgeOldData, initDb } from './db/index.js';
import { rateLimit, startRateLimitSweeper } from './lib/ratelimit.js';
import { verifyToken } from './lib/jwt.js';
import { fail, HttpError } from './lib/util.js';
import { authRouter } from './routes/auth.routes.js';
import { devicesRouter } from './routes/devices.routes.js';
import { dataRouter } from './routes/data.routes.js';
import { safetyRouter } from './routes/safety.routes.js';
import { attachRealtime } from './realtime.js';

const app = express();
app.set('trust proxy', 1); // behind nginx / Cloudflare Tunnel the client IP is in X-Forwarded-For
app.disable('x-powered-by');

app.use(
  helmet({
    contentSecurityPolicy: {
      directives: {
        defaultSrc: ["'self'"],
        scriptSrc: ["'self'", "'unsafe-inline'"],
        styleSrc: ["'self'", "'unsafe-inline'"],
        imgSrc: ["'self'", 'data:', 'blob:', 'https://*.tile.openstreetmap.org'],
        connectSrc: ["'self'", 'ws:', 'wss:'],
        fontSrc: ["'self'", 'data:'],
      },
    },
    crossOriginEmbedderPolicy: false,
    crossOriginResourcePolicy: { policy: 'cross-origin' },
  }),
);

app.use(express.json({ limit: '2mb' }));
app.use(express.urlencoded({ extended: false, limit: '2mb' }));
app.use(cookieParser());

// ------------------------------------------------------------------- CORS
// The dashboard is served same-origin, but allow other origins for API clients.
app.use((req, res, next) => {
  res.setHeader('Access-Control-Allow-Origin', req.get('origin') || '*');
  res.setHeader('Vary', 'Origin');
  res.setHeader('Access-Control-Allow-Credentials', 'true');
  res.setHeader(
    'Access-Control-Allow-Headers',
    'Content-Type, Authorization, X-Device-Token, X-Enroll-Code',
  );
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PATCH, DELETE, OPTIONS');
  if (req.method === 'OPTIONS') return res.sendStatus(204);
  return next();
});

// ------------------------------------------------------------- rate limits
const authLimiter = rateLimit({
  limit: config.authRateLimit,
  windowMs: config.authRateWindowMin * 60_000,
  keyFn: (req) => `auth:${req.ip}`,
});

const deviceLimiter = rateLimit({
  limit: config.deviceRateLimit,
  windowMs: 60_000,
  keyFn: (req) => {
    const header = req.get('authorization') || '';
    const match = /^(?:Device|Bearer)\s+(.+)$/i.exec(header.trim());
    const claims = match ? verifyToken(match[1].trim()) : null;
    return claims?.sub ? `dev:${claims.sub}` : `ip:${req.ip}`;
  },
});

app.use('/api/auth', authLimiter);
app.use('/api/devices', deviceLimiter);

// ------------------------------------------------------------------ routes
app.get('/api/health', (_req, res) => {
  res.json({
    ok: true,
    data: { status: 'healthy', uptimeSec: Math.round(process.uptime()), version: '1.0.0' },
  });
});

app.use('/api/auth', authRouter);
app.use('/api/devices', devicesRouter);
app.use('/api/devices', dataRouter);
app.use('/api', safetyRouter);

app.use('/api', (_req, res) => fail(res, 404, 'NOT_FOUND', 'المسار غير موجود'));

// ----------------------------------------------------------- dashboard SPA
const publicDir = path.join(config.root, 'public');
app.use(
  express.static(publicDir, {
    index: 'index.html',
    maxAge: config.isProd ? '1h' : 0,
    setHeaders: (res, filePath) => {
      if (filePath.endsWith('.html')) res.setHeader('Cache-Control', 'no-cache');
    },
  }),
);

// ------------------------------------------------------------- error handler
app.use((err, req, res, _next) => {
  if (err instanceof HttpError) {
    return fail(res, err.status, err.code, err.message);
  }
  if (err?.code === 'LIMIT_FILE_SIZE') {
    return fail(res, 413, 'BAD_REQUEST', 'حجم الملف أكبر من الحد المسموح');
  }
  if (err?.code === 'LIMIT_UNEXPECTED_FILE') {
    return fail(res, 400, 'BAD_REQUEST', 'حقل الملف يجب أن يكون named "file"');
  }
  if (err?.type === 'entity.parse.failed') {
    return fail(res, 400, 'BAD_REQUEST', 'JSON غير صالح');
  }
  console.error('[guardian] unhandled error:', err);
  return fail(
    res,
    500,
    'INTERNAL',
    config.isProd ? 'خطأ داخلي في الخادم' : String(err?.message || err),
  );
});

// ------------------------------------------------------------------- start
await initDb();

const server = http.createServer(app);
attachRealtime(server);
startRateLimitSweeper();

server.listen(config.port, '0.0.0.0', () => {
  console.log(`[guardian] listening on http://0.0.0.0:${config.port}`);
  console.log(`[guardian] dashboard: ${config.publicUrl || `http://localhost:${config.port}`}`);
  console.log(`[guardian] database:  ${config.dbDriver === 'libsql' ? config.libsqlUrl : config.dbFile}`);
});

// Daily retention sweep keeps the free-tier disk from filling up.
const purgeTimer = setInterval(() => {
  purgeOldData(90).catch((err) => console.error('[guardian] purge failed:', err));
}, 24 * 3600_000);
purgeTimer.unref?.();
purgeOldData(90).catch((err) => console.error('[guardian] purge failed:', err));

function shutdown(signal) {
  console.log(`[guardian] ${signal} received, shutting down`);
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 5000).unref();
}
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));

export { app, server };
