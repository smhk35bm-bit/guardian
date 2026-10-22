# Guardian — تطبيق الأندرويد (جانب الطفل)

تطبيق أندرويد يجمع بيانات الجهاز ويرسلها إلى خادم **Guardian Parental Control**
(لوحة تحكم الأهل) وفق العقد `docs/API_CONTRACT.md`.

> **هذا التطبيق لا يختبئ ولا يتخفّى.**
> يعمل كـ **خدمة أمامية دائمة (Foreground Service)** مع إشعار دائم غير قابل للتجاهل
> يوضّح في شريط الحالة أن المراقبة نشطة، واسم التطبيق في قائمة التطبيقات يذكر صراحةً
> أنه تطبيق مراقبة. لا يوجد في الكود أي أداة لإخفاء التطبيق أو أيقونته أو آثاره،
> ولا صلاحيات Device Admin، ولا تعتيم على الكود.

---

## 1. ما الذي يفعله التطبيق؟

| المُجمِّع | البيانات | المسار في الخادم |
|---|---|---|
| `location` | الموقع الحالي (GPS/الشبكة) مع الدقة والسرعة والارتفاع | `POST /api/devices/:id/location` |
| `usage` | مدة استخدام كل تطبيق + التطبيق الأمامي الحالي | `POST /api/devices/:id/events` |
| `apps` | قائمة التطبيقات المثبتة وإصداراتها | `POST /api/devices/:id/apps` |
| `calls` | سجلّ المكالمات (واردة/صادرة/مفقودة) | `POST /api/devices/:id/calls` |
| `sms` | الرسائل النصية (واردة/صادرة) | `POST /api/devices/:id/sms` |
| `screenshot` | لقطة شاشة (بموافقة صريحة من المستخدم في كل جلسة) | `POST /api/devices/:id/screenshots` (multipart) |
| `camera` | صورة واحدة بالكاميرا (معطّل افتراضيًا) | `POST /api/devices/:id/photos` (multipart) |
| `heartbeat` | البطارية، الشحن، الشبكة، المساحة الحرة، مدة التشغيل | `POST /api/devices/:id/heartbeat` |

---

## 2. المتطلبات

| العنصر | القيمة |
|---|---|
| JDK | 17 |
| Android Gradle Plugin | 8.5.2 |
| Gradle | 8.7 |
| Kotlin | 1.9.24 |
| compileSdk / targetSdk | 34 |
| minSdk | 26 (أندرويد 8.0) |
| حزمة التطبيق | `com.guardian.parent` |
| الاعتماديات | AndroidX + Material + OkHttp + `org.json` + `play-services-location:21.0.1` |

لا يُستخدم Retrofit ولا Moshi ولا Gson ولا kotlinx-serialization؛ طبقة الشبكة مكتوبة
يدويًا فوق OkHttp، والتحليل يتم عبر `org.json` المدمجة في أندرويد.

### بناء المشروع

1. ثبّت **Android Studio Hedgehog (2023.1.x)** أو أحدث، ومعه SDK 34 وBuild-Tools 34.
2. افتح مجلد `android/` من Android Studio (اختر **Open** لا **Import from Gradle**).
3. اترك Gradle يستخدم **Gradle JDK 17** (`Settings → Build, Execution, Deployment → Build Tools → Gradle JDK`).
4. شغّل على جهاز أو محاكي:

```
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> **ملاحظة:** الملف `gradle/wrapper/gradle-wrapper.properties` موجود (Gradle 8.7)،
> لكنّ ملفَّي `gradlew` و `gradle/wrapper/gradle-wrapper.jar` غير مضومنين في المستودع.
> عند فتح المشروع في Android Studio سيُنشئ الـ wrapper تلقائيًا؛ وإن أردت البناء من
> سطر الأوامر فاستخدم Gradle 8.7 مثبَّتًا مباشرةً:
>
> ```
> gradle :app:assembleDebug
> ```

5. أذونات وقت التشغيل والأذونات الخاصة تُمنح من داخل التطبيق (شاشة «الأذونات»)،
   ولا يمكن لأي توزيعة APK تجاوزها.

---

## 3. خطوات التشغيل

### 3.1 توليد كود الربط (من لوحة الأهل)

في لوحة تحكم الأهل، زر «إنشاء كود ربط» يستدعي:

```
POST /api/devices/enroll-code      (ترويسة: Authorization: Bearer <parent-token>)
→ { "ok": true, "data": { "code": "48130277", "expiresAt": "..." } }
```

الكود مكوّن من **8 أرقام** وصلاحيته **10 دقائق** ويُستهلك مرة واحدة.

### 3.2 ربط الجهاز

1. افتح التطبيق (المسار: `Guardian — المراقبة العائلية`).
2. في خانة **«عنوان خادم وليّ الأمر»** اكتب عنوان لوحة الأهل، مثل:
   * `https://vps.example.com/api`
   * أو `https://vps.example.com` ← يُضاف `/api` تلقائيًا
   * أو `https://vps.example.com/api/` ← تُحذف الشرطة المائلة وتُوحَّد
3. في خانة **«كود الربط (8 أرقام)»** أدخل الكود الذي أعطاه الأهل.
4. اضغط **«اربط الجهاز»**.

عند النجاح يعرض الخادم `deviceId` و`deviceToken`، ويُخزَّنان محليًا في
`SharedPreferences` باسم `guardian_prefs`، وتبدأ خدمة المراقبة فورًا.

> الترويسة المستخدمة في كل الطلبات بعد الربط:
> `Authorization: Device <deviceToken>` (و`Authorization: Bearer <token>` بديل مقبول للخادم).

### 3.3 منح الأذونات

الضغط على **«الأذونات»** من الشاشة الرئيسية يفتح قائمة مفصّلة، وكل صف يشرح
لماذا يُطلب الإذن وأين يُمنح:

| الإذن | لماذا؟ | مكان المنح |
|---|---|---|
| `POST_NOTIFICATIONS` | لإظهار الإشعار الدائم الذي يثبت أن المراقبة نشطة | نافذة النظام (أندرويد 13+) |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | تحديد موقع الجهاز | نافذة النظام |
| `ACCESS_BACKGROUND_LOCATION` | الاستمرار في تحديد الموقع عندما لا يكون التطبيق في المقدمة | نافذة النظام (يجب اختيار «السماح دائمًا») |
| `PACKAGE_USAGE_STATS` | قراءة مدة استخدام التطبيقات | إعدادات النظام ← «الوصول إلى بيانات الاستخدام» (إذن خاص، لا نافذة منبثقة) |
| `READ_CALL_LOG` | قراءة سجلّ المكالمات | نافذة النظام |
| `READ_SMS` | قراءة الرسائل النصية | نافذة النظام |
| `READ_CONTACTS` | إظهار اسم صاحب الرقم في سجلّ المكالمات | نافذة النظام |
| `CAMERA` | التقاط صورة واحدة عند تفعيل مُجمِّع الكاميرا | نافذة النظام |
| MediaProjection | التقاط الشاشة | **نافذة موافقة صريحة من النظام في كل جلسة** |

> الكاميرا واللقطة **معطّلتان افتراضيًا** ولا يمكن تشغيلهما إلا من داخل التطبيق
> بعد موافقة المستخدم. التقاط الشاشة يتطلّب موافقة نظام في كل جلسة، وعند إيقاف
> الموافقة يتوقّف الالتقاط فورًا.

### 3.4 الإعدادات

من **«الإعدادات»** يمكن تعديل:

* عنوان الخادم (يقبل مع `/api` أو بدونها).
* اسم الجهاز الذي يظهر في لوحة الأهل.
* تفعيل/تعطيل كل مُجمِّع، والفاصل الزمني بالثواني لكل مُجمِّع.
* **كاميرا الجهاز**: مفتاح منفصل، مطفأ افتراضيًا، يمكن إيقافه فورًا.
* **حذف قائمة الانتظار** المحلية.
* **إلغاء الربط**: يوقف الخدمة، يلغي المهمة الدورية، يحذف التوكن وقائمة الانتظار، ويعيدك إلى شاشة الربط.

