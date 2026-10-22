/* ============================================================
   Guardian — dashboard logic (vanilla JS, no build step)
   ============================================================ */
'use strict';

const state = {
  token: localStorage.getItem('pg_token') || null,
  user: null,
  devices: [],
  currentDeviceId: null,
  activeTab: 'map',
  map: null,
  marker: null,
  trail: null,
  ws: null,
  refreshTimer: null,
  enrollTimer: null,
  live: { source: null, frames: 0, startedAt: 0, pending: false, rateTimer: null },
};

/* ------------------------------------------------------------- utilities */

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

const esc = (value) =>
  String(value ?? '').replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c],
  );

const dateFmt = new Intl.DateTimeFormat('ar', {
  dateStyle: 'medium',
  timeStyle: 'short',
  numberingSystem: 'latn',
});

const timeFmt = new Intl.DateTimeFormat('ar', {
  timeStyle: 'short',
  numberingSystem: 'latn',
});

function fmtDate(value) {
  if (!value) return '—';
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? '—' : dateFmt.format(d);
}

function fmtTime(value) {
  if (!value) return '—';
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? '—' : timeFmt.format(d);
}

/** Human-readable duration from milliseconds. */
function fmtDuration(ms) {
  const totalMin = Math.floor((ms || 0) / 60000);
  if (totalMin < 60) return `${totalMin} د`;
  const h = Math.floor(totalMin / 60);
  const m = totalMin % 60;
  if (h < 24) return m ? `${h} س ${m} د` : `${h} س`;
  const d = Math.floor(h / 24);
  return `${d} ي ${h % 24} س`;
}

function fmtDurationSec(sec) {
  const m = Math.floor((sec || 0) / 60);
  const s = (sec || 0) % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}

function relTime(value) {
  if (!value) return '—';
  const diff = Date.now() - new Date(value).getTime();
  if (Number.isNaN(diff)) return '—';
  if (diff < 60_000) return 'الآن';
  if (diff < 3_600_000) return `قبل ${Math.floor(diff / 60_000)} دقيقة`;
  if (diff < 86_400_000) return `قبل ${Math.floor(diff / 3_600_000)} ساعة`;
  return fmtDate(value);
}

function toast(message, kind = '') {
  const el = $('#toast');
  el.textContent = message;
  el.className = `toast ${kind}`;
  el.hidden = false;
  clearTimeout(el._t);
  el._t = setTimeout(() => {
    el.hidden = true;
  }, 4000);
}

/* ------------------------------------------------------------------ api */

async function api(path, { method = 'GET', body, raw = false } = {}) {
  const headers = {};
  if (state.token) headers.Authorization = `Bearer ${state.token}`;
  let payload;
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    payload = JSON.stringify(body);
  }
  const res = await fetch(path, { method, headers, body: payload });
  if (res.status === 401) {
    setAuthMode('login');
    throw new Error('انتهت الجلسة، يرجى تسجيل الدخول من جديد');
  }
  if (raw) return res;
  const json = await res.json().catch(() => ({}));
  if (!res.ok || json.ok === false) {
    throw new Error(json?.error?.message || `فشل الطلب (${res.status})`);
  }
  return json.data ?? json;
}

/* ----------------------------------------------------------------- auth */

let authMode = 'login';

function setAuthMode(mode) {
  authMode = mode;
  $$('.auth-tab').forEach((t) => t.classList.toggle('is-active', t.dataset.mode === mode));
  $('#name-field').hidden = mode !== 'register';
  $('#auth-submit').textContent = mode === 'register' ? 'إنشاء الحساب' : 'دخول';
  $('#auth-error').hidden = true;
}

$$('.auth-tab').forEach((tab) => {
  tab.addEventListener('click', () => setAuthMode(tab.dataset.mode));
});

$('#auth-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const form = new FormData(event.currentTarget);
  const email = String(form.get('email') || '').trim();
  const password = String(form.get('password') || '');
  const name = String(form.get('name') || '').trim();

  const errorBox = $('#auth-error');
  errorBox.hidden = true;
  $('#auth-submit').disabled = true;

  try {
    const path = authMode === 'register' ? '/api/auth/register' : '/api/auth/login';
    const data = await api(path, {
      method: 'POST',
      body: authMode === 'register' ? { email, password, name } : { email, password },
    });
    state.token = data.token;
    state.user = data.user;
    localStorage.setItem('pg_token', data.token);
    await enterApp();
  } catch (err) {
    errorBox.textContent = err.message;
    errorBox.hidden = false;
  } finally {
    $('#auth-submit').disabled = false;
  }
});

