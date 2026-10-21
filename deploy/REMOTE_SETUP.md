# 🚀 نشر Guardian على VPS مجاني — دليل خطوة بخطوة

> هذا الدليل يأخذك من **حساب Oracle Cloud** حتى **تطبيق يعمل على هاتف الطفل**.
> الوقت الإجمالي: **١٥–٢٥ دقيقة** (أغلبها انتظار).
> كل الأوامر مكتوبة لتُنسخ مباشرة.

---

## 📌 قبل أن تبدأ: جهّز جهازك

```bash
# 1) تأكد أن المشروع على جهازك
cd ~/parental-guardian   # أو المسار أينما نسخته
ls deploy/setup.sh       # يجب أن يطبع مساراً

# 2) تأكد أن مفتاح SSH جاهز
ls -la ~/.ssh/guardian_vps.pub
```

**مفتاحك العام (انسخه كاملاً — ستحتاجه في اللوحة):**

```
ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIPAVmKraU85tBOzPb8rf6brvaVVODPVjYybIu69porzl guardian-vps
```

> إن لم يكن موجوداً:
> ```bash
> ssh-keygen -t ed25519 -N '' -C "guardian-vps" -f ~/.ssh/guardian_vps
> cat ~/.ssh/guardian_vps.pub
> ```

---

## 🟠 الخطوة ١ — إنشاء حساب Oracle Cloud (١٠ دقائق)

1. افتح <https://www.oracle.com/cloud/free/> واضغط **Start for Free**.
2. املأ البريد الإلكتروني وكلمة مرور قوية.
3. **اختر الدولة (Country)** — اختر بلدك الحقيقي (التأثير على الفوترة).
4. **التحقق بالبطاقة** — تطلب Oracle بطاقة ائتمان (أو PayPal) *للتحقق فقط*.
   - لن يتم الخصم ما دمت ضمن رصيد Always Free.
   - إذا رفضت البطاقة (بطاقات افتراضية/مسبقة الدفع فاشلة غالباً) جرّب بطاقة أخرى.
5. اختر **Home Region** بعناية — **لا يمكن تغييره لاحقاً**:
   - 🟢 **Frankfurt** أو **Amsterdam** أو **Zurich** (أوروبا — جيد للأوروبا والشرق)
   - 🟢 **Stockholm** / **Sao Paulo** / **Johannesburg** (بدائل إن امتلأت أوروبا)
   - ⚠️ تجنّب `US East` إذا كنت في الشرق الأوسط — زمن الاستجابة أعلى.
6. أكمل التسجيل (رقم هاتف، العنوان).
7. بعد الدخول للوحة، قد تظهر لك **صفحة "Welcome to Oracle Cloud Free Tier"** — اختر
   **Create a free tier instance** أو اضغط مباشرة **Compute → Instances**.

> ⏱️ أحياناً يستغرق تفعيل الحساب **حتى ٢٤ ساعة** (خاصة بعد الساعة مساءً بتوقيت Oracle).
> إن ظهرت رسالة "We're processing your request" — انتظر وأعد الدخول لاحقاً.

---

## 🟠 الخطوة ٢ — إنشاء الخادم (٥ دقائق)

في **Compute → Instances → Create instance**:

| الحقل | القيمة | لماذا |
|---|---|---|
| **Shape** | `VM.Standard.A1.Flex` (معالج Ampere ARM) | المجاني: 2 OCPU / 12GB |
| **OCPU** | `2` | كافٍ جداً (التطبيق يستهلك ~100MB) |
| **RAM** | `12 GB` (أو أقل) | مجاني حتى 12GB |
| **Image** | `Canonical Ubuntu 24.04` (Minimal أو Standard) | مدعوم بالكامل |
| **Networking** | أنشئ **Default VCN** | الشبكة الافتراضية |
| **Assign a public IP address** | ✅ **مفعّل** | بدونه لا يمكنك الوصول |

> ⚠️ **لا تختار** `VM.Standard.E2.Flex.Micro` إلا إذا فشلت A1 — E2 المجاني **٢ GB** فقط
> (يكفي، لكنه أبطأ وأقل سعة).

### بعد الإنشاء مباشرة — أضف قواعد الجدار الناري

