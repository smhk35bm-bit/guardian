import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
export const serverDir = path.resolve(here, '..');

/**
 * Boots a throwaway server instance on its own port with an isolated data dir.
 * `extraEnv` lets a test switch the storage topology (e.g. a remote libSQL
 * database instead of the local file).
 * Returns the base URL and a stop() function.
 */
export async function startServer(port, extraEnv = {}) {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'guardian-test-'));
  const base = `http://127.0.0.1:${port}`;

  const child = spawn(process.execPath, ['src/index.js'], {
    cwd: serverDir,
    env: {
      ...process.env,
      PORT: String(port),
      DATA_DIR: tmpDir,
      UPLOAD_DIR: path.join(tmpDir, 'uploads'),
      JWT_SECRET: 'test-secret-value-that-is-long-enough-1234567890',
      NODE_ENV: 'development',
      PUBLIC_URL: '',
      AUTH_RATE_LIMIT: '1000',
      DEVICE_RATE_LIMIT: '10000',
      ...extraEnv,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  child.stderr.on('data', (d) => process.stderr.write(`[server] ${d}`));

  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`${base}/api/health`);
      if (res.ok) {
        return {
          base,
          child,
          stop() {
            child.kill('SIGKILL');
            fs.rmSync(tmpDir, { recursive: true, force: true });
          },
        };
      }
    } catch {
      /* not up yet */
    }
    await new Promise((r) => setTimeout(r, 150));
  }
  child.kill('SIGKILL');
  fs.rmSync(tmpDir, { recursive: true, force: true });
  throw new Error('server did not start in time');
}

/** Calls the API and returns { status, body }. */
export async function api(base, pathname, { method = 'GET', token, deviceToken, body } = {}) {
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
  const res = await fetch(`${base}${pathname}`, { method, headers, body: payload });
  const json = await res.json().catch(() => ({}));
  return { status: res.status, body: json };
}

/** Registers a parent account and returns its token. */
export async function registerParent(base, email) {
  const res = await api(base, '/api/auth/register', {
    method: 'POST',
    body: { email, password: 'Str0ngPass!123', name: 'ولي الأمر' },
  });
  return res.body.data;
}

/** Runs the full enrolment handshake and returns the device credentials. */
export async function enrollDevice(base, parentToken, name = 'هاتف الطفل') {
  const codeRes = await api(base, '/api/devices/enroll-code', { method: 'POST', token: parentToken });
  const { code } = codeRes.body.data;
  const regRes = await api(base, '/api/devices/register', {
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
  return regRes.body.data;
}