$('#logout-btn').addEventListener('click', async () => {
  try {
    await api('/api/auth/logout', { method: 'POST' });
  } catch {
    /* ignore — we clear locally anyway */
  }
  state.token = null;
  state.user = null;
  localStorage.removeItem('pg_token');
  location.reload();
});

async function enterApp() {
  if (!state.token) return;
  try {
    const me = await api('/api/auth/me');
    state.user = me.user;
  } catch {
    return;
  }
  $('#auth-view').hidden = true;
  $('#app-view').hidden = false;
  $('#who').textContent = state.user?.name || state.user?.email || '';
  await loadDevices();
  connectRealtime();
  startAutoRefresh();
}

/* -------------------------------------------------------------- devices */

async function loadDevices() {
  try {
    state.devices = (await api('/api/devices')) || [];
  } catch (err) {
    toast(err.message, 'err');
    return;
  }
  renderDeviceList();

  if (state.devices.length === 0) {
    $('#current-device-name').textContent = 'لا توجد أجهزة بعد';
    $('#current-device-meta').textContent = 'اضغط «+ جهاز» لإنشاء كود ربط.';
    return;
  }

  if (!state.currentDeviceId || !state.devices.some((d) => d.id === state.currentDeviceId)) {
    selectDevice(state.devices[0].id);
  } else {
    renderDeviceHead();
  }
}

function deviceStatus(device) {
  if (device.status === 'revoked') return { cls: 'is-offline', text: 'ملغى' };
  if (!device.lastSeen) return { cls: '', text: 'لم يتصل' };
  const age = Date.now() - new Date(device.lastSeen).getTime();
  return age < 120_000
    ? { cls: 'is-online', text: 'متصل' }
    : { cls: 'is-offline', text: 'غير متصل' };
}

function renderDeviceList() {
  const container = $('#device-list');
  if (!state.devices.length) {
    container.innerHTML = '<div class="empty"><span class="big">📱</span>لا توجد أجهزة مربوطة</div>';
    return;
  }
  container.innerHTML = state.devices
    .map((d) => {
      const st = deviceStatus(d);
      const active = d.id === state.currentDeviceId ? ' is-active' : '';
      const battery = d.battery !== null && d.battery !== undefined ? `🔋 ${d.battery}%` : '';
      return `
        <div class="device-card${active}${st.cls === 'is-online' ? ' is-online' : st.cls === 'is-offline' ? ' is-offline' : ''}" data-id="${d.id}">
          <div class="row">
            <span class="name">${esc(d.name)}</span>
            <span class="status"><i></i>${st.text}</span>
          </div>
          <div class="row">
            <span class="sub">${esc(d.model || d.manufacturer || '—')}</span>
            <span class="batt">${battery}</span>
          </div>
        </div>`;
    })
    .join('');

  $$('.device-card', container).forEach((card) => {
    card.addEventListener('click', () => selectDevice(Number(card.dataset.id)));
  });
}

function currentDevice() {
  return state.devices.find((d) => d.id === state.currentDeviceId) || null;
}

function renderDeviceHead() {
  const d = currentDevice();
  if (!d) return;
  $('#current-device-name').textContent = d.name || 'جهاز';
  const bits = [d.manufacturer, d.model, d.androidVersion ? `أندرويد ${d.androidVersion}` : null]
    .filter(Boolean)
    .join(' · ');
  $('#current-device-meta').textContent = `${bits || '—'} — آخر اتصال: ${relTime(d.lastSeen)}`;
}

function selectDevice(id) {
  if (state.live.source) stopLive(true);
  state.currentDeviceId = id;
  renderDeviceList();
  renderDeviceHead();
  refreshActiveTab(true);
  loadLiveStats();
}

/* ------------------------------------------------------------------ map */

function ensureMap() {
  if (state.map) return state.map;
  state.map = L.map('map', { zoomControl: true, attributionControl: true }).setView([24.7136, 46.6753], 11);
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; OpenStreetMap contributors',
  }).addTo(state.map);
  return state.map;
}