أثناء الإنشاء أو بعده من **Instance → Networking → Add ingress rule**:

| Source CIDR | Destination Port | Protocol |
|---|---|---|
| `0.0.0.0/0` | `22` | TCP |
| `0.0.0.0/0` | `443` | TCP |
| `0.0.0.0/0` | `80` | TCP |

> 🔐 **حذار أمني:** فتح `22` على `0.0.0.0/0` يعني أي شخص على الإنترنت يستطيع تجربة
> تسجيل دخولك. **مفتاح SSH ed25519 مع تعطيل كلمة المرور** يضيف حماية كافية عملياً،
> لكن الخيار الأمثل هو تقييد المصدر إلى IP بيتك. إن كان عنوانك ثابتاً، اجعل
> المصدر `your.home.ip/32` بدل `0.0.0.0/0`.

### الصق المفتاح العام

في شاشة **Add SSH keys**:
- **اختر Upload** → اختر `~/.ssh/guardian_vps.pub` من جهازك
- أو **اختر Generate** واترك Oracle يولّد مفتاحاً — *لكن لن تتمكن من الاتصال بمفتاحك أنت*، فاختر Upload.

اضغط **Create**. انتظر حتى تصبح الحالة **RUNNING**.

**انسخ الـ Public IP** من صفّ-instance (مثال: `158.180.42.19`).

---

## 🟠 الخطوة ٣ — الاتصال بالخادم

```bash
ssh -i ~/.ssh/guardian_vps ubuntu@158.180.42.19
```

> أول مرة سيطلب تأكيد بصمة المفتاح → اكتب `yes` ثم Enter.
> إن ظهر `Permission denied (publickey)` فالمفتاح لم يُرفع بشكل صحيح — راجع الخطوة ٢.

**تحقق من النظام:**
```bash
cat /etc/os-release | head -2
uname -m          # يجب أن يطبع aarch64
free -h           # يجب أن يظهر ~12GB
```

---

## 🟠 الخطوة ٤ — رفع المشروع

**من جهازك** (في نافذة طرفية ثانية، أو اخرج من SSH أولاً):

```bash
cd ~/parental-guardian
./deploy/upload.sh ubuntu@158.180.42.19
```

ينتج `/tmp/guardian-upload/` على الخادم.

> **بديل بدون رفع** — انسخ المشروع عبر GitHub:
> ```bash
> git remote add origin git@github.com:<user>/parental-guardian.git
> git push -u origin main
> # ثم على الخادم:
> git clone git@github.com:<user>/parental-guardian.git /tmp/guardian-upload
> ```

---

## 🟠 الخطوة ٥ — التثبيت (٣ دقائق)

على **الخادم** (داخل SSH):

```bash
cd /tmp/guardian-upload
chmod +x deploy/setup.sh
sudo ./deploy/setup.sh
```

السكربت يقوم تلقائياً بـ:
- ✅ تثبيت Node.js 22 و Nginx و rsync و sqlite3 و ufw
- ✅ إنشاء مستخدم نظام `guardian` (بدون shell)
- ✅ نسخ الملفات إلى `/opt/guardian/server`
- ✅ `npm ci --omit=dev`
- ✅ توليد `JWT_SECRET` عشوائي ٤٨ بايت وحفظه في `.env` بصلاحيات `600`
- ✅ تسجيل خدمة `systemd` + تفعيلها عند الإقلاع
- ✅ تهيئة UFW (منفذ 22 + 80/443)
- ✅ **فحص صحي** للتأكد أن التطبيق يستجيب

**تحقق:**
```bash
systemctl status guardian --no-pager
curl -s http://127.0.0.1:3000/api/health
# {"ok":true,"data":{"status":"healthy",...}}
```

> ❌ **"Out of capacity"** عند الإنشاء؟ جرّب منطقة أخرى (Instance → Edit → Change shape
> أو احذف وأنشئ في منطقة ثانية). هذه أشهر عقبة في Oracle المجاني.

---

## 🟠 الخطوة ٦ — HTTPS مجاني (٥ دقائق)

### ✅ الخيار الموصى به: Cloudflare Tunnel (بلا دومين، رابط دائم مع حساب)

