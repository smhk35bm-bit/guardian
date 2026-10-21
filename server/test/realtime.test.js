import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import WebSocket from 'ws';
import { startServer, api, registerParent, enrollDevice } from './helpers.js';

const PORT = 34568;
let server;
let parentToken;
let device;

before(async () => {
  server = await startServer(PORT);
  parentToken = (await registerParent(server.base, 'ws@test.local')).token;
  device = await enrollDevice(server.base, parentToken);
});

after(() => server?.stop());

/** Opens a websocket and resolves once the server says hello. */
function connect(token) {
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}/ws?token=${encodeURIComponent(token)}`);
  const messages = [];
  const waiters = [];

  ws.on('message', (raw) => {
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
        () => reject(new Error('timed out waiting for websocket message')),
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

  return { ws, messages, waitFor };
}

test('parent websocket receives live location updates', async () => {
  const client = connect(parentToken);
  await client.waitFor((m) => m.type === 'hello');
  assert.equal(client.messages[0].kind, 'parent');

  await api(server.base, `/api/devices/${device.deviceId}/location`, {
    method: 'POST',
    deviceToken: device.deviceToken,
    body: { lat: 24.7136, lng: 46.6753, accuracy: 10, provider: 'gps' },
  });

  const msg = await client.waitFor((m) => m.type === 'device.location');
  assert.equal(msg.deviceId, device.deviceId);
  assert.equal(msg.data.lat, 24.7136);
  assert.equal(msg.data.lng, 46.6753);

  client.ws.close();
});

test('parent websocket receives heartbeat and usage events', async () => {
  const client = connect(parentToken);
  await client.waitFor((m) => m.type === 'hello');

  await api(server.base, `/api/devices/${device.deviceId}/heartbeat`, {
    method: 'POST',
    deviceToken: device.deviceToken,
    body: { battery: 55, charging: true, wifi: 'School' },
  });
  const hb = await client.waitFor((m) => m.type === 'device.heartbeat');
  assert.equal(hb.data.battery, 55);
  assert.equal(hb.data.wifi, 'School');

  await api(server.base, `/api/devices/${device.deviceId}/events`, {
    method: 'POST',
    deviceToken: device.deviceToken,
    body: { type: 'app_usage', package: 'com.example', label: 'Example', durationMs: 1000 },
  });
  const usage = await client.waitFor((m) => m.type === 'device.usage');
  assert.equal(usage.data.package, 'com.example');

  client.ws.close();
});

test('a websocket with an invalid token is rejected', async () => {
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}/ws?token=not-a-real-token`);
  const closed = await new Promise((resolve) => {
    ws.on('close', (code) => resolve(code));
    ws.on('error', () => resolve('error'));
  });
  assert.notEqual(closed, 1000, 'the server must not accept an unauthenticated socket');
});

test('a device websocket is accepted and identified', async () => {
  const client = connect(device.deviceToken);
  const hello = await client.waitFor((m) => m.type === 'hello');
  assert.equal(hello.kind, 'device');
  assert.equal(hello.deviceId, device.deviceId);
  client.ws.close();
});