function updateMarker(device) {
  const loc = device.location;
  if (!loc) return;
  const map = ensureMap();

  if (!state.marker) {
    state.marker = L.marker([loc.lat, loc.lng]).addTo(map);
  } else {
    state.marker.setLatLng([loc.lat, loc.lng]);
  }
  state.marker.bindPopup(popupHtml(device));

  if ($('#follow-toggle').checked) {
    map.panTo([loc.lat, loc.lng]);
  }
}

function popupHtml(d) {
  const loc = d.location || {};
  return `
    <div class="map-popup">
      <h4>${esc(d.name)}</h4>
      <div class="row"><span>الإحداثيات</span><span dir="ltr">${Number(loc.lat ?? 0).toFixed(5)}, ${Number(loc.lng ?? 0).toFixed(5)}</span></div>
      <div class="row"><span>الدقة</span><span>${loc.accuracy !== null && loc.accuracy !== undefined ? `${loc.accuracy} م` : '—'}</span></div>
      <div class="row"><span>الوقت</span><span>${fmtTime(loc.at)}</span></div>
    </div>`;
}

async function loadTrail() {
  if (!$('#trail-toggle').checked) {
    if (state.trail) {
      state.map?.removeLayer(state.trail);
      state.trail = null;
    }
    return;
  }
  const hours = Number($('#trail-range').value || 6);
  const from = new Date(Date.now() - hours * 3600_000).toISOString();
  try {
    const points = await api(
      `/api/devices/${state.currentDeviceId}/locations?from=${encodeURIComponent(from)}&limit=2000`,
    );
    const map = ensureMap();
    if (state.trail) map.removeLayer(state.trail);
    if (!points || points.length < 2) {
      state.trail = null;
      return;
    }
    state.trail = L.polyline(
      points.map((p) => [p.lat, p.lng]),
      { color: '#4f8cff', weight: 3, opacity: 0.75 },
    ).addTo(map);
  } catch (err) {
    toast(err.message, 'err');
  }
}

/* ---------------------------------------------------------- live status */

async function loadLiveStats() {
  const d = currentDevice();
  if (!d) return;
  const loc = d.location;
  const battery = d.battery ?? loc?.battery ?? null;

  $('#live-stats').innerHTML = `
    <div class="stat accent">
      <div class="label">الموقع الحالي</div>
      <div class="value">${loc ? 'مُحدَّد' : 'غير متاح'}</div>
      <div class="hint">${loc ? fmtTime(loc.at) : 'لم تصل بيانات موقع بعد'}</div>
    </div>
    <div class="stat">
      <div class="label">الوقت الحالي</div>
      <div class="value">${fmtTime(new Date().toISOString())}</div>
      <div class="hint">${d.wifi ? `📶 ${esc(d.wifi)}` : '—'}</div>
    </div>
    <div class="stat ${battery !== null && battery < 20 ? 'danger' : battery !== null && battery < 40 ? 'warn' : 'ok'}">
      <div class="label">البطارية</div>
      <div class="value">${battery !== null ? `${battery}%` : '—'}</div>
      <div class="hint">${d.charging ? 'شاحن متصل' : 'ليس شاحناً'}</div>
    </div>
    <div class="stat">
      <div class="label">آخر اتصال</div>
      <div class="value" style="font-size:1rem">${relTime(d.lastSeen)}</div>
      <div class="hint">${d.status === 'active' ? 'الجهاز مفعّل' : esc(d.status)}</div>
    </div>`;
}

/* ------------------------------------------------------------ realtime */

