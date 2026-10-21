import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const publicDir = path.resolve(here, '..', 'public');

const html = fs.readFileSync(path.join(publicDir, 'index.html'), 'utf8');
const js = fs.readFileSync(path.join(publicDir, 'app.js'), 'utf8');
const css = fs.readFileSync(path.join(publicDir, 'style.css'), 'utf8');

/** Every id="..." declared in the markup. */
const htmlIds = new Set([...html.matchAll(/\bid="([^"]+)"/g)].map((m) => m[1]));

/** Every $('#foo') / $("#foo") selector used by the dashboard script. */
const jsSelectors = new Set(
  [...js.matchAll(/\$\(\s*['"]#([A-Za-z0-9_-]+)['"]\s*\)/g)].map((m) => m[1]),
);

test('every id referenced by app.js exists in index.html', () => {
  const missing = [...jsSelectors].filter((id) => !htmlIds.has(id));
  assert.deepEqual(missing, [], `app.js references missing ids: ${missing.join(', ')}`);
});

test('every element id in index.html is unique', () => {
  const all = [...html.matchAll(/\bid="([^"]+)"/g)].map((m) => m[1]);
  const dupes = all.filter((id, i) => all.indexOf(id) !== i);
  assert.deepEqual([...new Set(dupes)], [], `duplicate ids: ${[...new Set(dupes)].join(', ')}`);
});

test('every tab button points at an existing panel', () => {
  const tabs = [...html.matchAll(/data-tab="([^"]+)"/g)].map((m) => m[1]);
  const panels = [...html.matchAll(/data-panel="([^"]+)"/g)].map((m) => m[1]);
  assert.ok(tabs.length > 0, 'no tabs found');
  for (const tab of tabs) {
    assert.ok(panels.includes(tab), `tab "${tab}" has no matching panel`);
  }
  // A panel without a tab would be unreachable.
  for (const panel of panels) {
    assert.ok(tabs.includes(panel), `panel "${panel}" has no matching tab`);
  }
});

test('the Leaflet vendor bundle is vendored locally', () => {
  assert.ok(
    fs.existsSync(path.join(publicDir, 'vendor', 'leaflet', 'leaflet.js')),
    'leaflet.js is missing — the dashboard must not depend on a CDN',
  );
  assert.ok(
    fs.existsSync(path.join(publicDir, 'vendor', 'leaflet', 'leaflet.css')),
    'leaflet.css is missing',
  );
  assert.ok(!/https?:\/\/unpkg\.com/.test(html), 'index.html still points at a CDN');
  assert.ok(!/https?:\/\/unpkg\.com/.test(js), 'app.js still points at a CDN');
});

test('stylesheet classes used by app.js exist in style.css', () => {
  // Only fully static class attributes count — template placeholders (`${...}`)
  // are resolved at runtime and cannot be checked statically.
  const used = new Set(
    [...js.matchAll(/class="([^"$<]*)"/g)]
      .flatMap((m) => m[1].split(/\s+/))
      .filter((c) => /^[a-z][a-z0-9_-]*$/.test(c)),
  );
  const defined = new Set([...css.matchAll(/\.([A-Za-z][A-Za-z0-9_-]*)/g)].map((m) => m[1]));
  const missing = [...used].filter((c) => !defined.has(c));
  assert.deepEqual(missing, [], `classes used in app.js but not styled: ${missing.join(', ')}`);
});

test('the dashboard declares RTL and Arabic', () => {
  assert.match(html, /dir="rtl"/);
  assert.match(html, /lang="ar"/);
});

test('no inline secrets are hard-coded in the dashboard', () => {
  assert.ok(!/JWT_SECRET\s*=\s*['"][^'"]+/.test(js), 'app.js must not contain a JWT secret');
  assert.ok(!/password\s*=\s*['"][^'"]{6,}/i.test(js), 'app.js must not contain a hard-coded password');
});
