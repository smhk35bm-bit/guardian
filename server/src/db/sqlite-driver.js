import { DatabaseSync } from 'node:sqlite';

/**
 * Local SQLite driver (node:sqlite).
 *
 * Used on a normal VPS and for local development. The API is asynchronous even
 * though the underlying calls are synchronous, so the rest of the server does
 * not care which driver is active.
 */
export function createSqliteDriver({ file }) {
  const db = new DatabaseSync(file);

  db.exec('PRAGMA journal_mode = WAL');
  db.exec('PRAGMA foreign_keys = ON');
  db.exec('PRAGMA busy_timeout = 5000');

  // Prepared statements are cached: node:sqlite recompiles on schema change otherwise.
  const cache = new Map();
  function stmt(sql) {
    let s = cache.get(sql);
    if (!s) {
      s = db.prepare(sql);
      cache.set(sql, s);
    }
    return s;
  }

  // node:sqlite has no portable "does this statement return rows?" check, so
  // the statement text decides. The server only ever issues SELECT / INSERT /
  // UPDATE / DELETE.
  function returnsRows(sql) {
    return /^\s*(select|with|pragma|explain)\b/i.test(sql);
  }

  return {
    name: 'sqlite',

    async get(sql, ...args) {
      return stmt(sql).get(...args) ?? undefined;
    },

    async all(sql, ...args) {
      return stmt(sql).all(...args);
    },

    async run(sql, ...args) {
      const r = stmt(sql).run(...args);
      return { changes: Number(r.changes), lastInsertRowid: Number(r.lastInsertRowid) };
    },

    async exec(sql) {
      db.exec(sql);
    },

    /**
     * Runs several statements in one transaction. `items` is
     * `[{ sql, args }]`. Returns one result per statement.
     */
    async batch(items) {
      if (!items.length) return [];
      const out = [];
      db.exec('BEGIN');
      try {
        for (const item of items) {
          const args = item.args ?? [];
          if (returnsRows(item.sql)) {
            out.push({ rows: stmt(item.sql).all(...args), changes: 0, lastInsertRowid: 0 });
          } else {
            const r = stmt(item.sql).run(...args);
            out.push({
              rows: [],
              changes: Number(r.changes),
              lastInsertRowid: Number(r.lastInsertRowid),
            });
          }
        }
        db.exec('COMMIT');
      } catch (err) {
        try {
          db.exec('ROLLBACK');
        } catch {
          /* the transaction was already aborted */
        }
        throw err;
      }
      return out;
    },

    close() {
      db.close();
    },
  };
}