function connectRealtime() {
  if (state.ws) state.ws.close();
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const ws = new WebSocket(`${proto}://${location.host}/ws?token=${encodeURIComponent(state.token)}`);
  ws.binaryType = 'arraybuffer';
  state.ws = ws;

  ws.addEventListener('open', () => {
    setConn('is-live', 'متصل لحظياً');
    // The server drops device sockets on restart; the device reconnects by
    // itself, so resuming the view only needs another start request.
    if (state.live.source) requestLive(state.live.source);
  });
  ws.addEventListener('close', () => {
    setConn('is-down', 'انقطع الاتصال');
    if (state.live.source) setLiveBadge('انقطع الاتصال', 'err');
    // Reconnect with a small backoff so a server restart reconnects on its own.
    setTimeout(() => {
      if (state.token) connectRealtime();
    }, 5000);
  });
  ws.addEventListener('error', () => setConn('is-down', 'خطأ اتصال'));

  ws.addEventListener('message', (event) => {
    // Live frames arrive as binary JPEGs; everything else is JSON.
    if (event.data instanceof ArrayBuffer) {
      handleLiveFrame(event.data);
      return;
    }

    let msg;
    try {
      msg = JSON.parse(event.data);
    } catch {
      return;
    }
    if (msg.deviceId && msg.deviceId !== state.currentDeviceId) return;

    if (msg.type === 'live.state') {
      handleLiveState(msg);
      return;
    }

    if (msg.type === 'device.location' || msg.type === 'device.heartbeat') {
      loadDevices().then(() => {
        updateMarker(currentDevice());
        loadLiveStats();
      });
    }
  });
}

function setConn(cls, text) {
  const el = $('#conn-status');
  el.className = `conn ${cls}`;
  $('#conn-text').textContent = text;
}

/* ------------------------------------------------------------ live view */

const LIVE_ERRORS = {
  NO_CONSENT:
    'لم يمنح الجهاز إذن تصوير الشاشة بعد. افتح تطبيق Guardian على الجهاز وامنح إذن التسجيل.',
  DEVICE_OFFLINE: 'الجهاز غير متصل الآن. تأكد من اتصاله بالإنترنت.',
  NO_CAMERA_PERMISSION: 'إذن الكاميرا غير ممنوح على الجهاز.',
  CAMERA_ERROR: 'تعذّر تشغيل الكاميرا (قد تكون مستخدمة من تطبيق آخر).',
  CAMERA_DISCONNECTED: 'انقطعت الكاميرا عن الجهاز.',
  CAMERA_OPEN_FAILED: 'تعذّر فتح كاميرا الجهاز.',
  SCREEN_UNAVAILABLE: 'تعذّر إنشاء قناة تصوير الشاشة على الجهاز.',
  FORBIDDEN: 'هذا الجهاز ليس مسجّلاً في حسابك.',
  BAD_DEVICE: 'طلب غير صالح.',
  INTERNAL: 'خطأ داخلي في الجهاز.',
};

function setLiveBadge(text, kind = '') {
  const el = $('#live-badge');
  if (!el) return;
  el.textContent = text;
  el.className = `live-badge${kind ? ` is-${kind}` : ''}`;
}

function startLive(source) {
  if (!state.currentDeviceId) return;
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) {
    toast('قناة التحكم غير متصلة، انتظر لحظة ثم أعد المحاولة', 'err');
    return;
  }
  state.live.source = source;
  state.live.frames = 0;
  state.live.startedAt = Date.now();
  setLiveBadge('جارٍ الطلب…', 'warn');
  $('#live-stop-btn').disabled = false;
  $('#live-meta').textContent =
    source === 'camera' ? 'جارٍ تشغيل كاميرا الجهاز…' : 'جارٍ تشغيل بث شاشة الجهاز…';
  requestLive(source);
}

function requestLive(source) {
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return;
  state.ws.send(
    JSON.stringify({ type: 'live.start', deviceId: state.currentDeviceId, source }),
  );
}

function stopLive(silent = false) {
  if (state.ws && state.ws.readyState === WebSocket.OPEN && state.currentDeviceId) {
    state.ws.send(JSON.stringify({ type: 'live.stop', deviceId: state.currentDeviceId }));
  }
  if (silent) {
    stopRateTimer();
    state.live.source = null;
    state.live.frames = 0;
    $('#live-stage').classList.remove('is-live');
    $('#live-stop-btn').disabled = true;
    setLiveBadge('غير نشط', '');
  } else {
    resetLiveStage();
  }
}

function resetLiveStage() {
  stopRateTimer();
  state.live.source = null;
  state.live.frames = 0;
  state.live.pending = false;
  $('#live-stage').classList.remove('is-live');
  $('#live-stop-btn').disabled = true;
  setLiveBadge('غير نشط', '');
  $('#live-meta').textContent = 'لا بث حالي.';
  const canvas = $('#live-canvas');
  const ctx = canvas.getContext('2d');
  if (ctx) ctx.clearRect(0, 0, canvas.width, canvas.height);
}

