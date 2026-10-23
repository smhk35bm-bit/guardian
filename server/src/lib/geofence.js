import { q, nowIso } from '../db/index.js';
import { broadcastToUser } from '../realtime.js';

const EARTH_RADIUS_M = 6_371_000;

/** Great-circle distance in metres between two WGS-84 points. */
export function haversineMeters(lat1, lon1, lat2, lon2) {
  const toRad = (deg) => (deg * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
}

/**
 * Compares a new position with every safe zone of the device and records an
 * alert when the child enters or leaves one.
 *
 * The first fix only seeds the state: a device that is already inside a zone
 * when the zone is created must not fire an "entered" alert. Everything is
 * best effort — a failure here can never reject the location itself.
 *
 * @returns {Promise<Array<object>>} the alerts that were created
 */
export async function evaluateGeofences({ deviceId, userId, lat, lng, at }) {
  let fences;
  try {
    fences = await q.all(
      'SELECT id, name, lat, lng, radius_m, inside FROM geofences WHERE device_id = ? AND active = 1',
      deviceId,
    );
  } catch {
    return [];
  }

  const created = [];
  for (const fence of fences) {
    let inside;
    try {
      inside = haversineMeters(lat, lng, fence.lat, fence.lng) <= Number(fence.radius_m);
    } catch {
      continue;
    }

    const previous = fence.inside === null || fence.inside === undefined
      ? null
      : Number(fence.inside) === 1;

    if (previous === inside) continue;

    try {
      await q.run('UPDATE geofences SET inside = ? WHERE id = ?', inside ? 1 : 0, fence.id);
    } catch {
      continue;
    }

    // Seed only: no alert for the first observation.
    if (previous === null) continue;

    const type = inside ? 'geofence_enter' : 'geofence_exit';
    const title = inside
      ? `دخل «${fence.name}»`
      : `خرج من «${fence.name}»`;
    const body = inside
      ? 'الجهاز أصبح داخل الموقع الآمن.'
      : 'الجهاز غادر الموقع الآمن.';

    try {
      const { lastInsertRowid } = await q.run(
        `INSERT INTO alerts (device_id, user_id, type, title, body, payload, at)
         VALUES (?, ?, ?, ?, ?, ?, ?)`,
        deviceId,
        userId,
        type,
        title,
        body,
        JSON.stringify({
          geofenceId: fence.id,
          name: fence.name,
          lat,
          lng,
          at,
        }),
        at,
      );
      const alert = { id: lastInsertRowid, deviceId, type, title, body, at };
      created.push(alert);
      broadcastToUser(userId, { type: 'device.alert', deviceId, data: alert });
    } catch {
      /* an alert is useful, never critical */
    }
  }

  return created;
}

/** Inserts one alert and notifies every open dashboard of the parent. */
export async function raiseAlert({ deviceId, userId, type, title, body, payload }) {
  const at = nowIso();
  const { lastInsertRowid } = await q.run(
    `INSERT INTO alerts (device_id, user_id, type, title, body, payload, at)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
    deviceId,
    userId,
    type,
    title,
    body ?? null,
    payload ? JSON.stringify(payload) : null,
    at,
  );
  const alert = { id: lastInsertRowid, deviceId, type, title, body, at };
  broadcastToUser(userId, { type: 'device.alert', deviceId, data: alert });
  return alert;
}
