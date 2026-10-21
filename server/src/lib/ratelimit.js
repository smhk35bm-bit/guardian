/**
 * Fixed-window in-memory rate limiter.
 * Good enough for a single-process server; swap for Redis if you scale horizontally.
 */
const buckets = new Map();

export function rateLimit({ limit, windowMs, keyFn }) {
  return (req, res, next) => {
    const key = keyFn(req);
    const now = Date.now();
    let bucket = buckets.get(key);

    if (!bucket || now >= bucket.resetAt) {
      bucket = { count: 0, resetAt: now + windowMs };
      buckets.set(key, bucket);
    }
    bucket.count += 1;

    const remaining = Math.max(0, limit - bucket.count);
    res.setHeader('X-RateLimit-Limit', String(limit));
    res.setHeader('X-RateLimit-Remaining', String(remaining));
    res.setHeader('X-RateLimit-Reset', String(Math.ceil(bucket.resetAt / 1000)));

    if (bucket.count > limit) {
      const retryAfter = Math.ceil((bucket.resetAt - now) / 1000);
      res.setHeader('Retry-After', String(retryAfter));
      return res.status(429).json({
        ok: false,
        error: { code: 'RATE_LIMITED', message: 'محاولات كثيرة، حاول لاحقاً' },
      });
    }
    return next();
  };
}

/** Drops expired buckets so the map does not grow without bound. */
export function startRateLimitSweeper(intervalMs = 60_000) {
  const timer = setInterval(() => {
    const now = Date.now();
    for (const [key, bucket] of buckets) {
      if (now >= bucket.resetAt) buckets.delete(key);
    }
  }, intervalMs);
  timer.unref?.();
  return timer;
}