function handleLiveState(msg) {
  if (msg.state === 'started') {
    setLiveBadge('مباشر', 'live');
    $('#live-stage').classList.add('is-live');
    $('#live-stop-btn').disabled = false;
    startRateTimer();
    return;
  }
  if (msg.state === 'error') {
    const text = LIVE_ERRORS[msg.error] || `تعذّر بدء البث (${msg.error || 'خطأ'}).`;
    resetLiveStage();
    setLiveBadge('تعذّر البدء', 'err');
    $('#live-meta').textContent = text;
    toast(text, 'err');
    return;
  }
  if (msg.state === 'stopped') {
    resetLiveStage();
  }
}

/**
 * One binary frame: [ver:1][source:1][flags:2][deviceId:4][seq:4][length:4] + JPEG.
 * Frames are decoded only while the live tab is visible, and never queued.
 */
function handleLiveFrame(buffer) {
  if (buffer.byteLength <= 16) return;
  const view = new DataView(buffer);
  if (view.getUint8(0) !== 1) return;
  const source = view.getUint8(1);
  if (source !== 1 && source !== 2) return;
  if (view.getUint32(4) !== state.currentDeviceId) return;
  if (!state.live.source || state.activeTab !== 'live') return;
  const length = view.getUint32(12);
  if (!length || 16 + length > buffer.byteLength) return;
  if (state.live.pending) return;

  state.live.pending = true;
  state.live.frames += 1;
  drawLiveFrame(new Blob([new Uint8Array(buffer, 16, length)], { type: 'image/jpeg' }));
}

async function drawLiveFrame(blob) {
  const canvas = $('#live-canvas');
  try {
    let width;
    let height;
    let paint;
    if (typeof createImageBitmap === 'function') {
      const bitmap = await createImageBitmap(blob);
      width = bitmap.width;
      height = bitmap.height;
      paint = () => {
        canvas.getContext('2d').drawImage(bitmap, 0, 0);
        if (bitmap.close) bitmap.close();
      };
    } else {
      const url = URL.createObjectURL(blob);
      const image = await new Promise((resolve, reject) => {
        const element = new Image();
        element.onload = () => resolve(element);
        element.onerror = reject;
        element.src = url;
      });
      width = image.naturalWidth;
      height = image.naturalHeight;
      paint = () => {
        canvas.getContext('2d').drawImage(image, 0, 0);
        URL.revokeObjectURL(url);
      };
    }
    if (canvas.width !== width) canvas.width = width;
    if (canvas.height !== height) canvas.height = height;
    paint();
    $('#live-stage').classList.add('is-live');
  } catch {
    /* a broken frame is skipped; the next one replaces it */
  } finally {
    state.live.pending = false;
  }
}

function startRateTimer() {
  stopRateTimer();
  state.live.rateTimer = setInterval(() => {
    if (!state.live.source) {
      stopRateTimer();
      return;
    }
    const seconds = Math.max(1, (Date.now() - state.live.startedAt) / 1000);
    const fps = (state.live.frames / seconds).toFixed(1);
    const label = state.live.source === 'camera' ? 'الكاميرا' : 'الشاشة';
    $('#live-meta').textContent = `بث ${label} — ${state.live.frames} إطاراً — ${fps} إطار/ث.`;
  }, 1000);
}

function stopRateTimer() {
  if (state.live.rateTimer) {
    clearInterval(state.live.rateTimer);
    state.live.rateTimer = null;
  }
}

$('#live-screen-btn').addEventListener('click', () => startLive('screen'));
$('#live-camera-btn').addEventListener('click', () => startLive('camera'));
$('#live-stop-btn').addEventListener('click', () => stopLive());

/* --------------------------------------------------------------- tabs */

$$('.tab').forEach((tab) => {
  tab.addEventListener('click', () => {
    $$('.tab').forEach((t) => t.classList.toggle('is-active', t === tab));
    state.activeTab = tab.dataset.tab;
    $$('.panel').forEach((p) => p.classList.toggle('is-active', p.dataset.panel === state.activeTab));
    refreshActiveTab();
  });
});

function rangeFrom(selectId, fallback = 7) {
  const days = Number($(`#${selectId}`).value || fallback);
  return new Date(Date.now() - days * 86400_000).toISOString();
}

