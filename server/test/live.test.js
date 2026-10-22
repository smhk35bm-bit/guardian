import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import WebSocket from 'ws';
import { startServer, registerParent, enrollDevice } from './helpers.js';

const PORT = 34571;
let server;
let parentToken;
let device;

before(async () => {
  server = await startServer(PORT);
  parentToken = (await registerParent(server.base, 'live@test.local')).token;
  device = await enrollDevice(server.base, parentToken);
});

after(() => server?.stop());

/**
 * Opens a websocket that keeps JSON and binary messages apart.
 * `waitFor` matches JSON messages; `waitFrame` resolves on the next binary one.
 */
function connect(token) {
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}/ws?token=${encodeURIComponent(token)}`);
  const messages = [];
  const frames = [];
  const waiters = [];
  const frameWaiters = [];

  ws.on('message', (raw, isBinary) => {
    if (isBinary) {
      frames.push(raw);
      const waiter = frameWaiters.shift();
      if (waiter) waiter(raw);
      return;
    }
    const msg = JSON.parse(raw.toString());
    messages.push(msg);
    for (const waiter of [...waiters]) {
      if (waiter.match(msg)) {
        waiters.splice(waiters.indexOf(waiter), 1);
        waiter.resolve(msg);
      }
    }
  });

  const waitFor = (match, timeoutMs = 5000) =>
    new Promise((resolve, reject) => {
      const existing = messages.find(match);
      if (existing) return resolve(existing);
      const timer = setTimeout(
        () => reject(new Error('timed out waiting for a websocket message')),
        timeoutMs,
      );
      waiters.push({
        match,
        resolve: (m) => {
          clearTimeout(timer);
          resolve(m);
        },
      });
      return undefined;
    });

  const waitFrame = (timeoutMs = 5000) =>
    new Promise((resolve, reject) => {
      if (frames.length) return resolve(frames[0]);
      const timer = setTimeout(() => reject(new Error('timed out waiting for a frame')), timeoutMs);
      frameWaiters.push((frame) => {
        clearTimeout(timer);
        resolve(frame);
      });
      return undefined;
    });

  return { ws, messages, frames, waitFor, waitFrame };
}

/** Builds the 12-byte device header plus the payload. */
function deviceFrame({ source = 1, seq = 1, payload }) {
  const head = Buffer.alloc(12);
  head.writeUInt8(1, 0);
  head.writeUInt8(source, 1);
  head.writeUInt16BE(0, 2);
  head.writeUInt32BE(seq, 4);
  head.writeUInt32BE(payload.length, 8);
  return Buffer.concat([head, payload]);
}

test('the parent can ask the device for a live session', async () => {
  const parent = connect(parentToken);
  const parentDevice = connect(device.deviceToken);
  await parent.waitFor((m) => m.type === 'hello');
  await parentDevice.waitFor((m) => m.type === 'hello');

  parent.ws.send(JSON.stringify({ type: 'live.start', deviceId: device.deviceId, source: 'camera' }));
  const command = await parentDevice.waitFor((m) => m.type === 'live.start');
  assert.equal(command.source, 'camera');

  parent.ws.send(JSON.stringify({ type: 'live.stop', deviceId: device.deviceId }));
  const stop = await parentDevice.waitFor((m) => m.type === 'live.stop');
  assert.equal(stop.type, 'live.stop');

  parent.ws.close();
  parentDevice.ws.close();
});

test('a device frame reaches the parent with its id prepended', async () => {
  const parent = connect(parentToken);
  const parentDevice = connect(device.deviceToken);
  await parent.waitFor((m) => m.type === 'hello');
  await parentDevice.waitFor((m) => m.type === 'hello');

  const payload = Buffer.from('jpeg-bytes-here');
  parentDevice.ws.send(deviceFrame({ source: 2, seq: 7, payload }));

  const frame = await parent.waitFrame();
  assert.equal(frame.readUInt8(0), 1);
  assert.equal(frame.readUInt8(1), 2);
  assert.equal(frame.readUInt32BE(4), device.deviceId);
  assert.equal(frame.readUInt32BE(8), 7);
  assert.equal(frame.readUInt32BE(12), payload.length);
  assert.deepEqual(frame.subarray(16), payload);

  parent.ws.close();
  parentDevice.ws.close();
});

test('a malformed frame is dropped without closing the socket', async () => {
  const parent = connect(parentToken);
  const parentDevice = connect(device.deviceToken);
  await parent.waitFor((m) => m.type === 'hello');
  await parentDevice.waitFor((m) => m.type === 'hello');

  parentDevice.ws.send(Buffer.from([9, 9, 9]));

  const command = { type: 'live.start', deviceId: device.deviceId, source: 'screen' };
  parent.ws.send(JSON.stringify(command));
  const delivered = await parentDevice.waitFor((m) => m.type === 'live.start');
  assert.equal(delivered.source, 'screen');

  parent.ws.close();
  parentDevice.ws.close();
});

test('the device reports the live state to the parent', async () => {
  const parent = connect(parentToken);
  const parentDevice = connect(device.deviceToken);
  await parent.waitFor((m) => m.type === 'hello');
  await parentDevice.waitFor((m) => m.type === 'hello');

  parentDevice.ws.send(
    JSON.stringify({ type: 'live.state', state: 'error', source: 'screen', error: 'NO_CONSENT' }),
  );
  const state = await parent.waitFor((m) => m.type === 'live.state');
  assert.equal(state.deviceId, device.deviceId);
  assert.equal(state.state, 'error');
  assert.equal(state.error, 'NO_CONSENT');

  parent.ws.close();
  parentDevice.ws.close();
});

test('starting a session on an offline device answers DEVICE_OFFLINE', async () => {
  const parent = connect(parentToken);
  await parent.waitFor((m) => m.type === 'hello');

  parent.ws.send(JSON.stringify({ type: 'live.start', deviceId: device.deviceId, source: 'screen' }));
  const state = await parent.waitFor((m) => m.type === 'live.state' && m.state === 'error');
  assert.equal(state.error, 'DEVICE_OFFLINE');

  parent.ws.close();
});

test('a parent cannot stream another parent’s device', async () => {
  const stranger = (await registerParent(server.base, 'stranger@test.local')).token;
  const client = connect(stranger);
  await client.waitFor((m) => m.type === 'hello');

  client.ws.send(JSON.stringify({ type: 'live.start', deviceId: device.deviceId, source: 'screen' }));
  const state = await client.waitFor((m) => m.type === 'live.state' && m.state === 'error');
  assert.equal(state.error, 'FORBIDDEN');

  client.ws.close();
});