---

## 4. ضمانات الشفافية (التزام أخلاقي)

| الضمانة | كيف نُفّذت |
|---|---|
| إشعار دائم غير قابل للإخفاء | `Notifications.build()` يستخدم `setOngoing(true)` على قناة `guardian_monitoring` بأهمية `IMPORTANCE_LOW` (بلا صوت ولا اهتزاز). لا يوجد أي مسار في الكود يلغي الإشعار. |
| مؤشّر بصري دائم | أيقونة الدرع `ic_stat_guardian` في إشعار الخدمة الأمامية. |
| تسميات صريحة | `app_name = "Guardian — المراقبة العائلية"`؛ ولا يوجد أي كود لإخفاء الأيقونة أو تغيير مُكوِّنات التطبيق. |
| إبلاغ صريح عند التسجيل | شاشة الربط تعرض نصًا كاملًا لما يُجمع ولماذا، وكيف يُوقف المستخدم كل شيء. |
| موافقة النظام للشاشة | `MediaProjectionHolder` لا ينشئ إسقاطًا إلا بعد موافقة النظام في الجلسة الحالية، ويمكن سحبها من الإشعار الدائم في أي وقت. |
| لا توافق تلقائي | لا توجد `AccessibilityService` ولا أي آلية تضغط على نوافذ النظام بالنيابة عن المستخدم. |
| البث المباشر معلن | بث الكاميرا يبدأ من لوحة الأب بعد منح إذن الكاميرا مرة واحدة، وبث الشاشة يعمل ضمن موافقة MediaProjection الظاهرة في شريط الحالة. |
| إمكانية الإيقاف الكامل | زر «إلغاء الربط» يوقف كل شيء ويمسح التوكن. |
| لا تعتيم | `isMinifyEnabled = false`، ولا ProGuard ولا Any 或许 obfuscation. |

---

## 5. بنية المشروع

```
android/
├── build.gradle.kts                 # AGP 8.5.2 + Kotlin 1.9.24 (apply false)
├── settings.gradle.kts              # google() + mavenCentral()
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts             # compileSdk 34، minSdk 26، JDK 17
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml       # الأذونات، <queries>، أنواع الخدمة الأمامية
        ├── java/com/guardian/parent/
        │   ├── GuardianApp.kt
        │   ├── core/                # Config, Logger, Json, TimeFmt, Notifications, Permissions
        │   ├── data/                # Models, ApiClient, LocalStore, Repo
        │   ├── net/DeviceSocket.kt  # WebSocket دائم: أوامر + إطارات JPEG
        │   ├── service/
        │   │   ├── MonitorService.kt    # الخدمة الأمامية + المجدول + أنواع FGS
        │   │   ├── UploadWorker.kt      # شبكة أمان دورية (WorkManager)
        │   │   ├── BootReceiver.kt      # إعادة التشغيل بعد إعادة تشغيل الهاتف
        │   │   ├── MediaProjectionHolder.kt  # جسر موافقة التقاط الشاشة
        │   │   ├── capture/ScreenCaptureSource.kt  # VirtualDisplay واحد + JPEG
        │   │   ├── live/LiveStreamManager.kt       # بث الشاشة/الكاميرا
        │   │   └── collectors/       # المُجمِّعات الثمانية
        │   └── ui/                  # Enrollment, Main, Settings, Permission + محوّلات RecyclerView
        └── res/
            ├── layout/              # 6 تخطيطات
            ├── values/strings.xml   # العربية (الافتراضي)
            ├── values-en/strings.xml # الإنجليزية
            ├── values/{colors,themes}.xml
            ├── drawable/ mipmap-anydpi-v26/ xml/
```

### تدفّق العمل