async function refreshActiveTab(force = false) {
  if (!state.currentDeviceId) return;
  const tab = state.activeTab;
  const id = state.currentDeviceId;

  try {
    switch (tab) {
      case 'map': {
        ensureMap();
        updateMarker(currentDevice());
        await loadTrail();
        break;
      }
      case 'stats':
        await loadStats();
        break;
      case 'usage':
        await loadUsage();
        break;
      case 'calls':
        await loadCalls();
        break;
      case 'sms':
        await loadSms();
        break;
      case 'media':
        await loadMedia();
        break;
      case 'timeline':
        await loadTimeline();
        break;
      case 'apps':
        await loadApps();
        break;
      default:
        break;
    }
  } catch (err) {
    if (!force) toast(err.message, 'err');
  }
}

/* ---------------------------------------------------------- tab loaders */

async function loadStats() {
  const stats = await api(`/api/devices/${state.currentDeviceId}/stats`);
  $('#stats-cards').innerHTML = `
    <div class="stat accent">
      <div class="label">وقت الشاشة اليوم</div>
      <div class="value">${fmtDuration(stats.screenTimeTodayMs)}</div>
      <div class="hint">الأسبوع: ${fmtDuration(stats.screenTimeWeekMs)}</div>
    </div>
    <div class="stat">
      <div class="label">المكالمات اليوم</div>
      <div class="value">${stats.callsToday}</div>
      <div class="hint">الأسبوع: ${stats.callsWeek}</div>
    </div>
    <div class="stat">
      <div class="label">نقاط الموقع اليوم</div>
      <div class="value">${stats.locationsToday}</div>
      <div class="hint">عدد مرات تسجيل الموقع</div>
    </div>`;

  const list = $('#top-apps');
  const apps = stats.topApps || [];
  if (!apps.length) {
    list.innerHTML = '<div class="empty"><span class="big">📊</span>لا توجد بيانات استخدام بعد</div>';
    return;
  }
  const max = Math.max(...apps.map((a) => a.total_ms), 1);
  list.innerHTML = apps
    .map(
      (a) => `
      <div class="bar-item">
        <div class="name">${esc(a.label || a.package)}<small>${esc(a.package)}</small></div>
        <div class="bar-track"><div class="bar-fill" style="width:${(a.total_ms / max) * 100}%"></div></div>
        <div class="val">${fmtDuration(a.total_ms)}</div>
      </div>`,
    )
    .join('');
}

async function loadUsage() {
  const from = rangeFrom('usage-range');
  const rows = await api(`/api/devices/${state.currentDeviceId}/usage?from=${encodeURIComponent(from)}&limit=200`);
  const list = $('#usage-list');
  if (!rows.length) {
    list.innerHTML = '<div class="empty"><span class="big">⏱️</span>لا يوجد استخدام مسجّل في هذه الفترة</div>';
    return;
  }
  const max = Math.max(...rows.map((r) => r.total_ms), 1);
  list.innerHTML = rows
    .map(
      (r) => `
      <div class="bar-item">
        <div class="name">${esc(r.label || r.package)}<small>${esc(r.package)}</small></div>
        <div class="bar-track"><div class="bar-fill" style="width:${(r.total_ms / max) * 100}%"></div></div>
        <div class="val">${fmtDuration(r.total_ms)}</div>
      </div>`,
    )
    .join('');
}

async function loadCalls() {
  const from = rangeFrom('calls-range');
  const rows = await api(`/api/devices/${state.currentDeviceId}/calls?from=${encodeURIComponent(from)}&limit=300`);
  const list = $('#calls-list');
  if (!rows.length) {
    list.innerHTML = '<div class="empty"><span class="big">📞</span>لا توجد مكالمات في هذه الفترة</div>';
    return;
  }
  const typeLabel = { incoming: 'واردة', outgoing: 'صادرة', missed: 'فائتة', rejected: 'مرفوضة' };
  const icon = { incoming: '📥', outgoing: '📤', missed: '📵', rejected: '🚫' };
  list.innerHTML = rows
    .map((r) => {
      const cls = r.call_type === 'incoming' ? 'in' : r.call_type === 'missed' ? 'miss' : 'out';
      return `
      <div class="row-item">
        <div class="icon">${icon[r.call_type] || '📞'}</div>
        <div class="body">
          <div class="title">${esc(r.contact_name || r.number || 'رقم غير معروف')}</div>
          <div class="sub" dir="ltr" style="text-align:right">${esc(r.number || '')}</div>
        </div>
        <div>
          <span class="tag ${cls}">${typeLabel[r.call_type] || r.call_type}</span>
          <div class="when">${r.duration_sec ? fmtDurationSec(r.duration_sec) : '—'} · ${relTime(r.at)}</div>
        </div>
      </div>`;
    })
    .join('');
}

