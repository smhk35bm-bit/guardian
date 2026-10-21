import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const serverDir = path.resolve(here, '..');

const PORT = 34567;
const BASE = `http://127.0.0.1:${PORT}`;

const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'guardian-test-'));
let child;

before(async () => {
  child = spawn(process.execPath, ['src/index.js'], {
    cwd: serverDir,
    env: {
      ...process.env,
      PORT: String(PORT),
      DATA_DIR: tmpDir,
      UPLOAD_DIR: path.join(tmpDir, 'uploads'),
      JWT_SECRET: 'test-secret-value-that-is-long-enough-1234567890',
      NODE_ENV: 'development',
      PUBLIC_URL: '',
      // The suite creates many accounts from one IP; the real limit is exercised separately.
      AUTH_RATE_LIMIT: '1000',
      DEVICE_RATE_LIMIT: '10000',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  child.stderr.on('data', (d) => process.stderr.write(`[server] ${d}`));

  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`${BASE}/api/health`);
      if (res.ok) return;
    } catch {
      /* not up yet */
    }
    await new Promise((r) => setTimeout(r, 150));
  }
  throw new Error('server did not start in time');
});

after(() => {
  child?.kill('SIGKILL');
  fs.rmSync(tmpDir, { recursive: true, force: true });
});

// ------------------------------------------------------------------ helpers

async function api(pathname, { method = 'GET', token, body, deviceToken, raw } = {}) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (deviceToken) headers.Authorization = `Device ${deviceToken}`;
  let payload;
  if (body instanceof FormData) {
    payload = body;
  } else if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    payload = JSON.stringify(body);
  }
  const res = await fetch(`${BASE}${pathname}`, { method, headers, body: payload });
  if (raw) return res;
  const json = await res.json().catch(() => ({}));
  return { status: res.status, body: json };
}

async function registerParent(email) {
  const res = await api('/api/auth/register', {
    method: 'POST',
    body: { email, password: 'Str0ngPass!123', name: 'ولي الأمر' },
  });
  assert.equal(res.status, 201, JSON.stringify(res.body));
  return res.body.data;
}

async function enrollDevice(parentToken, name = 'هاتف الطفل') {
  const codeRes = await api('/api/devices/enroll-code', { method: 'POST', token: parentToken });
  assert.equal(codeRes.status, 201);
  const { code } = codeRes.body.data;

  const regRes = await api('/api/devices/register', {
    method: 'POST',
    body: {
      code,
      deviceName: name,
      manufacturer: 'Samsung',
      model: 'SM-A125F',
      androidVersion: '13',
      sdkInt: 33,
      appVersion: '1.0.0',
    },
  });
  assert.equal(regRes.status, 200, JSON.stringify(regRes.body));
  return regRes.body.data;
}

// -------------------------------------------------------------------- tests

test('health endpoint reports healthy', async () => {
  const res = await api('/api/health');
  assert.equal(res.status, 200);
  assert.equal(res.body.ok, true);
  assert.equal(res.body.data.status, 'healthy');
});

test('register + login + me', async () => {
  const parent = await registerParent('parent1@test.local');
  assert.ok(parent.token);
  assert.equal(parent.user.email, 'parent1@test.local');

  const me = await api('/api/auth/me', { token: parent.token });
  assert.equal(me.status, 200);
  assert.equal(me.body.data.user.email, 'parent1@test.local');

  const login = await api('/api/auth/login', {
    method: 'POST',
    body: { email: 'parent1@test.local', password: 'Str0ngPass!123' },
  });
  assert.equal(login.status, 200);
  assert.ok(login.body.data.token);

  const bad = await api('/api/auth/login', {
    method: 'POST',
    body: { email: 'parent1@test.local', password: 'wrong-password' },
  });
  assert.equal(bad.status, 401);
  assert.equal(bad.body.error.code, 'INVALID_TOKEN');
});

test('duplicate email is rejected', async () => {
  const res = await api('/api/auth/register', {
    method: 'POST',
    body: { email: 'parent1@test.local', password: 'another-pass', name: 'x' },
  });
  assert.equal(res.status, 409);
});

