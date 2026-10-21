import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import { config } from './config.js';
import { q } from './db/index.js';

/**
 * Media storage.
 *
 * Two backends:
 *   mediaDriver=file → the bytes live in `uploads/` (normal VPS)
 *   mediaDriver=db   → the bytes live in `media_blobs` as chunks, so they
 *                      survive a host whose disk is wiped on every deploy
 *                      (Render / any ephemeral PaaS filesystem).
 */

const EXT_BY_MIME = {
  'image/png': '.png',
  'image/jpeg': '.jpg',
  'image/webp': '.webp',
};

function extensionFor(mime) {
  return EXT_BY_MIME[String(mime || '').toLowerCase()] || '.bin';
}

/**
 * Stores a media file and creates its `media` row.
 * Returns `{ id, filename, mime, size, at }`.
 */
export async function saveMedia({ deviceId, kind, buffer, mime, meta, at }) {
  const filename = `${Date.now()}-${crypto.randomBytes(8).toString('hex')}${extensionFor(mime)}`;
  const size = buffer?.length ?? 0;

  const { lastInsertRowid } = await q.run(
    'INSERT INTO media (device_id, kind, filename, mime, size, meta, at) VALUES (?, ?, ?, ?, ?, ?, ?)',
    deviceId,
    kind,
    filename,
    mime,
    size,
    meta,
    at,
  );

  if (config.mediaDriver === 'db') {
    const chunkSize = Math.max(16 * 1024, config.mediaChunkBytes);
    const chunks = [];
    for (let offset = 0, seq = 0; offset < size; offset += chunkSize, seq += 1) {
      chunks.push({
        sql: 'INSERT INTO media_blobs (media_id, seq, data) VALUES (?, ?, ?)',
        args: [lastInsertRowid, seq, buffer.subarray(offset, offset + chunkSize)],
      });
    }
    if (chunks.length) {
      try {
        await q.batch(chunks);
      } catch (err) {
        // Never leave a half-written file behind.
        await q.run('DELETE FROM media_blobs WHERE media_id = ?', lastInsertRowid);
        await q.run('DELETE FROM media WHERE id = ?', lastInsertRowid);
        throw err;
      }
    }
  } else {
    await fs.writeFile(path.join(config.uploadDir, filename), buffer);
  }

  return { id: lastInsertRowid, filename, mime, size, at };
}

/**
 * Reads a media file back.
 * Returns `{ buffer, mime, filename }` or `null` when the row is gone.
 */
export async function readMedia(mediaId) {
  const item = await q.get('SELECT * FROM media WHERE id = ?', mediaId);
  if (!item) return null;

  if (config.mediaDriver === 'db') {
    const rows = await q.all(
      'SELECT seq, data FROM media_blobs WHERE media_id = ? ORDER BY seq',
      mediaId,
    );
    if (!rows.length) return null;
    const buffer = Buffer.concat(
      rows.map((row) => Buffer.from(row.data instanceof Uint8Array ? row.data : new Uint8Array(row.data ?? []))),
    );
    return { buffer, mime: item.mime, filename: item.filename };
  }

  // filename is server-generated, but resolve+prefix check keeps a corrupted DB
  // row from escaping the upload directory.
  const filePath = path.resolve(config.uploadDir, path.basename(item.filename));
  if (!filePath.startsWith(path.resolve(config.uploadDir))) return null;
  try {
    const buffer = await fs.readFile(filePath);
    return { buffer, mime: item.mime, filename: item.filename };
  } catch {
    return null;
  }
}
