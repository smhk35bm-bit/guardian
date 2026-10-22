# النشر المجاني بلا بطاقة — Render + Turso

> **هذا المسار لا يحتاج أي بطاقة بنكية ولا أي وسيلة دفع.**  
> الحسابات تُفتح ببريد + GitHub فقط. التكلفة **٠ دج** ومáltهم.

---

## لماذا هذا المسار؟

كل الخدمات المجانية تقريباً (Render، Cloudflare، Koyeb…) تمسح قرصها عند كل نشر.
لذلك نقلنا **قاعدة البيانات والوسائط** إلى قاعدة بعيدة (Turso) عبر HTTP:

| الجزء | مكانه على Render | مكانه على VPS عادي |
|---|---|---|
| قاعدة البيانات | Turso (عبر HTTP) | ملف `data/guardian.db` |
| لقطات الشاشة والصور | قاعدة البيانات على شكل أجزاء | مجلد `uploads/` |
| لوحة التحكم (HTML/CSS/JS) | من الخادم نفسه | من الخادم نفسه |

التبديل يتم بمتغيّرات بيئة واحدة — لا حاجة لتعديل أي سطر كود:

```
DB_DRIVER=libsql      MEDIA_DRIVER=db
```

---

## الخطوة ١ — إنشاء قاعدة Turso (بلا بطاقة)

1. سجّل في <https://turso.tech> عبر **GitHub** أو Google (بلا بطاقة).
2. ثبّت أداة Turso:

   ```bash
   curl -sSfL https://get.tur.so/install.sh | bash
   export PATH="$HOME/.turso:$PATH"
   turso auth login          # يفتح المتصفح — سجّل دخول بـ GitHub
   ```

3. أنشئ قاعدة بيانات:

   ```bash
   turso db create guardian
   ```

4. أنشئ **توكناً** لهذه القاعدة واحتفظ به (يظهر مرة واحدة):

   ```bash
   turso db tokens create guardian
   # → اسطر طويل يبدأ بـ eyJ...
   ```

5. احصل على رابط القاعدة:

   ```bash
   turso db show guardian --http-url
   # → https://guardian-<اسم-المنظمة>.turso.io
   ```

   ✅ هذه هي القيمة التي ستضعها في `LIBSQL_URL`.
   ⚠️ استخدم الرابط الذي يبدأ بـ `https://` وليس `libsql://`.

> الحساب المجاني في Turso: **٥ غيغابايت** تخزين + **١٠٠** قاعدة + حدود سخّاء
> للقراءة والكتابة — أكثر منenough لهذا التطبيق.

---

## الخطوة ٢ — رفع المشروع إلى GitHub

المستودع عندنا محلي (بلا remote). أنشئ مستودعاً جديداً:

```bash
cd /home/hako/parental-guardian
git remote add origin https://github.com/<اسم-حسابك>/guardian.git
git push -u origin main
```

> المستودع **لا يحتوي** على أي سر: ملف `.env` ومجلدات `data/` و `uploads/` مستثناة في `.gitignore` ✓

---

## الخطوة ٣ — النشر على Render

1. افتح <https://dashboard.render.com> وسجّل دخولك (Google/GitHub — **بلا بطاقة**).
2. **New → Blueprint** → اختر مستودع `guardian` → Render يقرأ `render.yaml` تلقائياً.
3. سيطلب منك تعبئة القيم التي وُضعت `sync: false`:

   | المتغيّر | القيمة |
   |---|---|
   | `LIBSQL_URL` | رابط Turso من الخطوة ١ (يبدأ بـ `https://`) |
   | `LIBSQL_TOKEN` | التوكن الطويل من الخطوة ١ |

   (`JWT_SECRET` يُولّده Render تلقائياً ✓ — و`PUBLIC_URL` لم يعد مطلوباً:
   يستنتج الخادم رابطه من الطلب نفسه.)

4. اضغط **Apply** → انتظر انتهاء البناء (دقيقة أو دقيقتان).
5. الرابط النهائي يظهر أعلى صفحة الخدمة في Render. لو كان الاسم محجوزاً
   سيضيف Render لاحقة، مثل: `https://guardian-server-nugk.onrender.com` — جرّبه:

   ```bash
   curl https://guardian-server-nugk.onrender.com/api/health
   # {"ok":true,"data":{"status":"healthy",...}}
   ```

6. لا حاجة لأي خطوة إضافية — التطبيق يعرف رابطه تلقائياً.

---

## الخطوة ٤ — إنشاء حساب ولي الأمر

افتح رابط خدمتك في Render (مثل `https://guardian-server-nugk.onrender.com`) في المتصفح، سجّل حساباً (بريد + كلمة مرور)،
ثم من تبويب **الأجهزة** اضغط **كود تسجيل** وأدخل الرمز في تطبيق الهاتف.

---

## ملاحظات مهمّة على Render المجاني

| الموضوع | التفصيل |
|---|---|
| **النوم بعد 15 دقيقة** | Render يوقف الخدمة إذا لم تصل أي طلب. تطبيق الهاتف يرسل نبضة كل **120 ثانية** → الخدمة تبقى مستيقظة ما دام الهاتف مسجّلاً ومتصلاً. |
| **750 ساعة/شهر** |Enough لخدمة واحدة تعمل 24/24 (720 ساعة). |
| **القرص مؤقّت** | سبب اختيار Turso — لا loses أي بيانات. |
| **إعادة النشر** | تُمسح الملفات المؤقتة فقط؛ قاعدة Turso تبقى سليمة ✓ |
| **أول طلب بعد النوم** | يستغرق ~30 ثانية — طبيعي. |
| **WebSocket** | مدعوم ✓ (التحديث اللحظي للموقع يعمل) |

---

## بديل Docker (أي مزوّد حاويات)

```bash
cd server
docker build -t guardian .
docker run -d --name guardian -p 3000:3000 \
  -e DB_DRIVER=libsql -e MEDIA_DRIVER=db \
  -e LIBSQL_URL="https://..." -e LIBSQL_TOKEN="..." \
  -e JWT_SECRET="$(openssl rand -base64 48)" \
  guardian
```

---

## النسخ الاحتياطي لقاعدة Turso

```bash
turso db shell guardian < backup.sql     # من الجهاز
turso db dump guardian --output backup.sql
```

أو من داخل الخادم (يستخدم نفس الـ API):

```bash
curl -s -X POST "$LIBSQL_URL/v1/dump" -H "Authorization: Bearer $LIBSQL_TOKEN" -o backup.sql
```

---

> **تذكير أخلاقي:** الاستخدام مشروع فقط على أجهزة **أبنائك** وبموافقتهم، مع إبلاغ الطفل
> بأن المراقبة مفعّلة — التطبيق يُظهر إشعاراً دائماً ولا يمكن إخفاؤه (هذا مقصود).