1. `EnrollmentActivity` ← `Repo.enroll()` ← `ApiClient.register()` ← `POST /api/devices/register`.
2. عند نجاح الربط: `MonitorService.start()` ← `startForeground()` + جدول المجمّعات.
3. كل 5 ثوانٍ يفحص `MonitorService` الجدول ويشغّل المُجمّعات التي حان وقتها.
4. `Repo.submit()` يكتب النتائج في **قائمة انتظار محلية** ثم يرسلها فورًا.
5. `UploadWorker` (كل 15 دقيقة، بأفضلية أسّية) يعيد محاولة ما تبقّى.

---

## 6. تفاصيل التواصل مع الخادم

| العنصر | القيمة |
|---|---|
| Base URL | يُقبل مع `/api` أو بدونها؛ يُوحَّد تلقائيًا إلى `…/api` (أي مقطع مسار يساوي `api` يُعتبر مقطعًا نهائيًا) |
| ترويسة المصادقة | `Authorization: Device <deviceToken>` |
| نوع المحتوى | `application/json; charset=utf-8` للـ JSON، و`multipart/form-data` للوسائط |
| حقول multipart | `file` = الملف الثنائي، `meta` = نص JSON اختياري |
| المسارات | `POST /api/devices/{deviceId}/{heartbeat\|location\|events\|calls\|sms\|screenshots\|photos\|apps}` |
| WebSocket | `wss://<host>/ws?token=<deviceToken>` — نفس توكن الجهاز، إعادة اتصال تلقائية بتباعد أسّي (2s…60s) |
| البث | إطارات JPEG ثنائية، رأس 12 بايت، إطار كل 250ms، عرض ≤720px |
| نموذج الخطأ | `{ "ok": false, "error": { "code": "…", "message": "…" } }` — يُعرض `error.message` للمستخدم كما هو |
| تقييد المعدل | 600 طلب/دقيقة لكل جهاز (الحد الأدنى للفواصل 15 ثانية للموقع و60 ثانية لغيره) |
| رمز التوكن | صالح سنة (365 يومًا) |
| أقصى حجم ملف | التطبيق يحدّه بـ 4 ميغابايت (الخادم يسمح بـ 10 ميغابايت) |
| الاستجابة | كل الردود `data` تُغلَّف في `{ "ok": true, "data": … }` |

**التوقيت:** كل الطوابع الزمنية تُرسَل كأرقام **epoch-millis** (ملي ثانية).
الخادم يحوّلها عبر `isoOrNow()` إلى ISO-8601.

**الإعدادات عن بُعد:** ردّ `heartbeat` قد يحمل `data.config` تحتوي على
`locationIntervalSec` و`usageIntervalSec`، ويطبّقها التطبيق فورًا على الجدول المحلي.

**قائمة الانتظار المحلية:** تحفظ في `SharedPreferences` (مفتاح `pending_queue`) مع
الملفات في `filesDir/pending_media`. الحد الأقصى 200 عنصر؛ عند التجاوز تُسقَط
أقدم العناصر **مع حذف ملفاتها** من القرص. الردود 4xx تُسقَط العنصر (لن يُقبل من
الخادم أبدًا)، وأخطاء الشبكة و401/403 تُبقيه لإعادة المحاولة.

---

## 7. ملاحظات أندرويد 14 (API 34)

* الخدمة الأمامية تُعلن `android:foregroundServiceType="location|dataSync|camera|mediaProjection"`،
  والأذونات المقابلة `FOREGROUND_SERVICE_*` موجودة في المانيفست.
* `MonitorService.currentTypes()` يحسب الأنواع ديناميكيًا: `dataSync` دائمًا،
  و`location` فقط ما دام إذن الموقع ممنوحًا، و`camera`/`mediaProjection` فقط عند
  الاستخدام الفعلي والموافقة عليها. يُعاد استدعاء `ServiceCompat.startForeground(...)`
  لتحديث الأنواع، مع الرجوع إلى `dataSync` عند رفض النظام للنوع الجديد.
* **منذ أندرويد 14** لا يجوز إنشاء `MediaProjection` إلا وأنت داخل خدمة أمامية من نوع
  `mediaProjection`؛ لذلك نقلنا إنشاء الإسقاط من `PermissionActivity` إلى
  `MonitorService.attachProjection()`، وتخزّن الشاشة الموافقة فقط في
  `MediaProjectionHolder` ثم تطلب من الخدمة استلامها.