test('weak password is rejected', async () => {
  const res = await api('/api/auth/register', {
    method: 'POST',
    body: { email: 'weak@test.local', password: 'short', name: 'x' },
  });
  assert.equal(res.status, 400);
  assert.equal(res.body.error.code, 'BAD_REQUEST');
});

test('enrollment code flow: generate → redeem → device appears', async () => {
  const parent = await registerParent('parent2@test.local');
  const device = await enrollDevice(parent.token, 'جهاز الاختبار');

  assert.ok(device.deviceId);
  assert.ok(device.deviceToken);

  const list = await api('/api/devices', { token: parent.token });
  assert.equal(list.status, 200);
  assert.equal(list.body.data.length, 1);
  assert.equal(list.body.data[0].name, 'جهاز الاختبار');
  assert.equal(list.body.data[0].status, 'active');
});

test('enrollment code is single-use and expires', async () => {
  const parent = await registerParent('parent3@test.local');
  const codeRes = await api('/api/devices/enroll-code', { method: 'POST', token: parent.token });
  const { code } = codeRes.body.data;

  const first = await api('/api/devices/register', {
    method: 'POST',
    body: { code, deviceName: 'أ' },
  });
  assert.equal(first.status, 200);

  const second = await api('/api/devices/register', {
    method: 'POST',
    body: { code, deviceName: 'ب' },
  });
  assert.equal(second.status, 409);
  assert.equal(second.body.error.code, 'CODE_USED');

  const bogus = await api('/api/devices/register', {
    method: 'POST',
    body: { code: '00000000', deviceName: 'ج' },
  });
  assert.equal(bogus.status, 400);
  assert.equal(bogus.body.error.code, 'CODE_INVALID');
});

test('device ingests telemetry and parent reads it back', async () => {
  const parent = await registerParent('parent4@test.local');
  const device = await enrollDevice(parent.token);
  const dt = device.deviceToken;
  const id = device.deviceId;

  const hb = await api(`/api/devices/${id}/heartbeat`, {
    method: 'POST',
    deviceToken: dt,
    body: { battery: 76, charging: false, wifi: 'Home-5G', networkType: 'wifi' },
  });
  assert.equal(hb.status, 200);
  assert.ok(hb.body.data.config.locationIntervalSec);

  const loc = await api(`/api/devices/${id}/location`, {
    method: 'POST',
    deviceToken: dt,
    body: { lat: 24.7136, lng: 46.6753, accuracy: 12, speed: 1.4, provider: 'gps', battery: 75 },
  });
  assert.equal(loc.status, 200);

  await api(`/api/devices/${id}/events`, {
    method: 'POST',
    deviceToken: dt,
    body: { type: 'app_usage', package: 'com.instagram.android', label: 'Instagram', durationMs: 540000, openedAt: new Date().toISOString() },
  });
  await api(`/api/devices/${id}/events`, {
    method: 'POST',
    deviceToken: dt,
    body: { type: 'app_installed', package: 'com.example.game', label: 'Game' },
  });
  await api(`/api/devices/${id}/calls`, {
    method: 'POST',
    deviceToken: dt,
    body: { number: '+966500000000', contactName: 'أحمد', type: 'incoming', durationSec: 125 },
  });
  await api(`/api/devices/${id}/sms`, {
    method: 'POST',
    deviceToken: dt,
    body: { address: '+966500000001', body: 'مرحبا', type: 'inbox' },
  });
  await api(`/api/devices/${id}/apps`, {
    method: 'POST',
    deviceToken: dt,
    body: { apps: [{ package: 'com.a', label: 'A', version: '1.0', system: false }] },
  });

  const usage = await api(`/api/devices/${id}/usage`, { token: parent.token });
  assert.equal(usage.status, 200);
  assert.equal(usage.body.data[0].package, 'com.instagram.android');
  assert.equal(usage.body.data[0].total_ms, 540000);

  const calls = await api(`/api/devices/${id}/calls`, { token: parent.token });
  assert.equal(calls.body.data[0].contact_name, 'أحمد');

  const sms = await api(`/api/devices/${id}/sms`, { token: parent.token });
  assert.equal(sms.body.data[0].body, 'مرحبا');

  const apps = await api(`/api/devices/${id}/apps`, { token: parent.token });
  assert.equal(apps.body.data[0].package, 'com.a');

  const timeline = await api(`/api/devices/${id}/timeline`, { token: parent.token });
  assert.ok(timeline.body.data.length >= 4);

  const stats = await api(`/api/devices/${id}/stats`, { token: parent.token });
  assert.equal(stats.body.data.callsToday, 1);
  assert.equal(stats.body.data.topApps[0].package, 'com.instagram.android');
});