```bash
# على الخادم
curl -fsSL https://pkg.cloudflare.com/cloudflare-main.gpg | sudo tee /usr/share/keyrings/cloudflare-main.gpg >/dev/null
echo "deb [signed-by=/usr/share/keyrings/cloudflare-main.gpg] https://pkg.cloudflare.com/cloudflared any main" | sudo tee /etc/apt/sources.list.d/cloudflared.list
sudo apt-get update -qq && sudo apt-get install -y cloudflared
```

**أ) رابط فوري (للتجربة — يتغير عند إعادة تشغيل الخادم):**
```bash
cloudflared tunnel --url http://localhost:3000
```
سيطبع رابطاً مثل `https://abc-defg.trycloudflare.com` — **افتحه في المتصفح** 🎉

**ب) رابط دائم (بلا دومين خاص بك):**
```bash
cloudflared tunnel login          # يفتح المتصفح — سجّل دخولك
cloudflared tunnel create guardian
cloudflared tunnel route dns guardian guardian.yourname.workers.dev   # أو دومينك
sudo cloudflared service install
sudo systemctl enable --now cloudflared
```
> بعد `tunnel create` نفّذ `cloudflared tunnel route dns` للاسم الذي تريده.
> لو لم يكن لديك دومين، استخدم نطاق Worker مجاني الذي يقدّمه Cloudflare.

**ج) تثبيت الرابط داخل التطبيق** (حتى تُرسله الأجهزة تلقائياً):
```bash
sudo sed -i "s|^PUBLIC_URL=.*|PUBLIC_URL=https://YOUR-TUNNEL-URL|" /opt/guardian/server/.env
sudo systemctl restart guardian
```

### 🌐 الخيار البديل: نطاق فرعي مع DuckDNS + Certbot

```bash
# 1) سجّل في https://www.duckdns.org واحصل على توكن
# 2) اجعل DNS يشير إلى IP الخادم
curl "https://www.duckdns.org/update?domains=YOURNAME&token=YOURTOKEN&ip=YOUR_VPS_IP"

# 3) على الخادم
sudo apt-get install -y certbot python3-certbot-nginx
sudo certbot --nginx -d YOURNAME.duckdns.org
sudo sed -i "s|^PUBLIC_URL=.*|PUBLIC_URL=https://YOURNAME.duckdns.org|" /opt/guardian/server/.env
sudo systemctl restart guardian
```

---

## 🟠 الخطوة ٧ — إنشاء حساب ولي الأمر

1. افتح الرابط (`https://YOUR-TUNNEL-URL`) في المتصفح.
2. تبويب **«حساب جديد»** → بريد + كلمة مرور (٨ أحرف على الأقل).
3. **احفظ كلمة المرور في مدير كلمات مرور** — لا يمكن استعادتها (تخزَّب بـ scrypt).

---

## 🟠 الخطوة ٨ — ربط هاتف الطفل

1. في اللوحة → زر **«+ جهاز»** → يظهر كود من ٨ أرقام (صالح ١٠ دقائق، مرة واحدة).
2. **ثبّت تطبيق Guardian** على هاتف الطفل (Android 8.0+):
   - افتح مجلد `android/` في Android Studio
   - `Build → Build Bundle(s)/APK(s) → Build APK(s)`
   - انقل `android/app/build/outputs/apk/debug/app-debug.apk` إلى الهاتف وثبّته
3. في التطبيق: أدخل **عنوان السيرفر** (نفس رابط اللوحة، بدون `/api` — التطبيق يضيفه تلقائياً)
   ثم **الكود** → اضغط **«ربط الجهاز»**.
4. ارجع للوحة — سيظهر الجهاز مع **خريطة لحظية**.

### أذونات أندرويد (اطلبها من التطبيق خطوة بخطوة)

| الإذن | لماذا | كيف يُمنح |
|---|---|---|
| الإشعارات | إشعار دائم يوضح أن المراقبة مفعّلة | موافقة popup |
| الموقع (fg + bg) | الخريطة اللحظية | من إعدادات النظام |
| **الاستخدام** (Usage Access) | وقت كل تطبيق | إعدادات → Special access |
| سجل المكالمات | المكالمات الواردة/الصادرة | popup |
| الرسائل | قراءة الرسائل | popup |
| جهات الاتصال | أسماء جهات الاتصال بدل الأرقام | popup |
| الكاميرا | صور الكاميرا (**معطّل افتراضياً**) | popup |

