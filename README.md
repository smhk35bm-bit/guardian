# 🛡️ Guardian — تطبيق رقابة أبوي متصل بـ VPS مجاني

منظومة متكاملة لمراقبة هاتف الأبناء عن بُعد، تتكون من ثلاثة أجزاء:

```
┌──────────────────────┐        ┌─────────────────────────┐        ┌──────────────────────┐
│  تطبيق أندرويد      │  HTTPS │  سيرفر Node.js على VPS  │        │  لوحة تحكم الأب     │
│  (هاتف الطفل)       │◄──────►│  REST + WebSocket       │◄──────►│  (متصفح / جوال)     │
│                      │        │  SQLite                 │        │  خريطة لحظية        │
└──────────────────────┘        └─────────────────────────┘        └──────────────────────┘
```

| المكوّن | التقنية | الموقع |
|---|---|---|
| لوحة التحكم (ويب) | HTML/CSS/JS خالص + Leaflet محلي | `server/public/` |
| السيرفر | Node.js 22+ · Express 5 · `node:sqlite` · `ws` | `server/src/` |
| تطبيق الأندرويد | Kotlin · Foreground Service · WorkManager | `android/` |
| النشر | systemd + Nginx + Cloudflare Tunnel | `deploy/` |

---

## ✨ الميزات

**على هاتف الطفل:**
- 📍 تحديد الموقع لحظياً + سجل المسار على الخريطة
- ⏱️ وقت استخدام كل تطبيق (UsageStatsManager)
- 📞 سجل المكالمات (واردة/صادرة/فائتة) مع أسماء جهات الاتصال
- 💬 الرسائل النصية
- 📦 قائمة التطبيقات المثبتة
- 🖥️ لقطات الشاشة (بموافقة صريحة من المستخدم في كل جلسة)
- 🔋 صحة الجهاز: البطارية، الشحن، الواي فاي، المساحة

**في لوحة التحكم:**
- 🗺️ خريطة OpenStreetMap بتحديث لحظي عبر WebSocket
- 📊 إحصائيات اليوم والأسبوع + أكثر التطبيقات استخداماً
- 🗂️ سجل زمني موحّد يجمع كل الأحداث
- 📷 معرض لقطات الشاشة والصور
- 🔐 تسجيل دخول، جلسات آمنة، تحديد معدل الطلبات، عزل كامل بين الحسابات

---

## 🚀 البدء السريع

### ١) تشغيل محلي (بلا VPS)

```bash
cd server
npm install
cp .env.example .env
node -e "console.log(require('crypto').randomBytes(48).toString('base64url'))"  # ضعها في JWT_SECRET
npm start
```

افتح <http://localhost:3000> → أنشئ حساب → «+ جهاز» → خذ الكود.

### ٢) تجربة كاملة بلا هاتف أندرويد

يوجد **محاكي جهاز** يرسل بيانات واقعية (موقع متحرك، استخدام، مكالمات، رسائل، لقطات):

```bash
cd server
node scripts/simulate-device.js --email dad@example.com --password 'Guardian!2026'
```

### ٣) النشر على VPS مجاني

**الدليل الكامل خطوة بخطوة** (حساب Oracle ← إنشاء الخادم ← SSH ← رفع ← تثبيت ← HTTPS):
👉 [`deploy/REMOTE_SETUP.md`](deploy/REMOTE_SETUP.md)

```bash
# من جهازك — ارفع المشروع إلى الخادم
./deploy/upload.sh ubuntu@158.180.42.19

# على الخادم — ثبّت كل شيء
cd /tmp/guardian-upload && sudo ./deploy/setup.sh
```

دليل اختيار المزوّد المجاني: [`deploy/VPS_OPTIONS.md`](deploy/VPS_OPTIONS.md)

### ٤) تطبيق الأندرويد

افتح مجلد `android/` في Android Studio، انشر على هاتف الطفل، ثم أدخل
عنوان السيرفر والكود من اللوحة.

التفاصيل الكاملة (البناء، الأذونات، حدود التطبيق): [`android/README.md`](android/README.md)

---

## 🔐 نموذج الأمان

