# خيارات الـ VPS المجاني — موقّع في أكتوبر 2026

> ⚠️ **الخطط المجانية تتغير باستمرار.** تحقّق من الأرقام الرسمية قبل الاعتماد عليها.
> كل المسارات المجانية هنا تتطلّب **بطاقة ائتمان** للتحقق (لا يتم الخصم منها إذا بقيت ضمن الحد المجاني).

---

## ملخص سريع

| المزوّد | المواصفات المجانية | عنوان IP عام | المدة | التقييم |
|---|---|---|---|---|
| **Oracle Cloud (Always Free)** | 2 OCPU / 12GB RAM (معالج Ampere ARM) · 200GB قرص · 4 عناوين IP ثابتة · 10TB/شهر صادر | ✅ نعم | دائم | ⭐ **الأفضل** |
| **AWS Free Tier** | `t3.micro` / `t3.small` · 30GB قرص · 12GB/شهر صادر | ✅ نعم | 12 شهراً | ⭐ ممتاز للتجربة |
| **Google Cloud Free Tier** | `e2-micro` · 1GB RAM · 30GB قرص | ⚠️ مدفوع (~$3.65/شهر) | دائم | ❌ ليس مجانياً فعلياً |
| **Fly.io** | `shared-cpu-1x` (3GB) ضمن رصيد مجاني | ✅ نعم | دائم | ✅ جيد |
| **Railway** | رصيد $5/شهر | ✅ نعم | دائم | ✅ يكفي لهذا التطبيق |
| **Render** | 750 ساعة/شهر | ✅ نعم | دائم | ⚠️ يتوقف بعد 15 دقيقة خمول |
| **Hetzner / DigitalOcean / Vultr** | — | ✅ نعم | — | ❌ مدفوع فقط |

---

## 1) Oracle Cloud — الخيار الموصى به ⭐

**لماذا هو الأفضل؟** موارد مجانية سخيّة جداً (2 نواة ARM + 12GB رام)، وعناوين IP ثابتة مجانية،
وحدّ البيانات الصادرة 10TB شهرياً — أكثر بكثير مما يحتاجه هذا التطبيق.

**الخطوات:**

1. سجّل في <https://www.oracle.com/cloud/free/> (يلزم بطاقة ائتمان للتحقق فقط).
   - نصيحة: اختر منطقة **Frankfurt** أو **Amsterdam** — قربك من الشرق الأوسط يقلّل زمن الاستجابة.
2. بعد إنشاء الحساب، ادخل **Compute → Instances → Create instance**:
   - **Shape:** `VM.Standard.A1.Flex` (معالج Ampere ARM)
   - **OCPU:** 2 · **RAM:** 12 GB (أو أقل — التطبيق يستهلك ~100MB)
   - **Image:** Ubuntu 24.04 (أو Oracle Linux 9)
   - **Networking:** أنشئ VCN مع **Public IP** (اختر *Assign a public IP address*)
