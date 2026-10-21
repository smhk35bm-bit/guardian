import { WebSocketServer } from 'ws';
import { verifyToken } from './lib/jwt.js';
import { q } from './db.js';

const rooms = new Map(); // roomId -> Set<ws>
const meta = new WeakMap(); // ws -> { kind, userId, deviceId }

function join(ws, roomId) {
  let set = rooms.get(roomId);
  if (!set) {
    set = new Set();
    rooms.set(roomId, set);
  }
  set.add(ws);
}

function leaveAll(ws) {
  for (const [roomId, set] of rooms) {
    if (set.delete(ws) && set.size === 0) rooms.delete(roomId);
  }
}

export function broadcastToUser(userId, message) {
  const set = rooms.get(`user:${userId}`);
  if (!set || set.size === 0) return 0;
  const payload = JSON.stringify(message);
  let sent = 0;
  for (const ws of set) {
    if (ws.readyState === ws.OPEN) {
      ws.send(payload);
      sent += 1;
    }
  }
  return sent;
}

export function attachRealtime(server) {
  const wss = new WebSocketServer({ server, path: '/ws' });

  wss.on('connection', (ws, req) => {
    const url = new URL(req.url, 'http://localhost');
    const token = url.searchParams.get('token');
    const claims = token ? verifyToken(token) : null;

    if (!claims) {
      ws.send(JSON.stringify({ type: 'error', error: { code: 'INVALID_TOKEN' } }));
      ws.close(4001, 'invalid token');
      return;
    }

    if (claims.kind === 'parent') {
      const user = q.get('SELECT id FROM users WHERE id = ?', claims.sub);
      if (!user) {
        ws.close(4001, 'unknown user');
        return;
      }
      meta.set(ws, { kind: 'parent', userId: user.id });
      join(ws, `user:${user.id}`);
      ws.send(JSON.stringify({ type: 'hello', kind: 'parent' }));
    } else if (claims.kind === 'device') {
      // device_token must be selected here — it is compared against the
      // token's `jti` to detect a rotated (revoked) device credential.
      const device = q.get('SELECT id, user_id, device_token FROM devices WHERE id = ?', claims.sub);
      if (!device || device.device_token !== claims.jti) {
        ws.close(4001, 'unknown device');
        return;
      }
      meta.set(ws, { kind: 'device', userId: device.user_id, deviceId: device.id });
      join(ws, `device:${device.id}`);
      join(ws, `user:${device.user_id}`);
      ws.send(JSON.stringify({ type: 'hello', kind: 'device', deviceId: device.id }));
    } else {
      ws.close(4001, 'unknown token kind');
      return;
    }

    ws.on('message', (raw) => {
      // The only client→server message we care about is an app-level ping.
      try {
        const msg = JSON.parse(raw.toString());
        if (msg?.type === 'ping') {
          ws.send(JSON.stringify({ type: 'pong', at: new Date().toISOString() }));
        }
      } catch {
        /* ignore malformed frames */
      }
    });

    ws.on('close', () => leaveAll(ws));
    ws.on('error', () => leaveAll(ws));
  });

  // Keep-alive: terminate sockets that stop responding so rooms stay accurate.
  const heartbeat = setInterval(() => {
    for (const ws of wss.clients) {
      if (ws.isAlive === false) {
        ws.terminate();
        continue;
      }
      ws.isAlive = false;
      ws.ping();
    }
  }, 30_000);
  heartbeat.unref?.();

  wss.on('connection', (ws) => {
    ws.isAlive = true;
    ws.on('pong', () => {
      ws.isAlive = true;
    });
  });

  return wss;
}
