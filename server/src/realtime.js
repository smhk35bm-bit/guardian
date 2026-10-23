import { WebSocketServer } from 'ws';
import { verifyToken } from './lib/jwt.js';
import { q } from './db/index.js';

const rooms = new Map(); // roomId -> Set<ws>
const meta = new WeakMap(); // ws -> { kind, userId, deviceId }
const deviceSockets = new Map(); // deviceId -> ws

/** Binary header of a frame sent by a device: 12 bytes. */
const IN_HEADER = 12;
/** Binary header the server sends to the parent: 12 + 4 bytes of device id. */
const OUT_HEADER = 16;
const PROTOCOL_VERSION = 1;
/** Drop a frame instead of building an unbounded queue on a slow client. */
const MAX_BUFFERED_BYTES = 2 * 1024 * 1024;

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
  const info = meta.get(ws);
  if (info?.kind === 'device' && deviceSockets.get(info.deviceId) === ws) {
    deviceSockets.delete(info.deviceId);
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

/** Sends a JSON message to every dashboard socket of one parent. */
function sendToParents(userId, message) {
  const set = rooms.get(`user:${userId}`);
  if (!set || set.size === 0) return 0;
  const payload = JSON.stringify(message);
  let sent = 0;
  for (const ws of set) {
    if (meta.get(ws)?.kind !== 'parent') continue;
    if (ws.readyState !== ws.OPEN) continue;
    ws.send(payload);
    sent += 1;
  }
  return sent;
}

/** Sends a JSON message to the socket of one device, if it is online. */
export function sendToDevice(deviceId, message) {
  const ws = deviceSockets.get(Number(deviceId));
  if (!ws || ws.readyState !== ws.OPEN) return false;
  ws.send(JSON.stringify(message));
  return true;
}

export function isDeviceOnline(deviceId) {
  const ws = deviceSockets.get(Number(deviceId));
  return Boolean(ws && ws.readyState === ws.OPEN);
}

/**
 * Relays one JPEG frame from a device to the dashboards of its owner.
 *
 * The device header is
 *   [version:1][source:1][flags:2][seq:4][length:4]
 * and the server prepends the device id, producing
 *   [version:1][source:1][flags:2][deviceId:4][seq:4][length:4]
 * followed by the JPEG bytes (or PCM audio when flags has the audio bit).
 * The image itself is never decoded.
 */
function relayFrame(senderWs, info, raw) {
  if (raw.length <= IN_HEADER) return;
  if (raw.readUInt8(0) !== PROTOCOL_VERSION) return;

  // 1 = screen frame, 2 = camera frame, 3/4 = microphone PCM for either
  // source, distinguished by the audio flag.
  const source = raw.readUInt8(1);
  if (source < 1 || source > 4) return;

  const flags = raw.readUInt16BE(2);

  const length = raw.readUInt32BE(8);
  if (length === 0 || length > raw.length - IN_HEADER) return;

  const out = Buffer.allocUnsafe(OUT_HEADER + length);
  out.writeUInt8(PROTOCOL_VERSION, 0);
  out.writeUInt8(source, 1);
  out.writeUInt16BE(flags, 2);
  out.writeUInt32BE(info.deviceId, 4);
  out.writeUInt32BE(raw.readUInt32BE(4), 8);
  out.writeUInt32BE(length, 12);
  raw.copy(out, OUT_HEADER, IN_HEADER, IN_HEADER + length);

  // Only frames from the device the parent is watching are worth sending.
  const viewers = rooms.get(`user:${info.userId}`);
  if (!viewers || viewers.size === 0) return;

  for (const ws of viewers) {
    if (meta.get(ws)?.kind !== 'parent') continue;
    if (ws.readyState !== ws.OPEN) continue;
    if (ws.bufferedAmount > MAX_BUFFERED_BYTES) continue;
    ws.send(out, { binary: true });
  }
}

async function handleParentCommand(ws, info, msg) {
  const type = msg?.type;
  if (
    type !== 'live.start' &&
    type !== 'live.stop' &&
    type !== 'live.switch' &&
    type !== 'device.command'
  ) {
    return false;
  }

  const deviceId = Number(msg.deviceId);
  if (!Number.isInteger(deviceId) || deviceId <= 0) {
    ws.send(JSON.stringify({
      type: 'live.state',
      deviceId: msg.deviceId ?? null,
      state: 'error',
      error: 'BAD_DEVICE',
    }));
    return true;
  }

  const device = await q.get('SELECT id, user_id FROM devices WHERE id = ?', deviceId);
  if (!device || device.user_id !== info.userId) {
    ws.send(JSON.stringify({
      type: 'live.state',
      deviceId,
      state: 'error',
      error: 'FORBIDDEN',
    }));
    return true;
  }

  if (type === 'live.stop') {
    const delivered = sendToDevice(deviceId, { type: 'live.stop' });
    if (!delivered) {
      ws.send(JSON.stringify({ type: 'live.state', deviceId, state: 'stopped' }));
    }
    return true;
  }

  if (type === 'live.switch') {
    const facing = msg.facing === 'front' ? 'front' : 'back';
    const delivered = sendToDevice(deviceId, { type: 'live.switch', facing });
    if (!delivered) {
      ws.send(JSON.stringify({
        type: 'live.state',
        deviceId,
        state: 'error',
        error: 'DEVICE_OFFLINE',
      }));
    }
    return true;
  }

  if (type === 'device.command') {
    // Remote actions that only need the control socket: currently the loud
    // ring that helps finding a misplaced phone. Every command is explicit
    // and logged by the device itself; nothing is hidden from the child.
    const allowed = new Set(['alarm', 'stop_alarm']);
    const action = allowed.has(String(msg.action)) ? String(msg.action) : null;
    if (!action) {
      ws.send(JSON.stringify({
        type: 'device.command.result',
        deviceId,
        action: msg.action ?? null,
        ok: false,
        error: 'BAD_COMMAND',
      }));
      return true;
    }
    const delivered = sendToDevice(deviceId, { type: 'device.command', action });
    ws.send(JSON.stringify({
      type: 'device.command.result',
      deviceId,
      action,
      ok: delivered,
      // The final result comes back from the device; this first message only
      // tells the dashboard whether the command left the server.
      pending: delivered,
      error: delivered ? undefined : 'DEVICE_OFFLINE',
    }));
    return true;
  }

  const source = msg.source === 'camera' ? 'camera' : 'screen';
  const delivered = sendToDevice(deviceId, { type: 'live.start', source, audio: !!msg.audio });
  if (!delivered) {
    ws.send(JSON.stringify({
      type: 'live.state',
      deviceId,
      state: 'error',
      error: 'DEVICE_OFFLINE',
    }));
  }
  return true;
}

export function attachRealtime(server) {
  const wss = new WebSocketServer({ server, path: '/ws', maxPayload: 8 * 1024 * 1024 });

  wss.on('connection', async (ws, req) => {
    const url = new URL(req.url, 'http://localhost');
    const token = url.searchParams.get('token');
    const claims = token ? verifyToken(token) : null;

    if (!claims) {
      ws.send(JSON.stringify({ type: 'error', error: { code: 'INVALID_TOKEN' } }));
      ws.close(4001, 'invalid token');
      return;
    }

    if (claims.kind === 'parent') {
      const user = await q.get('SELECT id FROM users WHERE id = ?', claims.sub);
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
      const device = await q.get(
        'SELECT id, user_id, device_token FROM devices WHERE id = ?',
        claims.sub,
      );
      if (!device || device.device_token !== claims.jti) {
        ws.close(4001, 'unknown device');
        return;
      }
      meta.set(ws, { kind: 'device', userId: device.user_id, deviceId: device.id });
      join(ws, `device:${device.id}`);
      join(ws, `user:${device.user_id}`);
      deviceSockets.set(device.id, ws);
      ws.send(JSON.stringify({ type: 'hello', kind: 'device', deviceId: device.id }));
    } else {
      ws.close(4001, 'unknown token kind');
      return;
    }

    ws.on('message', (raw, isBinary) => {
      const info = meta.get(ws);
      if (!info) return;

      // ---- live frames (device → server → parent dashboards) -----------
      if (isBinary) {
        if (info.kind !== 'device') return;
        try {
          relayFrame(ws, info, raw);
        } catch {
          /* a malformed frame must never kill the socket */
        }
        return;
      }

      // ---- JSON control -------------------------------------------------
      let msg;
      try {
        msg = JSON.parse(raw.toString());
      } catch {
        return;
      }

      if (msg?.type === 'ping') {
        ws.send(JSON.stringify({ type: 'pong', at: new Date().toISOString() }));
        return;
      }

      if (info.kind === 'device') {
        // The device reports the outcome of a live request.
        if (msg?.type === 'live.state') {
          sendToParents(info.userId, {
            type: 'live.state',
            deviceId: info.deviceId,
            state: typeof msg.state === 'string' ? msg.state : 'error',
            source: msg.source === 'camera' ? 'camera' : msg.source === 'screen' ? 'screen' : undefined,
            error: typeof msg.error === 'string' ? msg.error : undefined,
          });
        } else if (msg?.type === 'live.camera') {
          sendToParents(info.userId, {
            type: 'live.camera',
            deviceId: info.deviceId,
            facing: msg.facing === 'front' ? 'front' : 'back',
          });
        } else if (msg?.type === 'device.command.result') {
          sendToParents(info.userId, {
            type: 'device.command.result',
            deviceId: info.deviceId,
            action: typeof msg.action === 'string' ? msg.action : '',
            ok: msg.ok !== false,
            error: typeof msg.error === 'string' ? msg.error : undefined,
          });
        }
        return;
      }

      if (info.kind === 'parent') {
        handleParentCommand(ws, info, msg).catch(() => {
          try {
            ws.send(JSON.stringify({
              type: 'live.state',
              deviceId: msg?.deviceId ?? null,
              state: 'error',
              error: 'INTERNAL',
            }));
          } catch {
            /* socket already gone */
          }
        });
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