async function loadSms() {
  const from = rangeFrom('sms-range');
  const rows = await api(`/api/devices/${state.currentDeviceId}/sms?from=${encodeURIComponent(from)}&limit=300`);
  const list = $('#sms-list');
  if (!rows.length) {
    list.innerHTML = '<div class="empty"><span class="big">💬</span>لا توجد رسائل في هذه الفترة</div>';
    return;
  }
  const typeLabel = { inbox: 'واردة', sent: 'صادرة', draft: 'مسودة', outbox: 'صادرة' };
  list.innerHTML = rows
    .map(
      (r) => `
      <div class="row-item">
        <div class="icon">${r.msg_type === 'sent' ? '📤' : '📥'}</div>
        <div class="body">
          <div class="title">${esc(r.address || 'غير معروف')}</div>
          <div class="sub">${esc(r.body || '')}</div>
        </div>
        <div>
          <span class="tag ${r.msg_type === 'sent' ? 'out' : 'in'}">${typeLabel[r.msg_type] || r.msg_type}</span>
          <div class="when">${relTime(r.at)}</div>
        </div>
      </div>`,
    )
    .join('');
}

async function loadMedia() {
  const from = rangeFrom('media-range');
  const rows = await api(`/api/devices/${state.currentDeviceId}/media?from=${encodeURIComponent(from)}&limit=100`);
  const grid = $('#media-grid');
  if (!rows.length) {
    grid.innerHTML = '<div class="empty"><span class="big">🖼️</span>لا توجد لقطات شاشة أو صور بعد</div>';
    return;
  }
  grid.innerHTML = rows
    .map(
      (m) => `
      <div class="media-item" data-url="${esc(m.url)}">
        <img loading="lazy" src="${esc(m.url)}" alt="${esc(m.kind)}" />
        <div class="cap">
          <span>${m.kind === 'camera' ? '📷 صورة' : '🖥️ لقطة شاشة'}</span>
          <span>${relTime(m.at)}</span>
        </div>
      </div>`,
    )
    .join('');

  $$('.media-item', grid).forEach((item) => {
    item.addEventListener('click', () => openLightbox(item.dataset.url));
  });
}

function openLightbox(url) {
  const overlay = document.createElement('div');
  overlay.className = 'modal';
  overlay.innerHTML = `<div class="modal-card" style="max-width:min(92vw,900px)">
      <img src="${esc(url)}" style="max-width:100%; border-radius:10px" />
      <button class="btn btn-primary btn-block" style="margin-top:.9rem">إغلاق</button>
    </div>`;
  overlay.addEventListener('click', () => overlay.remove());
  document.body.appendChild(overlay);
}

async function loadTimeline() {
  const from = rangeFrom('timeline-range');
  const rows = await api(`/api/devices/${state.currentDeviceId}/timeline?from=${encodeURIComponent(from)}&limit=300`);
  const list = $('#timeline-list');
  if (!rows.length) {
    list.innerHTML = '<div class="empty"><span class="big">🕓</span>السجل فارغ في هذه الفترة</div>';
    return;
  }
  const icons = {
    app_usage: '📱', call: '📞', sms: '💬',
    app_installed: '⬇️', app_removed: '🗑️',
    browser_history: '🌐', notification: '🔔', custom: '•',
  };
  list.innerHTML = rows
    .map(
      (r) => `
      <div class="row-item">
        <div class="icon">${icons[r.kind] || '•'}</div>
        <div class="body">
          <div class="title">${esc(r.title || r.kind)}</div>
          ${r.subtitle ? `<div class="sub">${esc(r.subtitle)}</div>` : ''}
        </div>
        <div class="when">${fmtDate(r.at)}</div>
      </div>`,
    )
    .join('');
}

