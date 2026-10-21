# خيارات الاستضافة — موقّع في أكتوبر 2026 (الجزائر · بلا بطاقة بنكية)

> **الوضع:** المستخدم في **الجزائر**، ووسيلة الدفع الوحيدة **RedotPay** (بطاقة رقمية مدعومة بالعملة الرقمية) — **لا توجد بطاقة ائتمان أو بطاقة خصم عادية**.
> لذلك استُبعد كل مسار يفرض بطاقة ائتمان.

---

## خلاصة سريعة

| الخيار | التكلفة | بطاقة مطلوبة |Stable URL | التقييم |
|---|---|---|---|---|
| **Render Free + Turso** | ٠ دج | ❌ لا | ✅ `*.onrender.com` | ⭐ **الأسهل والأسرع** — انظر [RENDER_TURSO.md](RENDER_TURSO.md) |
| **Cloudflare Workers + D1** | ٠ دج | ❌ لا | ✅ `*.workers.dev` | ⭐ دائم بلا نوم، لكن يحتاج إعادة كتابة الخادم |
| **Tailscale Funnel على جهازك** | ٠ دج | ❌ لا | ✅ `*.ts.net` | ⚡ يعمل اليوم فوراً، لكن جهازك يجب أن يبقى يعمل |
| **Vultr / RackNerd / Contabo (بـ USDT)** | ≈ ٤-٧ $ (٦٠٠-١٠٠٠ دج)/شهر | ❌ لا (عملة رقمية) | ✅ IP ثابت | 💰 **خادم حقيقي** بلا تعديل على الكود |
| Oracle Cloud | ٠ دج | ⚠️ **يرفض البطاقة الرقمية** | ✅ | ❌ غير متاح بدون بطاقة |
| AWS / GCP / Fly / Railway / Koyeb | — | ⚠️ تطلب بطاقة | ✅ | ❌ |

---

## ❌ لماذا لن تعمل Oracle Cloud مع RedotPay

نص Oracle الرسمي:

> We accept only **credit cards and debit cards that function like credit cards**.
> We do **not** accept … **virtual** … **prepaid** … cards.

بطاقة RedotPay **افتراضية (virtual)** ومموّلة برصيد عملة رقمية ⇒ تُرفض.

كما أن **PayPal الجزائر لا ينفع**: PayPal تسمح للجزائريين بالإرسال، لكن
> “You may use your **credit card** to send payments. You may not use your PayPal balance…”

أي أن رصيد PayPal وحده لا يكفي — يلزم ربط بطاقة، وبطاقة RedotPay قد تُرفض هناك أيضاً.

---

## ① المسار المجاني الدائم — Render + Turso

**بلا بطاقة، بلا عملة رقمية، ٠ دج.** التفاصيل خطوة بخطوة في
**[RENDER_TURSO.md](RENDER_TURSO.md)**.

لماذا يعمل هذا التطبيق عليه بلا تعديل على الكود؟
- `DB_DRIVER=libsql` ⇒ القاعدة في Turso عبر HTTP (لا حاجة لحزمة أصلية).
- `MEDIA_DRIVER=db` ⇒ لقطات الشاشة تُخزَّن داخل القاعدة على شكل أجزاء.
- القرص المؤقّت في Render يصبح بلا أثر: كل شيء دائم في Turso.

**حدود Turso المجانية:** ٥ غ.ب تخزين · ١٠٠ قاعدة · ٥٠٠ مليون قراءة/شهر ·
١٠ مليون كتابة/شهر — أكثر من احتياج هذا التطبيق.

**حدود Render المجانية:** ٥١٢ ميغا رام · ٧٥٠ ساعة/شهر · يتوقف بعد ١٥ دقيقة خمول
(تطبيق الهاتف يبقيه مستيقظاً بنبضة كل ١٢٠ ثانية).

---

## ② المسار المجاني الدائم — Cloudflare Workers + D1

حساب Cloudflare: **بريد + GitHub فقط، لا يطلب بطاقة أبداً**.

- D1 المجاني: ١ قاعدة (٥٠٠ ميغابايت) · ٥ غ.ب لكل الحساب · BLOB حتى ٢ ميغابايت/صف.
- WebSocket عبر Durable Objects.
- HTTPS تلقائي على `*.workers.dev`، وزمن استجابة ممتاز من الجزائر.

