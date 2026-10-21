/**
 * libSQL / Turso driver — talks SQL over plain HTTP (the `/v2/pipeline`
 * endpoint), so it needs no native module and works on any Node host.
 *
 * This is what makes the "free tier with an ephemeral disk" deployment
 * possible: the database lives in Turso instead of on the server's disk.
 *
 * Wire format (https://docs.turso.tech/sdk/http/reference):
 *   POST <base>/v2/pipeline
 *   { "requests": [ { "type": "execute", "stmt": { "sql": "...", "args": [...] } } ] }
 *   -> { "results": [ { "type": "ok", "response": { "result": { "cols": [], "rows": [] } } } ] }
 */

const DEFAULT_TIMEOUT_MS = 20_000;

/** Encodes a JS value into the typed-value shape used on the wire. */
export function encodeArg(value) {
  if (value === undefined || value === null) return { type: 'null' };
  if (typeof value === 'boolean') return { type: 'integer', value: value ? 1 : 0 };
  if (typeof value === 'number') {
    return Number.isInteger(value)
      ? { type: 'integer', value }
      : { type: 'float', value };
  }
  if (typeof value === 'bigint') return { type: 'integer', value: Number(value) };
  if (value instanceof Uint8Array) {
    return { type: 'blob', base64: Buffer.from(value).toString('base64') };
  }
  return { type: 'text', value: String(value) };
}

/** Decodes a typed value coming back from the server. */
export function decodeValue(cell) {
  if (cell === null || cell === undefined) return null;
  if (typeof cell !== 'object') return cell;
  switch (cell.type) {
    case 'null':
      return null;
    case 'integer':
      return typeof cell.value === 'number' ? cell.value : Number(cell.value ?? 0);
    case 'float':
      return typeof cell.value === 'number' ? cell.value : Number(cell.value ?? 0);
    case 'blob': {
      const b64 = cell.base64 ?? cell.value ?? '';
      return Buffer.from(String(b64), 'base64');
    }
    case 'text':
    default:
      return cell.value === undefined || cell.value === null ? null : String(cell.value);
  }
}

function toExecuteRequest(sql, args) {
  const stmt = { sql };
  if (args && args.length) stmt.args = args.map(encodeArg);
  return { type: 'execute', stmt };
}

export function createTursoDriver({
  url,
  token,
  fetchImpl = globalThis.fetch,
  timeoutMs = DEFAULT_TIMEOUT_MS,
}) {
  if (!url) throw new Error('libSQL driver requires a URL (LIBSQL_URL)');
  const base = String(url)
    .trim()
    .replace(/^libsql:\/\//i, 'https://')
    .replace(/\/+$/, '');
  const endpoint = `${base}/v2/pipeline`;

  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;

  async function pipeline(requests) {
    const res = await fetchImpl(endpoint, {
      method: 'POST',
      headers,
      body: JSON.stringify({ requests }),
      signal: AbortSignal.timeout(timeoutMs),
    });

    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`libSQL request failed (HTTP ${res.status}): ${text.slice(0, 300)}`);
    }

    const payload = await res.json();
    return payload.results ?? [];
  }

  /** Turns one wire result into `{ rows, changes, lastInsertRowid }`. */
  function decodeResult(result) {
    const cols = result?.cols ?? [];
    const rows = (result?.rows ?? []).map((row) => {
      const obj = {};
      for (let i = 0; i < cols.length; i += 1) {
        obj[cols[i].name] = decodeValue(Array.isArray(row) ? row[i] : null);
      }
      return obj;
    });

    const rawId = result?.last_insert_rowid;
    return {
      rows,
      changes: Number(result?.affected_row_count ?? 0),
      lastInsertRowid:
        rawId === undefined || rawId === null ? 0 : Number(rawId),
    };
  }

  function assertOk(entry, index) {
    if (entry?.type === 'error') {
      const message = entry.error?.message ?? JSON.stringify(entry.error ?? entry);
      throw new Error(`libSQL statement #${index} failed: ${message}`);
    }
  }

  async function runRequests(requests) {
    if (!requests.length) return [];
    const results = await pipeline([...requests, { type: 'close' }]);
    const out = [];
    for (let i = 0; i < requests.length; i += 1) {
      const entry = results[i];
      assertOk(entry, i);
      const response = entry?.response ?? {};
      if (response.type === 'execute') out.push(decodeResult(response.result));
      else out.push({ rows: [], changes: 0, lastInsertRowid: 0 });
    }
    return out;
  }

  return {
    name: 'libsql',

    async get(sql, ...args) {
      const [result] = await runRequests([toExecuteRequest(sql, args)]);
      return result.rows[0];
    },

    async all(sql, ...args) {
      const [result] = await runRequests([toExecuteRequest(sql, args)]);
      return result.rows;
    },

    async run(sql, ...args) {
      const [result] = await runRequests([toExecuteRequest(sql, args)]);
      return { changes: result.changes, lastInsertRowid: result.lastInsertRowid };
    },

    async exec(sql) {
      // The wire protocol runs one statement per request.
      const statements = String(sql)
        .split(/;\s*(?:\n|$)/)
        .map((s) => s.trim())
        .filter((s) => s.length);
      await runRequests(statements.map((s) => toExecuteRequest(s, [])));
    },

    /** Runs many statements in a single HTTP round-trip. */
    async batch(items) {
      if (!items.length) return [];
      const requests = items.map((item) => toExecuteRequest(item.sql, item.args ?? []));
      return runRequests(requests);
    },

    close() {
      /* nothing to close — every request is its own connection */
    },
  };
}
