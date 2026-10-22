import http from 'node:http';
import { DatabaseSync } from 'node:sqlite';

/**
 * A local stand-in for a Turso (libSQL) database.
 *
 * It speaks the same `/v2/pipeline` HTTP protocol as the real service and
 * executes the statements against a real SQLite engine, so the driver's
 * encoding and decoding are validated against real SQL — without needing a
 * Turso account.
 */

function encodeValue(value) {
  if (value === undefined || value === null) return { type: 'null' };
  if (typeof value === 'boolean') return { type: 'integer', value: value ? 1 : 0 };
  if (typeof value === 'bigint') return { type: 'integer', value: Number(value) };
  if (typeof value === 'number') {
    return Number.isInteger(value) ? { type: 'integer', value } : { type: 'float', value };
  }
  if (value instanceof Uint8Array) {
    return { type: 'blob', base64: Buffer.from(value).toString('base64') };
  }
  return { type: 'text', value: String(value) };
}

/**
 * Decodes a typed value, enforcing the same strictness as the real service:
 * an integer must arrive as a string, a float as a number. The mock is strict
 * on purpose — otherwise a broken encoder would pass the tests and only fail
 * against the real database.
 */
function decodeArg(arg) {
  if (!arg || typeof arg !== 'object') return null;
  switch (arg.type) {
    case 'null':
      return null;
    case 'integer':
      if (typeof arg.value !== 'string') {
        throw new Error('integer value must be a string (got ' + typeof arg.value + ')');
      }
      return Number(arg.value);
    case 'float':
      if (typeof arg.value !== 'number') {
        throw new Error('float value must be a number (got ' + typeof arg.value + ')');
      }
      return arg.value;
    case 'blob':
      return Buffer.from(String(arg.base64 ?? arg.value ?? ''), 'base64');
    default:
      return arg.value === undefined || arg.value === null ? null : String(arg.value);
  }
}

function returnsRows(sql) {
  return /^\s*(select|with|pragma|explain)\b/i.test(sql);
}

function columnNames(statement, rows) {
  if (typeof statement.columns === 'function') {
    try {
      return statement.columns().map((c) => c.name);
    } catch {
      /* fall through */
    }
  }
  return rows.length ? Object.keys(rows[0]) : [];
}

/** Starts the mock server. Returns `{ url, token, requests, stop }`. */
export async function startMockTurso({ token = 'test-token' } = {}) {
  const db = new DatabaseSync(':memory:');
  const requests = [];

  const server = http.createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const body = Buffer.concat(chunks).toString('utf8');

      if (!req.url.endsWith('/v2/pipeline')) {
        res.writeHead(404).end('not found');
        return;
      }
      if ((req.headers.authorization || '') !== `Bearer ${token}`) {
        res.writeHead(401).end('unauthorized');
        return;
      }

      let payload;
      try {
        payload = JSON.parse(body || '{}');
      } catch {
        res.writeHead(400).end('bad json');
        return;
      }
      requests.push(payload);

      const results = [];
      for (const entry of payload.requests ?? []) {
        if (entry.type === 'close') {
          results.push({ type: 'ok', response: { type: 'close' } });
          continue;
        }
        if (entry.type !== 'execute') {
          results.push({ type: 'error', error: { message: `unsupported request ${entry.type}` } });
          continue;
        }
        try {
          const stmt = entry.stmt ?? {};
          const args = (stmt.args ?? []).map(decodeArg);
          const prepared = db.prepare(stmt.sql);
          if (returnsRows(stmt.sql)) {
            const rows = prepared.all(...args);
            const cols = columnNames(prepared, rows);
            results.push({
              type: 'ok',
              response: {
                type: 'execute',
                result: {
                  cols: cols.map((name) => ({ name, decltype: 'TEXT' })),
                  rows: rows.map((row) => cols.map((name) => encodeValue(row[name]))),
                  affected_row_count: 0,
                },
              },
            });
          } else {
            const r = prepared.run(...args);
            results.push({
              type: 'ok',
              response: {
                type: 'execute',
                result: {
                  cols: [],
                  rows: [],
                  affected_row_count: Number(r.changes),
                  last_insert_rowid: String(r.lastInsertRowid),
                },
              },
            });
          }
        } catch (err) {
          results.push({ type: 'error', error: { message: err.message } });
        }
      }

      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ baton: null, base_url: null, results }));
    });
  });

  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address();

  return {
    url: `http://127.0.0.1:${port}`,
    token,
    requests,
    stop() {
      server.close();
      db.close();
    },
  };
}