**المتطلّب:** إعادة كتابة طبقة الخادم (Express → Worker). لوحة التحكم وتطبيق
أندرويد يبقيان كما هما تماماً. **ليس منفّذاً حالياً** — مسار Render أبسط وأسرع.

---

## ③ المسار الأسرع — Tailscale Funnel على جهازك (يعمل اليوم)

```bash
# على جهازك الذي يشغّل السيرفر
tailscale up --advertise-tags=funnel
tailscale funnel --bg 3000
# → https://اسم-جهازك.اسم-شبكتك.ts.net
```

رابط **HTTPS دائم** بلا بطاقة وبلا كلفة. الشرط الوحيد: **جهازك يبقى يعمل ومتصلاً**.

---

## ④ الخادم الحقيقي بالدفع بالعملة الرقمية (RedotPay → USDT)

إن كان لديك رصيد **USDT** داخل RedotPay (أو ما يمكنك شراؤه من منصات جزائرية)،
فهذه المسارات تُشترى بالعملة الرقمية مباشرة بلا أي بطاقة:

| المزوّد | السعر | المواصفات | ملاحظات |
|---|---|---|---|
| **RackNerd** | ٣٫٤٩ $/شهر | ١ vCPU · ١ غ.ب · ٢٠ غ.ب | USDT (TRC20/ERC20) — بلا KYC |
| **Vultr** | ٥ $/شهر | ١ vCPU · ١ غ.ب · ٢٥ غ.ب · IPv4 | يقبل USDT + PayPal |
| **Vultr (IPv6 فقط)** | ٢٫٥٠ $/شهر | ١ vCPU · ٥١٢ م.ب · ١٠ غ.ب | بلا IPv4 — يحتاج تكييف |
| **BitLaunch** | ٥ $/شهر | ١ vCPU · ١ غ.ب · ٢٠ غ.ب | USDT بلا KYC |
| **Contabo** | ≈ ٧ $/شهر | ٤ vCPU · ٨ غ.ب · ١٥٠ غ.ب | يقبل USDT، رسوم تفعيل |

**تكديس التكلفة (TRC20):** رسوم الشبكة أقل من ٠٫٥٠ $ لكل دفعة.

**لماذا هذا المسار مفضّل إن توفّر الرصيد:** هو **الطريق الوحيد** الذي يمنحك
خادماً حقيقياً بعنوان IP ثابت وقرص دائم — **بلا أي تعديل على الكود**:
```bash
./deploy/upload.sh            # يرفع المشروع إلى الخادم
ssh <server> 'sudo ./deploy/setup.sh'   # يثبّت Node + Nginx + systemd
```

> ⚠️ **عملة TRC20 وليس ERC20** — رسوم الشبكة أقل بكثير.

---

## ⑤ ما الذي نختاره؟

```
                    ┌─ هل لديك رصيد USDT (≈ ٥ $)؟
                    │
        نعم ───────┴─────── لا
         │                    │
         ▼                    ▼
   Vultr / RackNerd        Render + Turso
   (خادم حقيقي، ٠ تعديل)   (٠ دج، ٠ بطاقة)
```

المساران يعملان مع **نفس الكود** — التبديل بثلاثة متغيّرات بيئة فقط.

---

## بدائل الـ HTTPS المجاني (مهمّ)

### أ) Cloudflare Tunnel — الأسهل (بلا دومين)

```bash
# رابط فوري (يتغيّر عند إعادة التشغيل) — للتجربة السريعة
cloudflared tunnel --url http://localhost:3000

# أو نفق دائم (حساب Cloudflare مجاني + دومين)
cloudflared tunnel login
cloudflared tunnel create guardian
cloudflared tunnel route dns guardian app.yourdomain.com
cloudflared tunnel run guardian
```
✅ HTTPS تلقائي · ✅ يخفي IP السيرفر · ✅ مجاني بالكامل

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

> **تذكير:** الاستخدام مشروع فقط على أجهزة **أبنائك** وموافقتهم، وبإفصاح واضح
> للطفل بأن المراقبة مفعّلة (التطبيق يُظهر إشعاراً دائماً — وهذا مقصود).