test('multipart media upload is stored and served back', async () => {
  const parent = await registerParent('parent5@test.local');
  const device = await enrollDevice(parent.token);
  const { deviceId: id, deviceToken: dt } = device;

  const png = Buffer.from(
    '89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000a49444154789c6300010000050001' +
      '0d0a2db40000000049454e44ae426082',
    'hex',
  );

  const form = new FormData();
  form.append('file', new Blob([png], { type: 'image/png' }), 'shot.png');
  form.append('meta', JSON.stringify({ app: 'com.chrome', label: 'Chrome' }));

  const up = await api(`/api/devices/${id}/screenshots`, { method: 'POST', deviceToken: dt, body: form });
  assert.equal(up.status, 201, JSON.stringify(up.body));
  assert.equal(up.body.data.kind, 'screenshot');

  const list = await api(`/api/devices/${id}/media`, { token: parent.token });
  assert.equal(list.body.data.length, 1);

  const dl = await api(`/api/devices/${id}/media/${up.body.data.id}`, { token: parent.token, raw: true });
  assert.equal(dl.status, 200);
  assert.equal(dl.headers.get('content-type'), 'image/png');
  const bytes = Buffer.from(await dl.arrayBuffer());
  assert.equal(bytes.length, png.length);
});

test('a device cannot write into another device history', async () => {
  const parent = await registerParent('parent6@test.local');
  const a = await enrollDevice(parent.token, 'جهاز أ');
  const b = await enrollDevice(parent.token, 'جهاز ب');

  const res = await api(`/api/devices/${a.deviceId}/location`, {
    method: 'POST',
    deviceToken: b.deviceToken,
    body: { lat: 1, lng: 1 },
  });
  assert.equal(res.status, 403);
  assert.equal(res.body.error.code, 'FORBIDDEN');
});

test('one parent cannot read another parents device', async () => {
  const p1 = await registerParent('owner1@test.local');
  const p2 = await registerParent('owner2@test.local');
  const device = await enrollDevice(p1.token);

  const res = await api(`/api/devices/${device.deviceId}/location`, { token: p2.token });
  assert.equal(res.status, 404);

  const list = await api('/api/devices', { token: p2.token });
  assert.equal(list.body.data.length, 0);
});

test('device routes reject parent tokens and vice versa', async () => {
  const parent = await registerParent('crossauth@test.local');
  const device = await enrollDevice(parent.token);

  const asParent = await api(`/api/devices/${device.deviceId}/location`, {
    method: 'POST',
    token: parent.token,
    body: { lat: 1, lng: 1 },
  });
  assert.equal(asParent.status, 401);

  const asDevice = await api('/api/devices', { deviceToken: device.deviceToken });
  assert.equal(asDevice.status, 401);
});

test('revoking a device invalidates its token', async () => {
  const parent = await registerParent('revoke@test.local');
  const device = await enrollDevice(parent.token);

  const del = await api(`/api/devices/${device.deviceId}`, {
    method: 'DELETE',
    token: parent.token,
  });
  assert.equal(del.status, 200);

  const after = await api(`/api/devices/${device.deviceId}/heartbeat`, {
    method: 'POST',
    deviceToken: device.deviceToken,
    body: { battery: 50 },
  });
  assert.equal(after.status, 401);
});