| الجانب | التطبيق |
|---|---|
| كلمة مرور الأب | `scrypt` بملح عشوائي (من `node:crypto`، بلا تبعيات) |
| توكن الأب | JWT HS256 موقّع بـ `HMAC-SHA256`، ٣٠ يوماً، يُرسل كـ cookie `httpOnly` |
| توكن الجهاز | JWT سنة كاملة + سرّ عشوائي ٢٥٦-بت يُدوَّن في `jti` ويُتحقق منه في كل طلب |
| فصل الصلاحيات | مسار الأب لا يقبل توكن جهاز، ومسار الجهاز لا يقبل توكن أب |
| عزل البيانات | كل استعلام يقيَّد بـ `user_id` المالك — لا تسريب بين الحسابات |
| تحديد المعدل | ١٠ محاولات/١٥ دقيقة على المصادقة، ٦٠٠/دقيقة للأجهزة |
| رفع الملفات | حد ١٠ ميغابايت، اسم ملف عشوائي على القرص، تحقق من المسار عند القراءة |
| الترويسات | `helmet` + CSP + HSTS + `nosniff` |

### الشفافية (قاعدة غير قابلة للتفاوض)

> هذا التطبيق **مخصص لأجهزة أبنائك بموافقتهم**. لذلك:
> - التطبيق يُظهر **إشعاراً دائماً** غير قابل للإخفاء يقول إن المراقبة مفعّلة.
> - لا يحتوي على أي وضع **مخفي/متخفي**، ولا يخفي أيقونته، ولا يمنع إلغاء التثبيت.
> - لا يوجد أي تجعيد أو تمويه للكود أو محاولة لرصد أدوات الحماية وإزالتها.
> - لقطة الشاشة تتطلب موافقة صريحة من المستخدم في كل جلسة (رسم نظامي من أندرويد).

---

## 📁 بنية المشروع

```
parental-guardian/
├── server/                     # ← السيرفر + اللوحة
│   ├── src/
│   │   ├── index.js            # نقطة الدخول: Express + WS + systemd-friendly
│   │   ├── config.js           # قراءة .env والتحقق منها
│   │   ├── db.js               # مخطط SQLite + الاستعلامات + التنظيف الدوري
│   │   ├── realtime.js         # مركز WebSocket والغرف
│   │   ├── lib/                # jwt · password · ratelimit · util
│   │   ├── middleware/auth.js  # requireParent · requireDevice · requireOwnDevice
│   │   └── routes/             # auth · devices · data
│   ├── public/                 # لوحة التحكم (بدون build step)
│   ├── scripts/simulate-device.js
│   └── test/api.test.js        # ١٤ اختباراً تغطي العقد كاملاً
├── android/                    # ← تطبيق الأندرويد (Kotlin)
├── deploy/                     # ← systemd · nginx · setup.sh · upload.sh · REMOTE_SETUP.md
└── docs/API_CONTRACT.md        # ← العقد المرجعي بين الطرفين
```

---

## 🧪 الاختبارات

```bash
cd server && npm test
```

يغطي: التسجيل والدخول · دورة كود الربط (توليد/استهلاك/انتهاء) · رفع الوسائط ·
قراءة كل نقاط النهاية · **عزل الأجهزة** · **عزل الحسابات** · رفض التوكنات المقلوبة ·
إلغاء ربط الجهاز · التحقق من الإحداثيات · أخطاء 404.

---

## 📡 الـ API في لمحة

التفاصيل الكاملة في [`docs/API_CONTRACT.md`](docs/API_CONTRACT.md).

```bash
# إنشاء حساب
curl -X POST https://host/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"dad@example.com","password":"Str0ngPass!","name":"الأب"}'

# توليد كود ربط (يحتاج توكن الأب)
curl -X POST https://host/api/devices/enroll-code -H "Authorization: Bearer $TOKEN"

# بيانات الجهاز (تحتاج توكن الجهاز)
curl -X POST https://host/api/devices/1/location \
  -H "Authorization: Device $DEVICE_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"lat":24.7136,"lng":46.6753,"accuracy":12}'

# قراءة بيانات الأب
curl https://host/api/devices/1/locations?limit=100 -H "Authorization: Bearer $TOKEN"
```

---

## 📌 ملاحظات تشغيلية

- **القرص**: البيانات تُحذف تلقائياً بعد ٩٠ يوماً (`purgeOldData`).
- **النسخ الاحتياطي**: `sqlite3 server/data/guardian.db ".backup backup.db"` — يُنصح بمهمة يومية.
- **سجلات**: `journalctl -u guardian -f`.
- **تحديث**: `git pull && cd server && npm install --omit=dev && sudo systemctl restart guardian`.

---

## ⚖️ الاستخدام المسؤول

هذا المشروع أداة **حماية أسرة**. استخدمه على أجهزة أبنائك، بوافقتهم، مع إبلاغهم بوضوح.
لا يُستخدم للتجسس على أشخاص بالغين دون موافقتهم — تتبّع الأشخاص دون موافقتهم
مخالفة قانونية في معظم الدول، ومخالفة لأخلاقيات هذه الأداة.