> 🔒 **لقطة الشاشة** تتطلب موافقة **صريحة في كل جلسة** من نظام أندرويد
> (نافذة "السماح بتسجيل الشاشة") — لا يمكن تجاوزها، وهذا مقصود.

---

## 🟠 الخطوة ٩ — التشغيل اليومي

```bash
# حالة الخدمة
systemctl status guardian

# السجلات اللحظية
journalctl -u guardian -f

# إعادة التشغيل بعد تعديل
sudo systemctl restart guardian

# تحديث الكود
cd /opt/guardian && sudo rsync -a --exclude node_modules --exclude data --exclude uploads \
  --exclude .env /tmp/guardian-upload/server/ /opt/guardian/server/
sudo systemctl restart guardian
```

### النسخ الاحتياطي (مهم)

```bash
sudo install -m 755 /tmp/guardian-upload/deploy/backup.sh /usr/local/bin/guardian-backup
sudo crontab -e
# أضف السطر التالي:
15 3 * * * /usr/local/bin/guardian-backup >> /var/log/guardian-backup.log 2>&1
```

---

## 🛡️ checklist الأمان بعد النشر

- [ ] `sudo ufw status` — يظهر `22/tcp` و `80/tcp` و `443/tcp` فقط
- [ ] `cat /opt/guardian/server/.env` — `JWT_SECRET` بطول ≥ ٣٢ حرفاً (لا تشاركه)
- [ ] كلمة مرور حساب ولي الأمر قوية ومختلفة عن أي حساب آخر
- [ ] راجعت أن **جهاز الطفل يعلم** أن التطبيق يعرض إشعاراً دائماً — لا تخفيه
- [ ] تقييد منفذ SSH لمصدر IP بيتك (اختياري لكن مُستحسن)
- [ ] جدولة النسخ الاحتياطي

---

## 🆘 حل المشاكل

| العرض | السبب | الحل |
|---|---|---|
| `Permission denied (publickey)` | المفتاح غير مرفوع | أعد رفع `~/.ssh/guardian_vps.pub` في Oracle |
| `ssh: connect to port 22: Connection timed out` | قاعدة الجدار الناري ناقصة | أضف ingress rule للمنفذ 22 |
| `Out of capacity` | المنطقة ممتلئة | غيّر المنطقة أو الـ shape |
| الخدمة `active (running)` لكن لا استجابة | انظر السجل | `journalctl -u guardian -n 50` |
| `502 Bad Gateway` من Nginx | التطبيق متوقف | `sudo systemctl restart guardian` |
| رابط tunnel يتغير بعد إعادة التشغيل | رابط سريع | استخدم نفقاً مسمّى أو systemd لـ cloudflared |
| `EADDRINUSE :3000` | عملية أخرى على المنفذ | `sudo ss -tlnp \| grep 3000` |
| WebSocket لا يتصل | جدار ناري | تأكد أن 443 مفتوح وأن Cloudflare يمرّر WS |

### تشخيص سريع

```bash
# كل شيء في أمر واحد
systemctl is-active guardian && \
curl -s http://127.0.0.1:3000/api/health && echo && \
sudo nginx -t && \
sudo ss -tlnp | grep -E ':(3000|443|22)'
```

---

## 📊 ملخص التكلفة

| البند | التكلفة |
|---|---|
| خادم Oracle (Always Free) | **٠ ريال** |
| نطاق فرعي (Cloudflare / DuckDNS) | **٠ ريال** |
| شهادة TLS (Let's Encrypt) | **٠ ريال** |
| تطبيق الأندرويد | ٠ (الجهاز يحتاج إنترنت للوصول للخادم فقط) |

> ⚠️ **انتبه:** Oracle قد تحذف حسابات Always Free الخاملة. للوقاية:
> فعّل **Budget & Cost Alerts**، واترك الخادم **RUNNING** دائماً، وسجّل الدخول شهرياً.
