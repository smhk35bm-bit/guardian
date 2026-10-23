import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import WebSocket from 'ws';
import { startServer, registerParent, enrollDevice, api } from './helpers.js';

const PORT = 34578;
let server;
let parentToken;
let device;

before(async () => {
  server = await startServer(PORT);
  parentToken = (await registerParent(server.base, 'safety@test.local')).token;
  device = await enrollDevice(server.base, parentToken, 'هاتف الاختبار');
});

after(() => server?.stop());

/** Opens a websocket that resolves `waitFor(match)` on JSON messages. */
function connect(token) {
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}/ws?token=${encodeURIComponent(token)}`);
  const messages = [];
  const waiters = [];

  ws.on('message', (raw, isBinary) => {
    if (isBinary) return;
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
        resolve: (msg) => {
          clearTimeout(timer);
          resolve(msg);
        },
      });
      return undefined;
    });

  const ready = new Promise((resolve, reject) => {
    ws.on('open', resolve);
    ws.on('error', reject);
  });

  return { ws, waitFor, ready, messages };
}

test('the child address book is stored once and read by the parent', async () => {
  const payload = {
    contacts: [
      { name: 'أحمد', number: '+966500000001', email: 'ahmed@example.com' },
      { name: 'سارة', number: '+966500000002' },
      { name: 'بلا رقم' },
    ],
  };
  const first = await api(server.base, `/api/devices/${device.deviceId}/contacts`, {
    method: 'POST',
    deviceToken: device.deviceToken,
    body: payload,
  });
  assert.equal(first.status, 200);
  assert.equal(first.body.data.count, 2);

  // A second sync updates instead of duplicating.
  const second = await api(server.base, `/api/devices/${device.deviceId}/contacts`, {
    method: 'POST',
    deviceToken: device.deviceToken,
    body: { contacts: [{ name: 'أحمد علي', number: '+966500000001' }] },
  });
  assert.equal(second.status, 200);

  const list = await api(server.base, `/api/devices/${device.deviceId}/contacts`, {
    token: parentToken,
  });
  assert.equal(list.status, 200);
  assert.equal(list.body.data.length, 2);
  const ahmed = list.body.data.find((c) => c.number === '+966500000001');
  assert.equal(ahmed.name, 'أحمد علي');

  const search = await api(server.base, `/api/devices/${device.deviceId}/contacts?q=سارة`, {
    token: parentToken,
  });
  assert.equal(search.body.data.length, 1);
});

test('another parent cannot read the address book', async () => {
  const stranger = (await registerParent(server.base, 'stranger@test.local')).token;
  const res = await api(server.base, `/api/devices/${device.deviceId}/contacts`, {
    token: stranger,
  });
  assert.equal(res.status, 404);
});

test('a safe zone fires one alert per boundary crossing', async () => {
  const created = await api(server.base, `/api/devices/${device.deviceId}/geofences`, {
    method: 'POST',
    token: parentToken,
    body: { name: 'المنزل', lat: 35.32758, lng: -0.05293, radiusM: 150 },
  });
  assert.equal(created.status, 201);
  assert.equal(created.body.data.name, 'المنزل');

  const post = (lat, lng) =>
    api(server.base, `/api/devices/${device.deviceId}/location`, {
      method: 'POST',
      deviceToken: device.deviceToken,
      body: { lat, lng, accuracy: 10, provider: 'gps' },
    });

  // The first fix only seeds the state — no alert.
  await post(35.32758, -0.05293);
  let alerts = await api(server.base, '/api/alerts', { token: parentToken });
  assert.equal(alerts.body.data.length, 0);

  // Staying inside must not repeat the alert.
  await post(35.3276, -0.0529);
  alerts = await api(server.base, '/api/alerts', { token: parentToken });
  assert.equal(alerts.body.data.length, 0);

  // Leaving the 150 m radius creates exactly one exit alert.
  await post(35.4, -0.05293);
  alerts = await api(server.base, '/api/alerts', { token: parentToken });
  assert.equal(alerts.body.data.length, 1);
  assert.equal(alerts.body.data[0].type, 'geofence_exit');
  assert.equal(alerts.body.meta.unread, 1);

  // Coming back creates the matching entry alert.
  await post(35.32758, -0.05293);
  alerts = await api(server.base, '/api/alerts', { token: parentToken });
  assert.equal(alerts.body.data.length, 2);
  assert.equal(alerts.body.data[0].type, 'geofence_enter');
  assert.equal(alerts.body.meta.unread, 2);

  const fresh = await api(
    server.base,
    `/api/devices/${device.deviceId}/geofences`,
    { token: parentToken },
  );
  assert.equal(fresh.body.data.length, 1);
  assert.equal(Number(fresh.body.data[0].inside), 1);
});

test('an invalid safe zone is rejected', async () => {
  const res = await api(server.base, `/api/devices/${device.deviceId}/geofences`, {
    method: 'POST',
    token: parentToken,
    body: { name: 'خاطئ', lat: 200, lng: 0, radiusM: 100 },
  });
  assert.equal(res.status, 400);
  assert.equal(res.body.error.code, 'BAD_REQUEST');
});

test('alerts can be marked as read', async () => {
  const before = await api(server.base, '/api/alerts', { token: parentToken });
  assert.ok(before.body.meta.unread > 0);

  const read = await api(server.base, '/api/alerts/read', {
    method: 'POST',
    token: parentToken,
    body: { ids: [] },
  });
  assert.equal(read.status, 200);

  const after = await api(server.base, '/api/alerts', { token: parentToken });
  assert.equal(after.body.meta.unread, 0);
  assert.ok(after.body.data.every((a) => a.read === true));
});

test('a safe zone can be deleted', async () => {
  const list = await api(server.base, `/api/devices/${device.deviceId}/geofences`, {
    token: parentToken,
  });
  const id = list.body.data[0].id;
  const removed = await api(
    server.base,
    `/api/devices/${device.deviceId}/geofences/${id}`,
    { method: 'DELETE', token: parentToken },
  );
  assert.equal(removed.status, 200);
  const fresh = await api(server.base, `/api/devices/${device.deviceId}/geofences`, {
    token: parentToken,
  });
  assert.equal(fresh.body.data.length, 0);
});

test('the parent can ring the device over the control socket', async () => {
  const parent = connect(parentToken);
  const child = connect(device.deviceToken);
  await Promise.all([parent.ready, child.ready]);
  await parent.waitFor((m) => m.type === 'hello');
  await child.waitFor((m) => m.type === 'hello');

  parent.ws.send(
    JSON.stringify({ type: 'device.command', deviceId: device.deviceId, action: 'alarm' }),
  );
  const command = await child.waitFor((m) => m.type === 'device.command');
  assert.equal(command.action, 'alarm');

  const ack = await parent.waitFor((m) => m.type === 'device.command.result');
  assert.equal(ack.action, 'alarm');
  assert.equal(ack.ok, true);

  child.ws.send(JSON.stringify({ type: 'device.command.result', action: 'alarm', ok: true }));
  const reported = await parent.waitFor(
    (m) => m.type === 'device.command.result' && m.ok === true,
  );
  assert.equal(reported.action, 'alarm');

  parent.ws.close();
  child.ws.close();
});

test('an unknown device command is refused', async () => {
  const parent = connect(parentToken);
  await parent.ready;
  await parent.waitFor((m) => m.type === 'hello');

  parent.ws.send(
    JSON.stringify({ type: 'device.command', deviceId: device.deviceId, action: 'wipe' }),
  );
  const result = await parent.waitFor((m) => m.type === 'device.command.result');
  assert.equal(result.ok, false);
  assert.equal(result.error, 'BAD_COMMAND');
  parent.ws.close();
});
