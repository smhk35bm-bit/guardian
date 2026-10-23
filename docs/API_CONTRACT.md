# عقد API — Guardian Parental Control

> العقد المرجعي الذي يجب أن يطابقه كل من **السيرفر** (`server/`) و**تطبيق الأندرويد** (`android/`).
> أي انحراف عن هذا العقد يُعتبر خطأ. آخر تحديث: v3 (صوت البث، جهات الاتصال، المواقع الآمنة، التنبيهات، رنين الجهاز).

## 1. عام

- Base URL: `https://<vps-host>/api`
- كل الاستجابات JSON عدا الملفات (multipart / binary).
- ترميز: UTF-8.
- Compression: يقبل `gzip` على الردود النصية.

### 1.1 نموذج الأخطاء

كل خطأ يرجع بنفس الشكل:

```json
{ "ok": false, "error": { "code": "INVALID_TOKEN", "message": "Token غير صالح" } }
```

| code | HTTP | المعنى |
|---|---|---|
| `BAD_REQUEST` | 400 | مدخلات ناقصة/غير صالحة |
| `INVALID_TOKEN` | 401 | التوكن مفقود/منتهي/غير صالح |
| `FORBIDDEN` | 403 | الجهاز غير مالك للمورد |
| `NOT_FOUND` | 404 | غير موجود |
| `CODE_INVALID` | 400 | كود التسجيل غير صحيح أو منتهي |
| `CODE_USED` | 409 | الكود مستخدم مرة واحدة |
| `RATE_LIMITED` | 429 | تجاوز حد الطلبات |
| `INTERNAL` | 500 | خطأ داخلي |

### 1.2 المصادقة

ثلاثة أنماط، يمرر كل منها عبر `Authorization: Bearer <token>`:

| الرمز | الاستخدام | المصدر |
|---|---|---|
| **Parent token** | لوحة تحكم الأب | تسجيل دخول `/api/auth/login` → JWT (HS256) صالح 30 يوماً، يُرسل أيضاً كـ cookie `pg_session` (httpOnly) |
| **Device token** | تطبيق الأندرويد | يُمنح مرة واحدة عند التسجيل، JWT صالح 1 سنة، يُخزَّن في `SharedPreferences` |

**ترويسات الـ Parent token:**
- `Authorization: Bearer <jwt>` (للـ API)
- cookie `pg_session=<jwt>` (للـ dashboard)

**ترويسة الـ Device token:**
- `Authorization: Device <token>` — ترويسة مستقلة ومقصودة
- أو `Authorization: Bearer <token>` (بديل مقبول)

> `Authorization: Bearer` يُفسَّر كـ **Parent token** بشكل افتراضي.
> أي طلب على مسارات الجهاز (`/api/devices/*/...`) يُقبل فقط بـ Device token.

### 1.3 WebSocket

```
ws://<host>/ws?token=<parent-token>
ws://<host>/ws?token=<device-token>
```

- اتصال الـ parent يستقبل أحداث `device.*` لجميع أجهزته.
- اتصال الـ device يستقبل إشعار `config.updated`.
- الرسائل: `{ "type": "device.location", "deviceId": 3, "data": {...} }`
- ping/pong كل 30 ثانية.

### 1.4 البث المباشر (Live v2)

جلسة بث **واحدة** لكل جهاز في أي وقت: `source = "screen"` أو `"camera"`.
محتوى الفيديو يُرسل كإطارات **JPEG ثنائية** (Motion-JPEG) لأنها تعمل على 4G بلا
مُرمِّز فيديو، ولأنها تُستهلك مباشرة في المتصفح دون WebCodecs.

#### التحكم (JSON)

الأب → السيرفر:

```jsonc
{ "type": "live.start", "deviceId": 3, "source": "screen" }  // أو "camera"
{ "type": "live.stop",  "deviceId": 3 }
{ "type": "live.switch", "deviceId": 3, "facing": "front" }  // أو "back"
```

السيرفر يتحقق أن الجهاز يملكه نفس الأب، ثم:

- السيرفر → الجهاز: `{ "type": "live.start", "source": "camera" }` أو `{ "type": "live.stop" }`
  أو `{ "type": "live.switch", "facing": "front" }`
- الجهاز → السيرفر → كل أجهزة الأب: `{ "type": "live.state", "deviceId": 3,
  "state": "started"|"stopped"|"error", "source": "screen", "error": "NO_CONSENT" }`
- عند تبديل الكاميرا: `{ "type": "live.camera", "deviceId": 3, "facing": "front" }`

أكواد الخطأ:

| error | المعنى |
|---|---|
| `NO_CONSENT` | لم يمنح المستخدم موافقة MediaProjection بعد |
| `SCREEN_UNAVAILABLE` | تعذّر إنشاء `VirtualDisplay` (قيود النظام) |
| `NO_CAMERA_PERMISSION` | إذن الكاميرا غير ممنوح |
| `NO_CAMERA` / `CAMERA_OPEN_FAILED` | لا كاميرا متاحة أو مشغولة |
| `CAMERA_ERROR` / `CAMERA_DISCONNECTED` | انقطاع الكاميرا أثناء البث |
| `DEVICE_OFFLINE` | لا يوجد اتصال WebSocket للجهاز |
| `FORBIDDEN` / `BAD_DEVICE` | الجهاز ليس ملكاً لهذا الحساب |

#### الإطارات (Binary)

الجهاز → السيرفر (12 بايت ثم الحمولة):

```
[0]      version = 1
[1]      source   (1 = شاشة، 2 = كاميرا، 3 = صوت الشاشة، 4 = صوت الكاميرا)
[2..3]   flags    (bit0 = 1 → الحمولة PCM وليست JPEG)
[4..7]   seq      uint32 BE
[8..11]  length   uint32 BE  (طول الحمولة التي تليه)
```

السيرفر يعيد التغليف بإضافة `deviceId` (16 بايت ثم الحمولة):

```
[0]      version = 1
[1]      source
[2..3]   flags
[4..7]   deviceId uint32 BE   ← يضيفه السيرفر
[8..11]  seq      uint32 BE
[12..15] length   uint32 BE
```

**الصوت (flags bit0 = 1):** الحمولة PCM 16-بت أحادي بتردّد **16000 هرتز**، بترتيب
بايتات little-endian. لا يوجد ضغط ولا ترميز — المتصفح يحوّله إلى `AudioBuffer`
ويشغّله مباشرة. تصل حزمة صوت كل ~100 مللي ثانية تقريباً. تدفّق الصوت مستقل عن الفيديو:
يمكن أن تتوقف إطارات JPEG (شاشة ساكنة) ويستمر الصوت.

- المعدّل المستهدف: إطار كل **200 مللي ثانية** (≈5 إطارات/ث) أثناء تغيّر الشاشة فعلياً،
  عرض ≤ 640px، جودة JPEG 45. الجهاز يخفّض الدقة/الجودة تلقائياً إذا امتلأ طابور الإرسال،
  ويعيدها تدريجياً عند تحسّن الشبكة.
- **الشاشة الساكنة لا تولّد إطارات**: أندرويد لا ينتج إطار `VirtualDisplay` جديداً إلا عند
  تغيّر المحتوى، لذا يتوقف العدّاد مؤقتاً أثناء سكون الجهاز ويستأنف فور الحركة.
- الإطارات تُسقَط عند بطء الرابط بدل تكديسها: على الجهاز عندما يتجاوز طابور WebSocket
  `96KB`، وعلى السيرفر عندما يتجاوز `bufferedAmount` للمستهلك `2MB`. النتيجة صورة
  "حاضرة" أقلّ انسيابية بدل تأخير متزايد.
- لا يُخزَّن أي إطار على القرص أو في قاعدة البيانات.

### 1.5 أوامر الجهاز (رنين الجهاز عن بُعد)

الأب → السيرفر:

```jsonc
{ "type": "device.command", "deviceId": 3, "action": "alarm" }       // أو "stop_alarm"
```

- السيرفر → الجهاز: `{ "type": "device.command", "action": "alarm" }`
- السيرفر → الأب الطالب: `{ "type": "device.command.result", "deviceId": 3,
  "action": "alarm", "ok": true }` أو `{ "ok": false, "error": "DEVICE_OFFLINE" }`
- الجهاز → السيرفر → كل أجهزة الأب: `{ "type": "device.command.result", "deviceId": 3,
  "action": "alarm", "ok": true }`
- أي `action` خارج القائمة يُرفض بـ `BAD_COMMAND`.

الرنين يستخدم منبّه النظام بأقصى صوت، ويتوقف تلقائياً بعد 60 ثانية أو بأمر `stop_alarm`.

---

## 2. المصادقة — `/api/auth`

### POST `/api/auth/register`
```jsonc
// req
{ "email": "parent@example.com", "password": "Str0ngPass!", "name": "الأب" }
// res 201
{ "ok": true, "data": { "token": "<jwt>", "user": { "id":1, "email":"...", "name":"..." } } }
```

### POST `/api/auth/login`
```jsonc
// req
{ "email": "parent@example.com", "password": "Str0ngPass!" }
// res 200
{ "ok": true, "data": { "token": "<jwt>", "user": {...} } }
```

### GET `/api/auth/me`
→ `{ "ok": true, "data": { "user": {...} } }` (يحتاج Parent token)

---

## 3. الأجهزة — `/api/devices`