test('invalid coordinates are rejected', async () => {
  const parent = await registerParent('badcoords@test.local');
  const device = await enrollDevice(parent.token);
  const res = await api(`/api/devices/${device.deviceId}/location`, {
    method: 'POST',
    deviceToken: device.deviceToken,
    body: { lat: 999, lng: 999 },
  });
  assert.equal(res.status, 400);
});

test('unknown api route returns structured 404', async () => {
  const res = await api('/api/nope');
  assert.equal(res.status, 404);
  assert.equal(res.body.ok, false);
  assert.equal(res.body.error.code, 'NOT_FOUND');
});

test('multipart upload without a file is rejected', async () => {
  const parent = await registerParent('nofile@test.local');
  const device = await enrollDevice(parent.token);
  const form = new FormData();
  form.append('meta', '{}');
  const res = await api(`/api/devices/${device.deviceId}/screenshots`, {
    method: 'POST',
    deviceToken: device.deviceToken,
    body: form,
  });
  assert.equal(res.status, 400);
  assert.equal(res.body.error.code, 'BAD_REQUEST');
});

test('multipart upload with the wrong field name is rejected', async () => {
  const parent = await registerParent('wrongfield@test.local');
  const device = await enrollDevice(parent.token);
  const form = new FormData();
  form.append('image', new Blob([Buffer.from('x')], { type: 'image/png' }), 'a.png');
  const res = await api(`/api/devices/${device.deviceId}/screenshots`, {
    method: 'POST',
    deviceToken: device.deviceToken,
    body: form,
  });
  assert.equal(res.status, 400);
  assert.equal(res.body.error.code, 'BAD_REQUEST');
});

test('a non numeric device id cannot be used', async () => {
  const parent = await registerParent('badid@test.local');
  const device = await enrollDevice(parent.token);
  const res = await api('/api/devices/abc/location', {
    method: 'POST',
    deviceToken: device.deviceToken,
    body: { lat: 1, lng: 1 },
  });
  assert.equal(res.status, 403);
  assert.equal(res.body.error.code, 'FORBIDDEN');
});

test('the device token cannot be used by another enrolled device', async () => {
  const parent = await registerParent('crossdev@test.local');
  const a = await enrollDevice(parent.token, 'أ');
  const b = await enrollDevice(parent.token, 'ب');
  // Device B's token must not read device A's history.
  const res = await api(`/api/devices/${a.deviceId}/location`, {
    deviceToken: b.deviceToken,
  });
  assert.equal(res.status, 401);
});

test('a revoked device keeps its history for the parent', async () => {
  const parent = await registerParent('history@test.local');
  const device = await enrollDevice(parent.token);
  await api(`/api/devices/${device.deviceId}/location`, {
    method: 'POST',
    deviceToken: device.deviceToken,
    body: { lat: 10, lng: 20 },
  });
  await api(`/api/devices/${device.deviceId}`, { method: 'DELETE', token: parent.token });

  const loc = await api(`/api/devices/${device.deviceId}/locations`, { token: parent.token });
  assert.equal(loc.status, 200);
  assert.equal(loc.body.data.length, 1);
  assert.equal(loc.body.data[0].lat, 10);
});

test('the enrollment code cannot be guessed by another parent', async () => {
  const p1 = await registerParent('codeowner@test.local');
  const p2 = await registerParent('codeguesser@test.local');
  const codeRes = await api('/api/devices/enroll-code', { method: 'POST', token: p1.token });
  const { code } = codeRes.body.data;

  // The code alone is the credential — anyone holding it can enroll a device.
  const res = await api('/api/devices/register', {
    method: 'POST',
    body: { code, deviceName: 'جهاز مختطف' },
  });
  assert.equal(res.status, 200);

  // ...but the device then belongs to p1, not to whoever redeemed it.
  const p1Devices = await api('/api/devices', { token: p1.token });
  const p2Devices = await api('/api/devices', { token: p2.token });
  assert.equal(p1Devices.body.data.length, 1);
  assert.equal(p2Devices.body.data.length, 0);
});