async function loadApps() {
  const rows = await api(`/api/devices/${state.currentDeviceId}/apps`);
  const hideSystem = $('#hide-system-apps').checked;
  const filtered = hideSystem ? rows.filter((r) => !r.is_system) : rows;
  const list = $('#apps-list');
  if (!filtered.length) {
    list.innerHTML = '<div class="empty"><span class="big">📦</span>لم تصل قائمة التطبيقات بعد</div>';
    return;
  }
  list.innerHTML = filtered
    .map(
      (r) => `
      <div class="row-item">
        <div class="icon">📦</div>
        <div class="body">
          <div class="title">${esc(r.label || r.package)}</div>
          <div class="sub" dir="ltr" style="text-align:right">${esc(r.package)}</div>
        </div>
        <div>
          ${r.is_system ? '<span class="tag">نظام</span>' : ''}
          <div class="when">${r.version ? esc(r.version) : ''}</div>
        </div>
      </div>`,
    )
    .join('');
}

/* ------------------------------------------------------- enroll modal */

async function openEnrollModal() {
  $('#enroll-modal').hidden = false;
  await refreshEnrollCode();
}

async function refreshEnrollCode() {
  try {
    const data = await api('/api/devices/enroll-code', { method: 'POST' });
    const code = data.code;
    $('#enroll-code').textContent = code.replace(/(\d{4})(?=\d)/g, '$1 ');
    const expires = new Date(data.expiresAt);
    const tick = () => {
      const left = Math.max(0, Math.round((expires.getTime() - Date.now()) / 1000));
      if (left === 0) {
        $('#enroll-expiry').textContent = 'انتهى صلاحية الكود';
        return;
      }
      $('#enroll-expiry').textContent = `ينتهي خلال ${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}`;
    };
    tick();
    clearInterval(state.enrollTimer);
    state.enrollTimer = setInterval(tick, 1000);
  } catch (err) {
    toast(err.message, 'err');
  }
}

$('#add-device-btn').addEventListener('click', openEnrollModal);
$('#enroll-refresh').addEventListener('click', refreshEnrollCode);
$('#enroll-close').addEventListener('click', () => {
  $('#enroll-modal').hidden = true;
  clearInterval(state.enrollTimer);
  loadDevices();
});

$('#rename-btn').addEventListener('click', async () => {
  const d = currentDevice();
  if (!d) return;
  const name = prompt('الاسم الجديد للجهاز:', d.name);
  if (!name) return;
  try {
    await api(`/api/devices/${d.id}`, { method: 'PATCH', body: { name } });
    toast('تم تغيير الاسم', 'ok');
    loadDevices();
  } catch (err) {
    toast(err.message, 'err');
  }
});

$('#revoke-btn').addEventListener('click', async () => {
  const d = currentDevice();
  if (!d) return;
  if (!confirm(`سيتم إلغاء ربط «${d.name}» وسيحتاج إلى كود جديد. متابعة؟`)) return;
  try {
    await api(`/api/devices/${d.id}`, { method: 'DELETE' });
    toast('تم إلغاء الربط', 'ok');
    state.currentDeviceId = null;
    loadDevices();
  } catch (err) {
    toast(err.message, 'err');
  }
});

/* --------------------------------------------------------- auto refresh */

function startAutoRefresh() {
  clearInterval(state.refreshTimer);
  state.refreshTimer = setInterval(async () => {
    if (!state.currentDeviceId) return;
    await loadDevices();
    if (state.activeTab === 'map') {
      updateMarker(currentDevice());
      loadTrail();
    }
    loadLiveStats();
  }, 30_000);
}

/* ------------------------------------------------------------- filters */

['usage-range', 'calls-range', 'sms-range', 'media-range', 'timeline-range'].forEach((id) => {
  const el = document.getElementById(id);
  if (el) el.addEventListener('change', () => refreshActiveTab());
});
$('#hide-system-apps')?.addEventListener('change', () => loadApps());
$('#follow-toggle')?.addEventListener('change', () => updateMarker(currentDevice()));
$('#trail-toggle')?.addEventListener('change', () => loadTrail());
$('#trail-range')?.addEventListener('change', () => loadTrail());

/* ----------------------------------------------------------------- boot */

setAuthMode('login');
if (state.token) {
  enterApp().catch(() => {
    localStorage.removeItem('pg_token');
    state.token = null;
  });
}