### POST `/api/devices/enroll-code`  🔑 Parent token
يولّد كود تسجيل من 8 أرقام صالح 10 دقائق.
```jsonc
// res 201
{ "ok": true, "data": { "code": "48130277", "expiresAt": "2026-10-21T16:00:00.000Z" } }
```

### POST `/api/devices/register`  📱 بدون توكن (كود فقط)
يُستدعى مرة واحدة من تطبيق الأندرويد. يستهلك الكود.
```jsonc
// req
{
  "code": "48130277",
  "deviceName": "هاتف الطفل",
  "manufacturer": "Samsung",
  "model": "SM-A125F",
  "androidVersion": "13",
  "sdkInt": 33,
  "appVersion": "1.0.0"
}
// res 200
{ "ok": true, "data": {
    "deviceId": 3,
    "deviceToken": "<jwt-device>",
    "serverUrl": "https://host/api"
} }
```
- عند النجاح: `deviceId` يُربط بـ `userId` صاحب الكود، والجهاز يبدأ بـ `status: "active"`.

### GET `/api/devices` 🔑 Parent
```jsonc
{ "ok": true, "data": [
  { "id":3, "name":"...", "manufacturer":"Samsung", "model":"SM-A125F",
    "androidVersion":"13", "status":"active", "lastSeen":"2026-10-21T15:50:00.000Z",
    "battery": 76, "charging": false, "wifi": "Home-5G",
    "location": { "lat":24.7136, "lng":46.6753, "accuracy":12, "at":"2026-10-21T15:49:00.000Z" } }
]}
```

### GET `/api/devices/:id` 🔑 Parent → جهاز واحد
### DELETE `/api/devices/:id` 🔑 Parent → يوقف الجهاز ويحذف الـ token (يبقى السجل للتقارير)

---

## 4. بيانات الجهاز (كلها 🔑 Device token، المسار تحت `/api/devices/:id/...`)

> `:id` يجب أن يطابق الـ `deviceId` داخل الـ Device token، وإلا `FORBIDDEN`.

### POST `/api/devices/:id/heartbeat`
```jsonc
// req
{ "battery": 76, "charging": false, "wifi": "Home-5G",
  "networkType": "wifi", "storageFreeMb": 12000, "uptimeSeconds": 5400,
  "screenOn": true, "appVersion": "1.0.0" }
// res 200 → { "ok": true, "data": { "serverTime": "...", "config": { "locationIntervalSec": 60, "usageIntervalSec": 300 } } }
```

### POST `/api/devices/:id/location`
```jsonc
// req
{ "lat": 24.7136, "lng": 46.6753, "accuracy": 12, "speed": 1.4,
  "altitude": 612, "bearing": 90, "provider": "gps", "battery": 76,
// res 200
{ "ok": true, "data": { "id": 991, "at": "2026-10-21T15:49:00.000Z" } }
```

### POST `/api/devices/:id/events`
نوع واحد شامل لتقارير الاستخدام (app usage).
```jsonc
// req
{ "type": "app_usage",
  "package": "com.instagram.android",
  "label": "Instagram",
  "durationMs": 540000,
  "openedAt": "2026-10-21T15:40:00.000Z",
  "foreground": true }
// res 200
{ "ok": true, "data": { "id": 812 } }
```

`type` المدعوم: `app_usage`, `app_installed`, `app_removed`, `browser_history`, `notification`, `custom`.

### POST `/api/devices/:id/calls`
```jsonc
// req
{ "number": "+966500000000", "contactName": "أحمد", "type": "incoming",
  "durationSec": 125, "at": "2026-10-21T15:30:00.000Z" }
// type: incoming | outgoing | missed
```

### POST `/api/devices/:id/sms`
```jsonc
// req
{ "address": "+966500000000", "body": "مرحبا", "type": "inbox", "at": "..." }
// type: inbox | sent | draft
```

### POST `/api/devices/:id/contacts`
مزامنة دفتر العناوين كاملاً في طلب واحد. الرقم هو المفتاح الطبيعي، فيُحدَّث الاسم
بدل تكرار الصف عند كل مزامنة. تُتجاهل العناصر بلا رقم.
```jsonc
// req
{ "contacts": [
  { "name": "أحمد", "number": "+966500000001", "email": "ahmed@example.com" },
  { "name": "سارة", "number": "+966500000002" }
] }
// res 200
{ "ok": true, "data": { "count": 2 } }
```

### POST `/api/devices/:id/events` — إشعارات الجهاز
عند تفعيل «الوصول إلى الإشعارات» يرسل التطبيق حدثاً لكل إشعار:
```jsonc
{ "type": "notification", "package": "com.whatsapp", "label": "WhatsApp",
  "title": "أحمد", "text": "وصلت البيت", "at": "2026-10-21T15:40:00.000Z" }
```