* **منذ أندرويد 14** يسمح بـ `VirtualDisplay` واحد فقط لكل `MediaProjection`؛ لذلك
  `ScreenshotCollector` ينشئ القارئ والشاشة مرة واحدة لكل جلسة موافقة ويعيد استخدامهما.
* استقبال بثّ البطارية يتم عبر `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`
  كما يشترط أندرويد 14.
* تطبيقات أندرويد 13+ يجب أن تمنح `POST_NOTIFICATIONS`، وإن لم تُمنح يبقى الإشعار
  الدائم غير مرئي — لذلك التطبيق يطلب الإذن صراحةً في شاشة «الأذونات».

---

## 8. القيود المعروفة

1. **لم يُبنَ المشروع فعليًا** في بيئة التطوير الحالية (لا يوجد Android SDK ولا
   Gradle ولا اتصال بالشبكة). تم التحقق من correctness عبر **تحليل ثابت** فقط:
   فحص مراجع الموارد (`R.id` / `R.string` / `@drawable`)، تطابق المانيفست مع أصناف
   Kotlin، تطابق الحزم، وتطابق الاستدعاءات مع توقيعات المكتبات المُنزّلة (بما فيها
   `play-services-location` و`androidx.core`).
2. **ملفات الـ wrapper ناقصة**: `gradlew` و`gradle-wrapper.jar` غير موجودين (انظر §2).
3. **WebSocket موجود** (`net/DeviceSocket.kt`) ويُستخدم للبث المباشر وأوامر
   `live.start`/`live.stop`. إشعار `config.updated` لا يزال غير مُطبّق؛ الفواصل
   عن بُعد تصل مع `heartbeat` فقط.
4. **سجلّ المتصفح والإشعارات** (`browser_history`, `notification`) نوعان معرّفان في
   العقد لكن التطبيق لا يجمعهما (مصدران غير متاحين بدون أذونات خاصة إضافية).
5. **الكاميرا**: صورة واحدة كل دورة للجمع الدوري، و**بث مباشر** (MJPEG) عند طلب
   الأب. لا يوجد تسجيل فيديو H.264/MediaCodec — الإطارات JPEG لتوافق أوسع على 4G.
   بث الشاشة يحتاج موافقة MediaProjection؛ بعض أجهزة سامسونج تطلب كلمة مرور حساب
   سامسونج لمشاركة الشاشة، ولا يحاول التطبيق تجاوزها.
6. **حدّ حجم الوسائط 4 ميغابايت** (أقلّ من حدّ الخادم 10 ميغابايت) كاحتياط على
   استهلاك الذاكرة؛ الصور الأكبر تُسقَط وتُسجَّل في السجل.
7. **فاصل WorkManager الأدنى 15 دقيقة**؛ في وضع «توفير البطارية» قد تتأخّر خدمة
   الاستعادة، لكنّ الخدمة الأمامية نفسها ترفع البيانات فور إتمام الجمع.
8. **سياسة Google Play**: استخدام نوع `camera` في خدمة أمامية يتطلّب تقديم مبرّر
   لمركز policies؛ كما أنّ التطبيق مخصّص للأهل مع موافقتهم وليس تطبيق مراقبة
   تجاريًا. النشر على Play قد يحتاج تعهدات إضافية.
9. **وضع «الوصول إلى الاستخدام»** قد يُمنح لكل تطبيق على حدة؛ بعض الأجهزة
   المصنّعة تمنعه تمامًا (سيبقى مُجمِّع `usage` فارغًا).
10. **الأذونات الخاصة** (`PACKAGE_USAGE_STATS`) وإذن الخلفية لا يمكن منحهما من
    داخل التطبيق، فيحتاج المستخدم إلى الانتقال يدويًا إلى الإعدادات.

---

## 9. سجلّ الأخطاء

كل السجلات تُكتب عبر `android.util.Log` بالوسم `Guardian`:

```bash
adb logcat -s Guardian
```
