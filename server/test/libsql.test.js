import test from 'node:test';
import assert from 'node:assert/strict';
import { startMockTurso } from './mock-turso.js';
import { startServer, api, registerParent, enrollDevice } from './helpers.js';

/**
 * The free-tier hosts (Render, Cloudflare) have an ephemeral disk, so the
 * database and the media files must live in a remote database. These tests run
 * the complete flow against a local mock of the Turso HTTP API.
 */

const PORT = 39_411;

test('the full flow works on a remote (libSQL) database', async (t) => {
  const mock = await startMockTurso();
  const server = await startServer(PORT, {
    DB_DRIVER: 'libsql',
    MEDIA_DRIVER: 'db',
    LIBSQL_URL: mock.url,
    LIBSQL_TOKEN: mock.token,
    MEDIA_CHUNK_BYTES: '1024', // force the media bytes to be split in chunks
  });

  t.after(() => {
    server.stop();
    mock.stop();
  });

  // ---------------------------------------------------------------- parent
  const parent = await registerParent(server.base, 'remote@test.local');
  assert.ok(parent.token, 'parent token issued');

  const me = await api(server.base, '/api/auth/me', { token: parent.token });
  assert.equal(me.status, 200);
  assert.equal(me.body.data.user.email, 'remote@test.local');

  // -------------------------------------------------------------- device
  const device = await enrollDevice(server.base, parent.token);  const { deviceId: id, deviceToken: dt } = device;
  assert.ok(id, 'device enrolled');

  const devices = await api(server.base, '/api/devices', { token: parent.token });
  assert.equal(devices.status, 200);
  assert.equal(devices.body.data.length, 1);

  // ------------------------------------------------------------ telemetry
  const loc = await api(server.base, `/api/devices/${id}/location`, {
    method: 'POST',
    deviceToken: dt,
    body: { lat: 36.7538, lng: 3.0588, accuracy: 12, provider: 'gps', battery: 77 },
  });
  assert.equal(loc.status, 200, JSON.stringify(loc.body));

  const usage = await api(server.base, `/api/devices/${id}/events`, {
    method: 'POST',
    deviceToken: dt,
    body: { type: 'app_usage', package: 'com.instagram.android', label: 'Instagram', durationMs: 60000 },
  });
  assert.equal(usage.status, 200, JSON.stringify(usage.body));

  const apps = await api(server.base, `/api/devices/${id}/apps`, {
    method: 'POST',
    deviceToken: dt,
    body: {
      apps: [
        { package: 'com.android.chrome', label: 'Chrome', version: '120' },
        { package: 'com.whatsapp', label: 'WhatsApp', version: '2.24' },
      ],
    },
  });
  assert.equal(apps.status, 200);
  assert.equal(apps.body.data.count, 2);

  const appList = await api(server.base, `/api/devices/${id}/apps`, { token: parent.token });
  assert.equal(appList.body.data.length, 2);

  // ----------------------------------------------------------------- media
  const png = Buffer.from(
    '89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000a49444154789c6300010000050001' +
      '0d0a2db40000000049454e44ae426082',
    'hex',
  );
  // Bigger than MEDIA_CHUNK_BYTES so the payload really is chunked.
  const payload = Buffer.concat([png, Buffer.alloc(4096, 0x41)]);

  const form = new FormData();
  form.append('file', new Blob([payload], { type: 'image/png' }), 'shot.png');
  form.append('meta', JSON.stringify({ app: 'com.chrome', label: 'Chrome' }));

  const up = await api(server.base, `/api/devices/${id}/screenshots`, {
    method: 'POST',
    deviceToken: dt,
    body: form,
  });
  assert.equal(up.status, 201, JSON.stringify(up.body));
  assert.equal(up.body.data.size, payload.length);

  const mediaList = await api(server.base, `/api/devices/${id}/media`, { token: parent.token });
  assert.equal(mediaList.body.data.length, 1);

  const dl = await fetch(`${server.base}/api/devices/${id}/media/${up.body.data.id}`, {
    headers: { Authorization: `Bearer ${parent.token}` },
  });
  assert.equal(dl.status, 200);
  assert.equal(dl.headers.get('content-type'), 'image/png');
  const bytes = Buffer.from(await dl.arrayBuffer());
  assert.equal(bytes.length, payload.length);
  assert.ok(bytes.equals(payload), 'the media bytes survive the round-trip');

  // --------------------------------------------------------------- reading
  const timeline = await api(server.base, `/api/devices/${id}/timeline`, { token: parent.token });
  assert.equal(timeline.status, 200);
  assert.ok(timeline.body.data.length >= 1, 'timeline has entries');
  assert.equal(timeline.body.data[0].kind, 'app_usage');

  const stats = await api(server.base, `/api/devices/${id}/stats`, { token: parent.token });
  assert.equal(stats.status, 200);
  assert.equal(stats.body.data.locationsToday, 1);
  assert.equal(stats.body.data.topApps[0].package, 'com.instagram.android');

  const location = await api(server.base, `/api/devices/${id}/location`, { token: parent.token });
  assert.equal(location.body.data.lat, 36.7538);

  // The database really lived in the remote store — not on the local disk.
  assert.ok(mock.requests.length > 10, 'statements went over HTTP');
});