### POST `/api/devices/:id/screenshots`  📎 multipart
`file` = صورة PNG/JPG، `meta` = JSON string اختياري `{ "app":"com.x", "label":"Chrome" }`.
```jsonc
// res 201
{ "ok": true, "data": { "id": 55, "kind": "screenshot",
  "url": "/api/devices/3/media/55", "size": 482113, "at": "..." } }
```

### POST `/api/devices/:id/photos` 📎 multipart
نفس الشكل لكن `kind: "camera"`.

### POST `/api/devices/:id/apps` — قائمة التطبيقات المثبتة
```jsonc
// req
{ "apps": [ { "package":"com.x", "label":"X", "version":"1.0", "system": false, "installedAt": "..." } ] }
```

---

## 5. قراءة البيانات — Parent token

| المسار | الوصف | query params |
|---|---|---|
| `GET /api/devices/:id/location` | آخر موقع | — |
| `GET /api/devices/:id/locations` | مسار زمني | `from`, `to` (ISO), `limit` (افتراضي 500) |
| `GET /api/devices/:id/usage` | usage مجمّع بالتطبيق | `from`, `to`, `limit` |
| `GET /api/devices/:id/calls` | سجل المكالمات | `from`, `to`, `limit` |
| `GET /api/devices/:id/sms` | الرسائل | `from`, `to`, `limit` |
| `GET /api/devices/:id/apps` | التطبيقات المثبتة | — |
| `GET /api/devices/:id/contacts` | دفتر عناوين الطفل | `q` (بحث بالاسم/الرقم), `limit` |
| `GET /api/devices/:id/geofences` | المواقع الآمنة للجهاز | — |
| `GET /api/devices/:id/media` | الوسائط | `kind` (screenshot/camera), `from`, `to`, `limit` |
| `GET /api/devices/:id/media/:mediaId` | تنزيل ملف | — |
| `GET /api/devices/:id/timeline` | جدول زمني موحّد لكل الأحداث | `from`, `to`, `limit` |
| `GET /api/devices/:id/stats` | إحصائيات اليوم/الأسبوع | — |

### 5.1 المواقع الآمنة — 🔑 Parent

```jsonc
// POST /api/devices/:id/geofences
{ "name": "المنزل", "lat": 35.32758, "lng": -0.05293, "radiusM": 200 }
// res 201 → دائرة الموقع (radiusM بين 30 و20000)

// PATCH /api/devices/:id/geofences/:geoId
{ "name": "المدرسة", "radiusM": 300, "active": true }

// DELETE /api/devices/:id/geofences/:geoId → { "ok": true, "data": { "deleted": true } }
```

كل موقع يحمل `inside` (`null` قبل أول تحديث موقع، ثم `0/1`). يقيّم السيرفر الموقع عند
استلام كل `POST /location` (مسافة haversine): عند أول رصد يُثبَّت الحالة بلا تنبيه،
وبعدها يُنشأ تنبيه `geofence_enter` أو `geofence_exit` عند كل عبور فقط — لا تكرار.

### 5.2 التنبيهات — 🔑 Parent

| المسار | الوصف | query/body |
|---|---|---|
| `GET /api/alerts` | قائمة التنبيهات (الأحدث أولاً) | `limit`, `unread=1`, `deviceId` |
| `POST /api/alerts/read` | تعليم كمقروء | `{ "ids": [1,2] }` أو `{ "ids": [] }` للكل |

```jsonc
// GET /api/alerts
{ "ok": true, "data": [
    { "id": 7, "deviceId": 3, "type": "geofence_exit", "title": "خرج من «المنزل»",
      "body": "الجهاز غادر الموقع الآمن.", "at": "...", "read": false,
      "payload": { "geofenceId": 1, "name": "المنزل", "lat": 35.4, "lng": -0.05 } }
  ], "meta": { "count": 1, "unread": 1, "limit": 100 } }
```

عند إنشاء تنبيه يبثّ السيرفر لحظياً: `{ "type": "device.alert", "deviceId": 3, "data": {...} }`.

---

## 6. قواعد الترقيم (Pagination)

كل رد قراءة يحتوي على `limit`:
```jsonc
{ "ok": true, "data": [ ... ], "meta": { "count": 42, "limit": 500 } }
```

---

## 7. تقييد المعدل (Rate limiting)

- مسارات `/api/auth/*`: 10 طلبات / 15 دقيقة لكل IP.
- مسارات الجهاز: 600 طلب / دقيقة لكل جهاز.
- الرد عند التجاوز: HTTP 429 + `Retry-After` header.

## 8. CORS

- `Access-Control-Allow-Origin` = `*` (الـ API من جهة تطبيقات الأجهزة لا تفرض CORS، والـ dashboard يعمل same-origin).
- تُقبل جميع methods والـ headers مع `OPTIONS` preflight.