3. أضف **قاعدةfirewall جديدة**: Source `0.0.0.0/0`، Source Port `22`، Destination Port `443`،
   Protocol **TCP**. (منفذ 80 مطلوب إن أردت Let's Encrypt.)
4. ادخل عبر SSH:
   ```bash
   ssh -i ~/Downloads/ssh-key.key ubuntu@<your-instance-ip>
   ```
5. شغّل مثبّت المشروع:
   ```bash
   sudo apt update && sudo apt install -y git
   git clone <your-repo-url> guardian && cd guardian
   sudo DOMAIN=guardian.example.com ./deploy/setup.sh
   ```

**⚠️ مآخذ يجب معرفتها:**

- **"Out of capacity"** — أشهر المزايا: المنطقة قد تكون ممتلئة. جرّب منطقة أخرى
  (Amsterdam / Frankfurt / Zurich / Stockholm) أو غيّر الـ shape إلى `VM.Standard.E2.Flex.Micro`.
- **حذف الحسابات الخاملة** — Oracle حذفت سابقاً حسابات Always Free خاملة.
  للتفادي: فعّل **Budget & Cost Alerts**، وابقَ الـ instance في حالة **RUNNING** (لا توقفه)،
  وفعّل **Identity Domain MFA**، وسجّل الدخول شهرياً.
- **المعمارية ARM** — التطبيق يعمل عليه بلا مشكلة (Node.js أصلي لـ arm64).

---

## 2) AWS Free Tier — بديل ممتاز (12 شهراً)

- **EC2:** `t3.micro` (1GB) أو `t3.small` (2GB) مجاناً لمدة 12 شهراً.
- أنشئ **AMI** من Ubuntu 24.04، فعّل **Elastic IP** (مست IPs ثابتة مجانية).
- بعد 12 شهراً ينتقل لحساب On-Demand — ألغِ الـ instance عندها أو استخدم Free Tier
  continually (الخدمات مثل EBS وTraffic مجانية جزئياً).
- نفس خطوات التثبيت أعلاه.

---

## 3) Fly.io / Railway — نشر بضغطة زر

مناسب إن أردت نشراً سريعاً بلا SSH:

```bash
# Fly.io
fly launch --no-deploy --name guardian
fly deploy
fly secrets set JWT_SECRET=$(openssl rand -base64 48)
fly ips allocate-v4 --shared
```

```bash
# Railway
railway init && railway up
railway variables set JWT_SECRET="$(openssl rand -base64 48)"
```

> ⚠️ كلاهما يوفّر **منفذاً عاماً (HTTP port)** يجب ربطه بالدومين/النطاق.

---

## 4) Render — مجاني لكن يتوقف

- Web Service مجاني: يتوقف (sleep) بعد **15 دقيقة** من عدم النشاط، ويستيقظ خلال ~30 ثانية.
- مشكلة: WebSocket و**التحديث اللحظي للموقع** سيتقطعان عند النوم.
- الحل: إضافة **Cron Job** كل 10 دقائق ينفذ `/api/health` لإبقاء الخدمة مستيقظة (أو استخدم uptime-ping مجاني).

---

## بدائل الـ HTTPS المجاني (مهم!)

### أ) Cloudflare Tunnel — الأسهل (بلا دومين)

```bash
# رابط فوري (يتغير عند إعادة التشغيل) — للتجربة السريعة
cloudflared tunnel --url http://localhost:3000

# أو نفق دائم (يتطلب حساب Cloudflare مجاني + دومين)
cloudflared tunnel login
cloudflared tunnel create guardian
cloudflared tunnel route dns guardian app.yourdomain.com
cloudflared tunnel run guardian
```
✅ يعطي HTTPS تلقائياً · ✅ يخفي IP السيرفر · ✅ مجاني بالكامل

### ب) DuckDNS — دومين مجاني + Let's Encrypt

1. سجّل في <https://www.duckdns.org> واحصل على توكن.
2. اضبط الدومين على IP السيرفر: `curl "https://www.duckdns.org/update?domains=NAME&token=TOKEN&ip=IP"`
3. `certbot --nginx -d NAME.duckdns.org` → شهادة SSL مجانية.

### ج) Let's Encrypt مباشرة

```bash
sudo apt install certbot python3-certbot-nginx
sudo certbot --nginx -d guardian.example.com
```

---

## التوصية النهائية

```
Oracle Cloud Always Free (2 OCPU / 12GB ARM)
        │
        ├── Cloudflare Tunnel  ←  HTTPS مجاني فوراً، بدون دومين
        │
        └── Guardian (Node.js + SQLite)  ←  systemd + Nginx
                    │
                    └── تطبيق أندرويد على هاتف الطفل
```

> **تذكير:** الاستخدام مشروع فقط على أجهزة **أبنائك** وموافقتهم، وبإفصاح واضح
> للطفل بأن المراقبة مفعّلة (التطبيق يُظهر إشعاراً دائماً — وهذا مقصود).
