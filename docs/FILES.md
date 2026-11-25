# الملفات الداخلية: الأصلي ← Ayman Tunnel Plus

> Comparison of the original app's internal files (storage + embedded
> data) with this app's improved design. Facts about the original come
> from the static reverse-engineering report (`~/workspace/hamo-rev/`).
> The rule throughout: **improve the original's files, never copy
> them** — clean-room throughout.

## 1. Server storage

| الأصلي | Ayman Tunnel Plus | الحالة |
|---|---|---|
| 7 مخازن MMKV: MAIN / SETTING / SUB / ASSET / PROFILE_FULL_CONFIG / SERVER_RAW / SERVER_AFF، بمفاتيح مشفّرة ودوال encode/decode يدوية | قاعدة Room واحدة (`tunnel.db`) بمخطط مكتوب (typed schema): `ProfileEntity` + `ProfileDao` + ترحيل حقيقي `MIGRATION_1_2` | ✅ v0.1 |
| تصدير/استيراد عبر ملفات `.hat` مشفرة | تصدير/استيراد JSON نظيف (`ProfileBackup`: اسم/نوع/سيرفر/منفذ/رمز الدولة/outbound) — مقروء وقابل لإعادة الاستيراد | ✅ v0.1 |
| نسخ احتياطي بمجلد `backups/` + FileDialog يتصفح التخزين الخارجي | نسخة احتياطية يومية تلقائية (WorkManager) في `filesDir/backups/` الخاص بالتطبيق (آخر 7 نسخ) + تصدير يدوي عبر SAF (المستخدم يختار المكان) | ✅ v0.1 |
| — | تشفير اختياري لقاعدة البيانات (SQLCipher) | ⏳ المرحلة 2 (الحماية الحالية: التخزين الخاص بالتطبيق + `allowBackup=false`) |

## 2. Embedded data files: replacement, not deletion

| الأصلي | Ayman Tunnel Plus | الحالة |
|---|---|---|
| `geoip.dat` (15.3 MB) + `geosite.dat` (2.2 MB) مدمجة وقديمة | **لا ملفات مدمجة إطلاقًا** — rule-sets صغيرة (`.srs`) تُنزَّل من sing-geoip / sing-geosite عند أول تشغيل | ✅ v0.1 |
| القواعد المعتمدة عليها (تجاوز دولة، حظر إعلانات، توجيه نطاقات) تعمل ببيانات قديمة | حظر الإعلانات يعمل بقائمة `geosite-category-ads-all.srs` محدّثة (8,175 بايت — تحققنا من الرابط والحجم 2026-11-23)؛ تجاوز الدولة وتوجيه النطاقات بنفس النمط في المرحلة 2 | ✅ v0.1 (الإعلانات)، ⏳ الباقي |

### The mandatory fallback (user rule, 2026-11-23)

**الإزالة استبدال لا حذف.** وإن لم تكن البيانات موجودة وقت التشغيل
(بلا إنترنت عند أول تشغيل مثلًا)، التطبيق **لا يعطّل ولا يفشل** — بل
يرجع للوضع الافتراضي الآمن (كل الترافيك عبر النفق) مع تنبيه في
الواجهة أن بيانات التوجيه غير محدّثة، ويعيد المحاولة عند توفر الشبكة.

التنفيذ (`core/data/.../routing/`):
- `RoutingDataManager`: حالة الملف (`NOT_DOWNLOADED` / `READY` /
  `FAILED`) + تنزيل عند الحاجة + إعادة تنزيل للملفات الأقدم من 30 يومًا.
  التنزيل الفاشل لا يحذف أبدًا ملفًا قديمًا شغالًا.
- `RoutingFallback.decide()`: القاعدة نفسها كدالة نقية — **مختبرة
  بوحدة** (`RoutingFallbackTest`): البيانات جاهزة ← القاعدة تُستخدم؛
  غير جاهزة ← القاعدة تُحذف من الكونفيج، والتنبيه يظهر، والوضع الآمن
  يبقى.
- `SingBoxConfigBuilder`: قاعدة الإعلانات تُبنى فقط إن كان الملف
  موجودًا فعلًا على القرص (مختبر بوحدة: غياب الملف ← كونفيج سليم
  بلا القاعدة).
- الواجهة: بطاقة الإعدادات تعرض حالة البيانات + زر «إعادة محاولة
  التنزيل»؛ ومحاولة تنزيل تلقائية في الخلفية عند فتح التطبيق.

## 3. Routing lists (the four templates)

| الأصلي | عندنا | الحالة |
|---|---|---|
| 4 قوالب JSON: global / white / black / white_iran | v0.1: global فقط. الأوضاع الأربعة كقواعد sing-box + rule-sets (التخطيط في `docs/CONNECTIONS.md` §4) | ⏳ المرحلة 2 |
| قائمة 241 حزمة للبروكسي الانتقائي (`proxy_packagename.txt`, ملف ثابت) | بلا ملف ثابت؛ اختيار لكل تطبيق من الواجهة (السباكة موجودة: `OverrideOptions` + `PlatformInterface`) | ⏳ المرحلة 2 |

## 4. Default config template

| الأصلي | عندنا |
|---|---|
| `assets/v2ray_config.json`: قالب افتراضي يحوي بيانات دخول تجريبية مدمجة | **لا قالب افتراضي إطلاقًا** — لا ملف إعدادات مدمج، ولا أي بيانات دخول في التطبيق، ولا اشتراك افتراضي من أي نوع — لا رابط ولا قالب. الخوادم تأتي من استيراد المستخدم فقط (روابط أو اشتراكات يضيفها بنفسه) |
| القائمتان الافتراضيتان المدمجتان (23 سيرفرًا + 195 شبكة مجانية) بأسماء ومضيفين وSNI | **بيانات مرجعية فقط، بلا اتصال وبلا بيانات دخول** — نفس الحقول المعقّمة (الاسم/المضيف/المنفذ/البروتوكول/SNI) في جدولين منفصلين بـRoom (`referenceServers` + `referenceNetworks`، تُملآن مرة واحدة بـ`HamoReferenceSeeder`)؛ ليست خوادم اتصال إطلاقًا ولا تظهر كخوادم شغالة — للمرحلة 2 فقط (ZIVPN + مولّد الحمولات) |

## 5. External binaries

| الأصلي | عندنا |
|---|---|
| ثنائيات منسوخة في filesDir: libdns / libtun2socks / libpdnsd كعمليات خارجية | **بلا ثنائيات خارجية في v0.1** — كل شيء مدمج في sing-box/libbox (مكتبة AAR نظامية). مساعدو المرحلة 2 (ZIVPN/DNSTT/OpenVPN) يُضافون كمكتبات أصلية نظامية، لا ملفات منسوخة |

## 6. Country flags

| الأصلي | عندنا | الحالة |
|---|---|---|
| 206 صورة علم في `assets/flags/` | **إيموجي علم** من رمز دولة ISO (حرفان) يضعه المستخدم عند الاستيراد، أو يُملأ لاحقًا من بيانات GeoIP دون اتصال — بلا أي ملفات صور | ✅ v0.1 |
| — | القاعدة: لا نخمّن الدولة أبدًا من اسم المضيف أو الـIP — العلم يظهر فقط إن كان الرمز معروفًا، وإلا لا يظهر شيء | ✅ v0.1 |

## 7. Footprint (storage + RAM) — design goals

Target phone: budget (the user's Infinix SMART 5 is low-end), so a
light footprint is a priority, not cosmetics.

| الأصلي | الهدف في v0.1 | التنفيذ |
|---|---|---|
| ~60 MB XAPK؛ 10 مكتبات أصلية ~80 MB في شريحة arm64 | APK صغير: نواة sing-box واحدة | R8/minify + shrinkResources مفعّلة؛ شريحة arm64-v8a فقط؛ بلا ملفات geoip مدمجة (17.5 MB وُفّرت) |
| V2Ray في عملية `:RunSoLibV2RayDaemon` + tun2socks + pdnsd + Hysteria كعمليات خارجية | **عملية واحدة** | لا `android:process` في أي مكوّن؛ الخدمة الأمامية الوحيدة هي خدمة الـVPN نفسها (النسخ الاحتياطي عامل WorkManager قصير العمر، لا خدمة) |
| ملفات بيانات ضخمة تُحمَّل في الذاكرة | لا تحميل لبيانات التوجيه كلها دفعة واحدة | v0.1 بلا rule-sets ضخمة أصلًا؛ ملف الإعلانات 7.7 KB ويُقرأ من القرص عند بناء الكونفيج فقط (المرحلة 2: الالتزام نفسه — البث لا التحميل الكامل) |
| الملفات الشخصية في MMKV مبعثرة | الملفات الشخصية في Room (كيلوبايتات) | ✅ |
| سجلات بلا حدود ظاهرة | سجلات محدودة وتُدوَّر | `TunnelEventLog`: آخر 100 حدث في الذاكرة فقط؛ سجل sing-box الداخلي محدود بـ`logMaxLines = 1000`؛ بلا ملف سجل على الهاتف |

### Measured numbers (honest)

**لا أرقام مقاسة بعد.** الأرقام الحقيقية (حجم APK، وحجم التثبيت،
والرام أثناء الاتصال، ومساحة بيانات التطبيق) تُقاس من الـAPK المبني
فعليًا عندما يبنيه GitHub Actions، بأداة القياس
`scripts/measure-footprint.sh` — وتُسجَّل هنا حينها. لا يُدَّعى أي
رقم لم يُقَس.

## 8. وضع «نت مجاني» (SNI + نفق SSH)

| الموضوع | التفصيل |
|---|---|
| الطريقة | الحقن عبر SNI: اتصال TLS باسم مضيف مجاني (zero-rated) في الـSNI ← SSH داخل الـTLS ← تحويل SOCKS محلي (`127.0.0.1:10810`) ← sing-box ← النفق. المسار العادي للبروفايلات لم يُمس إطلاقًا |
| الملفات | 11 ملفًا جديدًا في `feature/connection/.../injector/` (عميل SSH خفيف JSch ‏2.28.6 + مصنع مقابس TLS بالـSNI + مستودع المرشحين + الفحص والتوثيق) + تعديلات `TunnelVpnService.kt` و`LastSessionStore.kt` و`MainActivity.kt`؛ وزر الدخول في شاشة الخوادم (`ServersScreen.kt`: معامل `onOpenFreeInternet`) |
| البيانات | 127 شبكة مرشحة من الجدول المرجعي `referenceNetworks` (tunnelType == 3 + SNI غير فارغ) — تُعرض كمرشحات للتجربة فقط، ولا تُوسم أي شبكة بأنها «شغالة» قبل نجاح فعلي على شريحة المستخدم دون خصم من الباقة |
| السيرفر | يحتاج سيرفر SSH خاصًا بالمستخدم — الدليل الكامل في `docs/INJECTOR-VPS-SETUP-AR.md` (stunnel على Oracle VPS: قبول 443 ← تحويل إلى 127.0.0.1:22)؛ والمنفذ في بروفايل SSH يجب أن يكون 443 لا 22 |
| الدخول | من الدرج («نت مجاني») + زر في شاشة الخوادم؛ وبروفايل SSH يُضاف من شاشة الاستيراد بصيغة `ssh://user:password@host:443` |
| التوثيق | مخزن التوثيق SharedPreferences (ملف `free_internet_verified`) — لا جداول Room جديدة، فلا تغيير في مخطط قاعدة البيانات |

### الحقن HTTP + مولّد الحمولة + فاحص الهوستات (البند 3 — جولة «التطبيق الأحسن»)

| الموضوع | التفصيل |
|---|---|
| مولّد الحمولة | قوالب `CONNECT`/`GET`/`POST` بعناصر نائبة `{host}` و`{port}` و`{sni}`؛ والقوالب المدمجة الثلاثة بنية فقط (بلا أي قيمة هوست/بروكسي من أي تطبيق)؛ والقالب المخصص نص حر يكتبه المستخدم. **تطهير CRLF:** كل قيمة مستبدَلة تُجرَّد من `\r` و`\n` قبل البناء — فلا قيمة تستطيع تهريب سطر ترويسة (مختبَر صراحةً). |
| الحقن | `HttpInjector`: يكتب الحمولة على مقبس TCP إلى بروكسي الشريحة (يدخله المستخدم)، وينجح فقط على `200` أو `101`، ثم يسلّم المقبس الخام إلى JSch عبر `SocketFactory` مخصصة — نفس نمط `TlsSniSocketFactory`. القراءة تتوقف عند نهاية الترويسات بلا قراءة زائدة (لافتة SSH التي تصل فور `200` لا تُبتلع). الفشل (غير 200، إغلاق، مهلة 15 ثانية) ← خطأ عربي واضح ولا يبدأ المحرك أبدًا. |
| فاحص الهوستات | `HostScanner`: يرسل `CONNECT host:443` إلى البروكسي (المنفذان 80 ثم 8080 بالترتيب) ويسجّل `SUCCESS`/`FAILED`/`TIMEOUT` مع الزمن والتاريخ في `ScannedHostStore` (SharedPreferences، سقف 50 نتيجة). الهوستات من هوستات SNI في الجدول المرجعي + ما يكتبه المستخدم؛ والبروكسي إدخال المستخدم دائمًا — **لا قائمة هوستات مدمجة ولا قيم بروكسي مخترعة**. نجاح الفحص يعني «البروكسي رد 200» فقط — لا يعني نتًا مجانيًا، وهذا مكتوب في الواجهة. |
| الواجهة | منتقي طريقة الاتصال (SNI / حقن HTTP) مع تحذير صريح لوضع HTTP؛ ومنتقي قالب + محرر قالب مخصص؛ وبطاقة بروكسي الشريحة (ظاهرة في الوضعين)؛ وبطاقة الفاحص (حقل هوست + زر «افحص الهوستات» + النتائج + «اختر هذه الشبكة» على الناجح). الافتراضي في كل شيء كما قبل: SNI + قالب `CONNECT + Host`. |
| الملفات | جديدة: `feature/connection/.../injector/payload/` — `PayloadTemplate.kt` + `PayloadGenerator.kt` + `PayloadStore.kt` + `HttpInjector.kt` + `HostScanner.kt` + `ScannedHostStore.kt` (المخزنان SharedPreferences لا DataStore — انظر الانحراف في تقرير البند 3)؛ واختبارات: `PayloadGeneratorTest` (17) + `HttpInjectorTest` (16) + `HostScannerTest` (7) = **40 اختبارًا**؛ ومعدَّلة: `InjectorConstants.kt` + `InjectorSshClient.kt` (معامل `socketFactory` اختياري) + `InjectorSessionManager.kt` + `TunnelVpnService.kt` (تمرير الوضع عبر `EXTRA_*` فقط) + `FreeInternetViewModel.kt` + `FreeInternetScreen.kt` |
| القاعدة الصادقة | كل ميزة «نت مجاني» موسومة «جرّب على شريحتك — غير موثقة»؛ والحكم الوحيد اختبار ميداني على شريحة المستخدم مع عدّاد البيانات. الحقن تقنية ثغرة فوترة قد تخالف شروط الشريحة — الوصف تقني فقط ولا تُسوَّق كضمان. |

## 9. المجموعات الذكية + اختبار السرعة (البند 6)

| الموضوع | التفصيل |
|---|---|
| اختبار السرعة | تنزيل فعلي من نقطة Cloudflare العلنية `https://speed.cloudflare.com/__down?bytes=N` (بلا مفتاح وبلا بيانات دخول) — يبدأ 5MB ويكبر إن كان سريعًا (حد الجولة 25MB)، وسقف 10 ثوانٍ؛ يعمل **فقط بضغطة المستخدم وفقط أثناء اتصال نفق قائم** (أبدًا تلقائيًا)، ويعرض حجم البيانات المتوقع ويطلب تأكيدًا قبل البدء؛ وفشل النقطة ← رسالة واضحة لا رقم وهمي؛ والرفع مؤجَّل للإصدار 2 |
| التخزين | آخر نتيجة لكل بروفايل (`SpeedTestResult`: تنزيل/بايتات/زمن/تاريخ) في DataStore فوق ملف `tunnel_settings` المشترك — نتيجة واحدة فقط لكل بروفايل، الأحدث تحل محل السابقة |
| المجموعات | `ConnectionGroup` (id + اسم + بروفايلات + وضع + آخر ناجح + آخر سرعة لكل بروفايل) كمصفوفة JSON واحدة في DataStore — بلا ترحيل Room؛ والوضع الافتراضي الأسرع، والعشوائي يفضّل الناجح حديثًا، والتناوبي يأخذ التالي بعد آخر ناجح |
| التجاوز | عند فشل اتصال ضمن مجموعة نشطة (مسار VPN فقط): بدائل `failoverCandidates` تلقائيًا — **محاولتان كحد أقصى** — ثم توقف برسالة عربية واضحة؛ والنجاح يُسجَّل عبر `recordSuccess` (يحدّث `lastGoodProfileId`)؛ وانقطاع منتصف الجلسة لا يُجاوَز هنا (حلقة إعادة الاتصال في الخدمة تملكه)، وحالة `Blocked` (مفتاح القتل) لا تُقاوَم بتبديل الخوادم أبدًا |
| الواجهة | شاشة «المجموعات» من الدرج (إنشاء/تسمية/حذف + إضافة/حذف خوادم + اختيار الوضع + تعيين النشطة)؛ ورقائق المجموعات أعلى قائمة الخوادم؛ وزر «اتصال عشوائي» (من المجموعة النشطة إن وُجدت وإلا من كل الخوادم، مع تفضيل النجاح الحديث)؛ وآخر سرعة في سطر الخادم (العلم + الزمن + السرعة)؛ وزر «اختبر السرعة» في بطاقة الخادم المتصل فقط |
| الملفات | جديدة: `core/data/.../groups/ConnectionGroup.kt` + `GroupStore.kt` + `GroupAutoSelect.kt` + `core/data/.../di/GroupsModule.kt` (عامل آخر)، و`core/data/.../model/SpeedTestResult.kt` + `core/data/.../repository/SpeedTestStore.kt` + `feature/connection/.../SpeedTest.kt` (عامل آخر)، و`feature/profiles/.../GroupsScreen.kt` + `GroupsViewModel.kt` (هذا البند)؛ ومعدَّلة: `ServersScreen.kt` (الرقائق + العشوائي + السرعة + بطاقة الاختبار) + `ProfilesViewModel.kt` (عمليات المجموعات + اختبار السرعة + إصلاح `selectFastestAndWait` — لا يعيد null أبدًا عند وجود بروفايل واحد) + `ConnectionViewModel.kt` (التجاوز) + `MainActivity.kt` (`Screen.GROUPS`) |
| الاختبارات | `SpeedTestTest` (9) + `GroupAutoSelectTest` (22) + `ProfilesViewModelTest` (Robolectric: `selectFastestAndWait` لا يعيد null عند وجود بروفايل واحد — **CI أخضر على `c916100`، تشغيل 203**)؛ والمحاكي الكامل للبند أُعيد تشغيله: **110 اختبارات** (74 انحدار البند 2 + 22 مجموعات + 9 سرعة + 5 واجهة) |

## 10. التصدير المقفول المشفّر + مشاركة QR (البند 4 — جولة «التطبيق الأحسن»)

| الموضوع | التفصيل |
|---|---|
| الصيغة | `aytun://v1.<base64url>` — الحمولة الثنائية: سحر `AYTUN1` (6 بايت) + ملح عشوائي (16 بايت) + nonce عشوائي (12 بايت) + نص مشفّر **AES-256-GCM** (وسام 128 بت) لمستند JSON نفسه الذي ينتجه `exportProfilesToJson` — **بلا تغيير في بنية JSON** (الاستيراد العادي القديم يعمل كما هو). الملف بامتداد `.aytun`. |
| اشتقاق المفتاح | PBKDF2-HMAC-SHA256 بعدد **210,000 تكرار** (الخطة قالت 600,000، وخُفّض بعد قياس: 600k ≈ 700–800ms على هذه الـVM أي ~3–5 ثوانٍ تقديرًا على Infinix Smart 5 — فوق ميزانية ~3 ثوانٍ؛ والثابت `PBKDF2_ITERATIONS` سطر واحد يُرفع إن أثبت قياس الهاتف هامشًا). كلمة السر **لا تُخزَّن أبدًا** وتُمسح من الذاكرة بعد كل عملية؛ وكلمة خاطئة أو نص معبَث به ← فشل وسام GCM ← رسالة عربية واحدة («كلمة السر غير صحيحة أو الملف تالف») بلا تفاصيل تقنية. |
| QR | **لبروفايل واحد في المرة** — سقف السعة **2,331 بايت بتصحيح أخطاء M** (لا ~3KB بمستوى L، لمتانة المسح بين هاتفين)؛ والحمولة الأكبر تُرفض برسالة وتُقترح مشاركة الملف. لا تقسيم على عدة رموز في الإصدار 1. |
| التدفق | اختيار البروفايلات ← كلمة السر مرتين ← حفظ عبر SAF (`CreateDocument`) أو مشاركة عبر `Intent` أو عرض QR؛ والاستيراد من ملف أو QR أو نص ملصوق (فرع `.aytun` في `importFromText` بعد فحص الفراغ وقبل WireGuard، مع معامل `password` اختياري متوافق رجعيًا). |
| القيد الصادق | **بلا قفل تجاري ولا انتهاء صلاحية في v1** — القفل تشفيري (سرية) لا فرض؛ و**فقدان كلمة السر = فقدان نهائي بلا استرجاع** (مكتوب في الحوار قبل التصدير)؛ وكلمة سر البروفايل الأصلية تبقى داخل `outboundJson` المشفر وتعود نصًا عاديًا بعد الاستيراد (نفس مستوى التخزين الحالي). |
| الملفات | جديدة: `core/data/.../backup/LockedExport.kt` + `QrCodeEncoder.kt` + `feature/profiles/.../LockedExportViewModel.kt` + `LockedExportDialog.kt`؛ واختبارات: `LockedExportTest` (9) + `QrCodeEncoderTest` (6) = **15 اختبارًا جديدًا** (+ 32 انحدارًا أُعيد تشغيلها: 47 إجمالًا)؛ ومعدَّلة: `ProfileRepository.kt` (فرع `.aytun`) + `ImportViewModel.kt` + `ImportScreen.kt` + `QrImportSection.kt` (صار موصولًا في `ImportScreen` — انظر الملاحظة أدناه) + `ServersScreen.kt` (زر «تصدير مشفّر (.aytun)») + `feature/profiles/build.gradle.kts` (سطر `hilt-navigation-compose` واحد) |
| ملاحظة | سطر KDoc قديم في `QrImportSection.kt` كان يقول «Not yet wired into ImportScreen» — صُحّح في جولة تكامل الدفعة الأولى + التحقق من التحديثات (2026-11-25): القسم موصول في `ImportScreen` داخل بطاقة «استيراد مقفول». |

## 11. استيراد `.ovpn` + الاتصال عبر تطبيق OpenVPN for Android المنفصل (البند 1 — جولة «التطبيق الأحسن»)

| الموضوع | التفصيل |
|---|---|
| الترخيص (محسوم) | ics-openvpn **GPL-2.0-only** — يتعارض مع GPL-3.0 الحالي، فـ**لا يُدمج أي كود ics-openvpn إطلاقًا**. الاتصال يتم عبر **Remote API** الخاص بتطبيق OpenVPN for Android المثبَّت منفصلًا — كود `remoteExample` مرخّص **Apache-2.0** صراحة — بلا تبعية مكتبة جديدة إطلاقًا. |
| المحلّل | `OvpnConfParser` (كوتلن خالصة، على نمط `WireGuardConfParser`): يقبل `remote` (الأول معروض، والمتعددة بدائل في `outboundJson`) و`proto tcp/udp` وكتل `<ca>`/`<cert>`/`<key>`/`<tls-auth>` المضمّنة و`auth-user-pass`؛ ويرفض `dev tap` وغياب `remote` والمراجع لملفات خارجية؛ والناتج `Profile(type=OPENVPN)` + `outboundJson` يحمل **نص `.ovpn` المطبَّع** (بعد إزالة التعليقات) لأن محرك التطبيق الآخر يحلّله بنفسه. الكشف بالامتداد `.ovpn` أولًا ثم بالمحتوى (`remote ` + `dev tun` في بداية السطر — لا فحص `in` حرفي، حتى لا تُبتلع نسخة JSON تحوي بروفايل OpenVPN). |
| الاتصال | `OpenVpnLauncher` عميل Remote API عبر `IBinder.transact` بأكواد متحقق منها من ترتيب طرق `.aidl` الحي (`startVPN=4`، `prepare=5`، `prepareVPNService=6`، `disconnect=7`؛ الإصدار 0.7.69) — **بلا ملفات `.aidl` في الشجرة** (هشاشة موثقة: إن أعاد upstream ترتيب الطرق وجب تحديث الأكواد). النتائج: `Started` / `NotInstalled` / `InvalidConfig` / `Failed`؛ و`pause()`/`resume()`؛ ورابط Play. بلا قراءة حالة متزامنة (لا `getStatus` في الـAPI) — إشعار التطبيق الآخر هو مصدر الحقيقة. |
| التوجيه | `ConnectionViewModel.startServiceFor` يتفرع: `OPENVPN` ← `OpenVpnLauncher`، وغيره ← المسار الحالي. بروفايلات OpenVPN **لا تمر بـ`SingBoxConfigBuilder` إطلاقًا** — فلا بروكسي per-app ولا DNS switcher ولا TCP No Delay ولا kill-switch داخل التطبيق عليها؛ والغطاء لها kill-switch النظام (Always-on). خدمتا VPN لا تعملان معًا — الاتصال بواحدة يوقف الأخرى أولًا، **بعد منح الإذن** (لا قبله). |
| الواجهة | شارة «OpenVPN (TCP/UDP)» في بطاقة الخادم (بلا لاحقة «لا يرد» — لا يُفحص أصلًا)؛ وتسمية زر الملف في `ImportScreen` تشمل `.ovpn`؛ وبطاقة إشعار قابلة للإغلاق لنتائج OpenVPN مع زر «تثبيت من متجر Play» عند عدم التثبيت. |
| الملفات | جديدة: `core/data/.../profile/OvpnConfParser.kt` + `feature/connection/.../openvpn/OpenVpnLauncher.kt` + `feature/connection/libs/README-openvpn.md` (مصدر الـRemote API + إصداره + ترخيصه)؛ واختبارات: `OvpnConfParserTest` (23) + `OpenVpnLauncherTest` (14) + تمديد `ProfileRepositoryTest` (+6) و`LatencyTesterTest` (+4) و`ProtocolSpeedTest` (+4) = **67 اختبارًا** في المحاكي؛ ومعدَّلة: `Profile.kt` (`OPENVPN` في نهاية `ProfileType`؛ `Backend` بلا تغيير) + `ProfileRepository.kt` (فرع `.ovpn`) + `LatencyTester.kt` (`OPENVPN -> true` في `isUdpOnly`) + `ProtocolSpeed.kt` (`OPENVPN -> 10` في `speedRank`) + `ConnectionViewModel.kt` + `ServersScreen.kt` + `ImportScreen.kt` |
| الحدود | اتصال OPENVPN **لا يُحيَى بعد إعادة التشغيل** (الجلسة المحفوظة sing-box فقط — يغطيها البند 5أ جزئيًا)؛ وأول اتصال يتطلب موافقة مرة واحدة في التطبيق الآخر + إذن VPN من النظام هناك؛ والتكلفة على المستخدم: تثبيت تطبيق OpenVPN for Android (مجاني) بجانب تطبيقنا. |

## 12. وضع البروكسي بدون VPN (البند 5أ — جولة «التطبيق الأحسن»)

| الموضوع | التفصيل |
|---|---|
| الفكرة | وضع بروكسي هو **حذف مدخل `tun`** لا إضافة مدخل جديد: الكونفيج نفسه بلا `tun-in` ويبقى `mixed-in` وحده (على المنفذ الفعّال، الافتراضي 10808). المحرك لا يعرف أنه VPN — `TunnelEngine.start(profile, configJson)` يأخذ مستندًا فقط. |
| الخدمة | `ProxyOnlyService` — خدمة أمامية عادية، **ليست `VpnService`**: بلا `BIND_VPN_SERVICE`، بلا `VpnService.prepare()`، بلا حوار إذن النظام. `foregroundServiceType="specialUse"` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE="proxy"`؛ وإشعار خاص (ID ‏2) مع زر «قطع الاتصال». تستخدم `TunnelEngine` نفسه مع `ProxyPlatformInterface` مصغّر (كل شيء no-op؛ و`openTun()` يرمي — لأنه لا يُستدعى أصلًا بلا مدخل `tun`). `START_STICKY` + إحياء عبر `LastSessionStore`. |
| الوضع | `ProxyModeStore` (DataStore على ملف `tunnel_settings`): `vpn`/`proxy`، الافتراضي `vpn`. التوجيه في نقطة قرار واحدة `resolveConnectRoute(profileType, proxyMode)` (كوتلن خالصة): OPENVPN أولًا دائمًا، وإلا PROXY عند الوضع، وإلا VPN. `ConnectionViewModel.connect`/`disconnect` يوجّهان عبرها؛ و`disconnect()` يوقف الخدمتين دائمًا. |
| الجلسة والإحياء | `Session` كسبت `isProxy` + `proxyPort` (القديمة تُقرأ كـVPN)؛ و`BootReceiver` يحيي جلسة البروكسي عبر `ProxyOnlyService` والعادية عبر `TunnelVpnService`؛ وحارس في `TunnelVpnService.onStartCommand` يرفض إحياء جلسة بروكسي فلا تتصارع الخدمتان على المنفذ 10808. |
| الواجهة | مفتاح «وضع البروكسي فقط» في شاشة الإعدادات مع الشرح الصادق حرفيًا: «وضع البروكسي لا يوجّه ترافيك أي تطبيق تلقائيًا — يعمل فقط للتطبيقات التي تضبط بروكسي يدويًا على 127.0.0.1:10808؛ وبلا per-app وبلا kill-switch وبلا التقاط DNS» + «يسري على الاتصال التالي». |
| الحدود (v1) | **بلا إعادة اتصال تلقائية** (خطأ المحرك ← Error + توقف الخدمة؛ والمستخدم يعيد الاتصال يدويًا)؛ و**بلا Heartbeat وبلا SpeedMonitor** (يقيسان خارج البروكسي فكانا سيكذبان)؛ و**بلا مسار حقن** (الحقن المجاني ميزة VPN)؛ وبروفايلات `SINGBOX_JSON` تعيد مستندها كما هو (الوضع لا يسري عليها إلا إن حمل مستندها `mixed` أصلًا)؛ وper-app تُتجاهل في وضع البروكسي. |
| الملفات | جديدة: `feature/connection/.../ProxyOnlyService.kt` + `ConnectRoute.kt` + `core/data/.../repository/ProxyModeStore.kt` (في `core/data` لا `feature/connection` — الوحدة بلا اعتماد DataStore)؛ واختبارات: `ProxyOnlyConfigTest` (9) + `ConnectRouteTest` (3) = **12 اختبارًا جديدًا** (+ 37 انحدارًا: 49 إجمالًا)؛ ومعدَّلة: `SingBoxConfigBuilder.kt` (معامل `proxyOnly`) + `ConnectionViewModel.kt` + `LastSessionStore.kt` + `BootReceiver.kt` + `TunnelVpnService.kt` (الحارس فقط) + `AndroidManifest.xml` (إعلان الخدمة) + `SettingsViewModel.kt` + `SettingsScreen.kt` + `SettingsViewModelTest.kt` |
| ملاحظتان | نص بطاقة الإعدادات يذكر المنفذ حرفيًا «127.0.0.1:10808» بينما البورت قابل للتغيير من البطاقة فوقها — إن غيّر المستخدم البورت صار النص غير دقيق (تنظيف لاحق). ومعامل `proxyOnly` في `startServiceFor` بلا مستدعٍ يمرّر `true` (زائد؛ يُحذف في تنظيف لاحق أو يُترك بلا أثر). |

## 13. مفتاح القتل داخل التطبيق (البند 2 — جولة «التطبيق الأحسن»)

| الموضوع | التفصيل |
|---|---|
| المشكلة التي يحلها | `scheduleReconnect` كان يعيد المحاولة إلى ما لا نهاية، وكل محاولة تستدعي `stopEngineSilently()` الذي يغلق الـTUN — فالترافيك يتسرب خارج النفق في الفجوة بين كل إغلاق وإعادة فتح. |
| الفكرة | عند سقوط النفق سقوطًا غير مقصود والمفتاح مفعّل، **لا يُغلق الـTUN إطلاقًا** — يبقى الـfd مفتوحًا بلا قارئ، فالترافيك الموجَّه إليه لا يجد طريقًا (محظور فعليًا)، وتستمر محاولات إعادة الاتصال؛ وعند نجاحها يعيد المحرك فتح الـTUN بالطريقة المعتادة. |
| آلة الحالات | `KillSwitchManager` (كوتلن خالصة): `OFF` ←→ `ARMED` (مفعّل والنفق يعمل) ←→ `BLOCKING` (مفعّل والنفق ساقط). `setEnabled(true)` من BLOCKING يبقى BLOCKING؛ و`onEngineFailed()` من OFF يبقى OFF (فشل متأخر بعد إيقاف مقصود لا يعيد الحظر)؛ و`onStopped()` ← OFF من أي حالة. |
| التفصيلة التقنية الحاسمة | `openTun` يعيد `fd.detachFd()` لـlibbox، فبعد الفصل لا تملك الخدمة الـfd إطلاقًا — فـ«لا يُغلق الـTUN» كان مستحيلًا بلا نسخة. الآن: نسخة `ParcelFileDescriptor.dup(fd.fileDescriptor)` تُؤخذ **قبل** `detachFd()` وتُعاد لـlibbox نسخة أخرى؛ و`closeTunFd()` يغلق النسخة عند كل إيقاف حقيقي. (درس: `Os.dup` يأخذ `FileDescriptor` لا `Int` — وخطأ تبديل النوعين هذا أسقط CI مرة وأُصلح في التزام `afafdae`.) |
| التكامل | `stopEngineSilently(keepTun: Boolean = false)`؛ و`scheduleReconnect` يستدعي `onEngineFailed()` مرة واحدة قبل الحلقة ويمرّر `keepTun = shouldKeepTun`؛ و`stopTunnel` (و`onDestroy` و`onRevoke`) يستدعي `onStopped()` أولًا ويغلق الـTUN دائمًا — **لا `keepTun=true` في أي مسار إيقاف**. حالة عرض جديدة `TunnelState.Blocked` + إشعار «محظور — مفتاح القتل». موت جلسة الحقن يمرّ بالحارس القديم أولًا فيُظهر `Error` لا `Blocked` (الاتجاه آمن — الترافيك محظور فعليًا حتى يوقف المستخدم). |
| الواجهة | مفتاح حقيقي في شاشتي الإعدادات والخوادم (نفس الإعداد) مع التحذير حرفيًا: «عند تفعيله لن يخرج أي ترافيك من الجهاز إطلاقًا أثناء انقطاع النفق — حتى الإشعارات — إلى أن يعود الاتصال أو توقفه يدويًا» + حدّ موت العملية + «يسري على الاتصال التالي». وبطاقة الإرشاد القديمة في `ServersScreen` استُبدلت بالمفتاح، مع بقاء زر «فتح إعدادات VPN» (مسار Always-on الأقوى). |
| الحدود الصادقة | **لا ينجو من موت العملية** — إن قتل النظام العملية كلها مات الـTUN معها وعاد الإنترنت مباشرة؛ والغطاء الوحيد الباقي Always-on في النظام (مكتوب في الواجهة). **لا يعمل في وضع البروكسي إطلاقًا** (بلا TUN — المفتاح معطَّل في الواجهة مع شرح السبب) ولا على بروفايلات OpenVPN الخارجية. اتصال يفشل قبل أول TUN يبقى خطأ عاديًا بلا حظر. |
| الملفات | جديدة: `core/data/.../repository/KillSwitchStore.kt` (DataStore فوق `tunnel_settings`، مفتاح `kill_switch_enabled`، افتراضي OFF) + `feature/connection/.../KillSwitchManager.kt`؛ واختبارات: `KillSwitchManagerTest` (16) = **16 اختبارًا جديدًا** (+ 58 انحدارًا: 74 إجمالًا)؛ ومعدَّلة: `HamoSettingsModule.kt` (الربط) + `TunnelVpnService.kt` + `ConnectionViewModel.kt` + `TunnelState.kt` + `ConnectionPanel.kt` + `TunnelTileStateMapper.kt` + `SettingsViewModel.kt` + `SettingsScreen.kt` + `ServersScreen.kt` + اختبارا `TunnelTileStateMapperTest` و`SettingsViewModelTest` |
| الافتراضي | OFF — المستخدم يفعّله بنفسه بعد قراءة التحذير. |

## 14. ZIVPN — البحث الاستكشافي Spike (البند 5ب): **غير منفذ — القرار (ج) لا مصدر موثوق**

| الموضوع | التفصيل |
|---|---|
| الحكم | صيغة سلك ZIVPN **ليست** Hysteria2، ولا يوجد أي مواصفة بروتوكول علنية ولا أي تنفيذ مفتوح المصدر (عميل أو خادم) لـZIVPN — فلا يمكن تنفيذها كـoutbound من نوع `hysteria2` في sing-box، ولا كتابة عميل نظيف الغرفة من وثائق غير موجودة. **تبقى الميزة غير منفذة** برسالتها الحالية («مخطط للمرحلة 2»)، وتبقى الخطافات كما هي. تنفيذ ناقص موثق خير من ثنائي مغلق. |
| الأدلة الحاسمة | التمويه اسمه حرفيًا **`zivpn`** في كونفيج الخادم الرسمي (`zahidbd2/udp-zivpn` — بلا ترخيص، سكربتات تثبيت فقط) لا `salamander`/`gecko`؛ والمصادقة نظام أوضاع (`"mode": "passwords"` + قائمة، والخوادم المرجعية `auth=1`) لا كلمة سر واحدة `Hysteria-Auth`؛ و**لا TLS/SNI إطلاقًا** في بروفايل ZIVPN بينما هاندشيك Hysteria2 كله HTTP/3 فوق TLS؛ وHamo نفسه يشغّل Hysteria v1 وZIVPN محركين منفصلين في `libogkayouy.so`. و`powermx/zivpn` (GPL-3.0) سكربتات تثبيت فقط؛ و`ZerQAQ/Zvpn` إطار Go عام من 2021 وليس ZIVPN (فخ مستبعد). |
| ما يبقى | `ProfileType.ZIVPN` + `Backend.EXTERNAL_PROCESS` في `Profile.kt`؛ والبيلدر يصدر outbound `socks` إلى `127.0.0.1:<zivpnSocksPort()>` (الافتراضي 1080)؛ و`ExternalProcessBackend` مسدود (`UnsupportedOperationException` — «مخطط للمرحلة 2»)؛ و`ProfileLinkParser` يرفض روابط `zivpn://` برسالة «المرحلة 2» — الرسالة ما زالت صادقة. |
| إعادة النظر | فقط إن ظهر مواصفة بروتوكول علنية أو عميل/خادم مفتوح المصدر مرخّص لـZIVPN — عندها يُعاد الـSpike من النقطة نفسها. والفائدة التقنية لـZIVPN (QUIC + Brutal + تمويه + قفز منافذ) مغطاة أصلًا بـHysteria2 داخل sing-box؛ وقيمة ZIVPN الوحيدة كانت التوافق مع خوادم ZIVPN القائمة. |
| التوثيق | سطر واحد في `docs/PHASE2.md` §1: «Spike 2026-11-25: (ج) — لا مصدر موثوق؛ ZIVPN ليس Hysteria2؛ التمويه اسمه `zivpn`؛ يبقى غير منفذ». والتقرير الكامل: `~/workspace/tunnel-app-work/better-app-round/zivpn-spike/REPORT-AR.md` (خارج المستودع). **صفر ملف معدَّل في المستودع خلال الـSpike.** |

## 15. الدفعة الأولى من قائمة الأخذ (2026-11-24 — 44 ملفًا جديدًا + 9 ملفات معدَّلة + 90 اختبارًا جديدًا)

| الموضوع | التفصيل |
|---|---|
| استيراد QR (3 ملفات + اعتماد) | `core/data/.../profile/QrCodeDecoder.kt` (فك خالص JVM بـzxing + `isImportableQrText`: يقبل رابط بروكسي أو اشتراك، ويرفض أي نص آخر) + `feature/profiles/.../QrCodeBitmapDecoder.kt` (محوّل أندرويد رفيع: `decodeBitmap`/`decodeUri` مع تقليل الصورة إلى 1024 بكسل) + `feature/profiles/.../QrImportSection.kt` (واجهة Compose بلا ViewModel: كاميرا عبر `TakePicturePreview` + معرض عبر `GetContent`، بلا إذن CAMERA وبلا CameraX)؛ الاعتماد الوحيد الجديد: `com.google.zxing:core:3.5.3` (Apache-2.0) في `app/build.gradle.kts` و`core/data/build.gradle.kts`؛ و`gradle/libs.versions.toml` لم يُلمس |
| بلاطة الإعدادات السريعة (3 ملفات) | `feature/connection/.../TunnelTileStateMapper.kt` (منطق خالص: `Started→CONNECTED`، و`Starting/Stopping/Reconnecting→CONNECTING`، و`Stopped/Error→DISCONNECTED`) + `TunnelTileService.kt` (يقرأ `TunnelStateHolder.state` فقط، ويتابعها حيًا أثناء فتح الستارة فقط — بلا عمل خلفي؛ والنقر يبدّل التشغيل/الإيقاف أو يفتح التطبيق إن لم توجد جلسة أو كانت جلسة حقن أو لم يُمنح إذن VPN) + `res/drawable/ic_tile.xml` (أيقونة بيضاء، نفس قوس النفق) |
| بروكسي لكل تطبيق (7 ملفات) | `core/data/.../routing/PerAppProxySettings.kt` (الوضعان + `includePackages()`/`excludePackages()` النقيتان) + `core/data/.../repository/PerAppProxyStore.kt` (واجهة + تطبيق DataStore في ملف `tunnel_settings`) + `core/data/.../di/PerAppDnsModule.kt` (ربطا Hilt للمخزنين) + `feature/connection/.../perapp/InstalledAppsProvider.kt` (قائمة التطبيقات المثبتة عدا التطبيق نفسه، على `Dispatchers.IO`) + `perapp/PerAppProxyModule.kt` (ربط Hilt للمصدر) + `perapp/PerAppProxyViewModel.kt` (الحالة + البحث/التصفية + الحفظ الفوري عند كل نقرة) + `perapp/PerAppProxyScreen.kt` (بطاقة الأوضاع الثلاثة + بحث + قائمة بأيقونة كسولة لكل صف) |
| فحص ما بعد الاتصال (3 ملفات) | `feature/connection/.../PostConnectionCheck.kt` (طلب `generate_204` عبر `EndpointProbe.probe()` الموجود + جلب الـIP العام من `https://api.ipify.org` وحده) + `PostConnectionCheckViewModel.kt` (يشغّل الفحص تلقائيًا مرة عند كل انتقال إلى `Started`، ويدويًا عبر `checkNow()`، ويمسح النتيجة عند قطع الاتصال، ويكتب كل نتيجة في `TunnelEventLog`) + `PostConnectionCheckCard.kt` (بطاقة: ✓/✗ مرور الترافيك + الـIP العام + زمن الاستجابة + زر «إعادة الفحص»)؛ والنجاح = مرور `generate_204` فقط — فشل جلب الـIP لا يحوّل نفقًا يعمل إلى «فاشل» |
| الإحصاءات (5 ملفات) | `core/data/.../stats/UsageStats.kt` (منطق نقي: `SessionRecord` + إجماليات اليوم/الأسبوع/الكل + استهلاك كل بروفايل + `SessionAccumulator`) + `UsageStatsStore.kt` (ملف JSON خاص `usage-stats.json`، كتابة ذرّية؛ مفقود أو فاسد = إحصاءات فارغة بلا استثناء؛ آخر 30 جلسة + 90 يومًا) + `UsageStatsRepository.kt` (`beginSession` عند بدء النفق و`noteProgress` ذاكرة فقط بلا I/O و`endSession` عند الإيقاف وهي الكتابة الوحيدة على القرص) + `feature/connection/.../stats/UsageStatsScreen.kt` + `UsageStatsViewModel.kt` (الجلسة الحالية + بطاقات اليوم/الأسبوع/كل الوقت + حسب الخادم + الجلسات الأخيرة + مسح مع تأكيد)؛ والتخزين JSON لا DataStore (لا يصلح لقوائم الجلسات) ولا Room (بلا مساس بالمخطط)؛ ومقايضة موثقة: جلسة تُقتل من النظام تُفقد |
| التشخيص (2 ملفات) | `feature/connection/.../DiagnosticsViewModel.kt` (بلا اعتماديات فوق `TunnelEventLog` الموجود؛ يشتق نوع الحدث من نص الرسالة: اتصال/انقطاع/شبكة/فحص/أخرى؛ بلا polling دوري) + `DiagnosticsScreen.kt` (فلترة حسب النوع + نسخ الصفوف الظاهرة + مسح مع تأكيد) |
| DNS (4 ملفات) | `core/data/.../net/DnsPreference.kt` (`DnsPreset`: تلقائي/Cloudflare/Google/Quad9/AliDNS/مخصص + `validateCustomDnsHost` النقي) + `core/data/.../repository/DnsPreferenceStore.kt` (واجهة + تطبيق DataStore، الافتراضي AUTO) + `feature/connection/.../dns/DnsSettingsViewModel.kt` (اختيار الإعداد المسبق بحفظ فوري؛ والمخصص لا يُحفظ إلا بعد تحقق صالح) + `dns/DnsSettingsScreen.kt` (قائمة الخوادم + حقل المخصص + «الخادم الفعّال الآن») |
| TCP No Delay (3 ملفات) | `core/data/.../repository/TcpNoDelayRepository.kt` (مستودع DataStore جديد، مفتاح `tcp_no_delay`، الافتراضي `false`؛ و`SettingsRepository.kt` لم يُلمس) + `feature/connection/.../TcpNoDelay.kt` (نقطة التطبيق الوحيدة: `apply(socket, enabled)`) + `TcpNoDelayViewModel.kt` (مفتاح تبديل جاهز للربط بشاشة إعدادات لاحقًا؛ يسري على الاتصال التالي)؛ و**لا حقل `tcp_nodelay` في sing-box 1.14.4** (يُفحص من توثيق `DialerOptions` — الحقول `tcp_fast_open` و`tcp_multi_path` وkeep-alive فقط، وsing-box يرفض الحقول غير المعروفة) فلم يُضف أي حقل إلى `SingBoxConfigBuilder` عمدًا |
| الاختبارات (14 ملفًا جديدًا + 1 ممدَّد = 90 اختبارًا) | `QrCodeDecoderTest` (7) + `TunnelTileStateMapperTest` (5) + `PerAppProxySettingsTest` (5) + `DnsPreferenceTest` (10) + `SingBoxConfigBuilderPerAppTest` (5) + `PerAppProxyViewModelTest` (9) + `DnsSettingsViewModelTest` (8) + `PostConnectionCheckTest` (6) + `UsageStatsAggregatorTest` (13) + `UsageStatsStoreTest` (5) + `DiagnosticsViewModelTest` (7) + `TcpNoDelayTest` (4) + `TcpNoDelayRepositoryTest` (3) + `TcpNoDelayDefaultsTest` (2) = 89، + اختبار إضافي واحد في `SpeedMonitorTest.kt` الممدَّد = **90 اختبارًا جديدًا** |
| المعدَّلة الـ9 | `app/build.gradle.kts` (zxing) + `core/data/build.gradle.kts` (zxing) + `feature/connection/AndroidManifest.xml` (إعلان خدمة البلاطة + إذن `QUERY_ALL_PACKAGES`) + `core/data/.../singbox/SingBoxConfigBuilder.kt` (معاملا `includePackages`/`excludePackages` الاختياريان — فارغان افتراضيًا = كونفيج مطابق تمامًا للسابق) + `feature/connection/.../ConnectionViewModel.kt` (حقن المخزنين + تمرير قوائم البروكسي وDNS للبيلدر) + `ConnectionPanel.kt` (ثلاث معاملات اختيارية بقيم افتراضية لبطاقة الفحص) + `SpeedMonitor.kt` (أُضيف `cumulative: StateFlow<Pair<Long,Long>>` — بلا عيّنة إضافية) + `TunnelEventLog.kt` (أُضيف `clear()` + تغليف `Log.i` بـ`runCatching` حتى لا ينفجر في اختبار وحدة JVM) + `SpeedMonitorTest.kt` (اختبار إضافي للعدّادات التراكمية) |
| الافتراضيات بلا تغيير | وضع بروكسي ALL + DNS تلقائي (سلوك فحص الأسرع الحالي حرفيًا) + TCP No Delay متوقف + لا كونفيج جديد — سلوك اليوم حرفيًا إن لم يفتح المستخدم الشاشات الجديدة؛ وبلا إعلانات/تتبع/بيانات دخول، وبلا خدمة جديدة أو عمل دوري، وبلا تغيير في سلوك مسار النفق الافتراضي |
| قيدا مسار الحقن و`SINGBOX_JSON` | اختيارا البروكسي/DNS يسريان على مسار sing-box الرئيسي فقط — مسار الحقن (SNI+SSH) يبني كونفيجه الخاص (`InjectorConfig`) فلا يسريان عليه، وبروفايلات `SINGBOX_JSON` يُعاد مستندها كما هو فلا يسريان عليها أيضًا (نفس قيد `autoRoute`/`adsBlocking` الحالي) |
| تغيير افتراضي الحقن المقصود | ربط TCP No Delay على مسار الحقن يغيّر الافتراضي هناك من «دائمًا مفعّل» (`socket.tcpNoDelay = true` في `TlsSniSocketFactory.kt:98`) إلى «متوقف» (افتراضي `false`، opt-in) — تغيير سلوك مقصود، لا خطأ يُصلح بالعكس |
| الربط (التكامل) | الشاشات الأربع في درج `MainActivity` + بطاقة الفحص في `ServersScreen` + قسم QR في `ImportScreen` (موصول داخل بطاقة «استيراد مقفول») + توصيل الإحصاءات وTCP No Delay في `TunnelVpnService` والحقن — جولة تكامل منفصلة (2026-11-25) |

## 16. التحقق من التحديثات (2026-11-24 — 15 ملفًا جديدًا: 10 إنتاج + 5 اختبار، 47 اختبارًا)

| الموضوع | التفصيل |
|---|---|
| المنطق والشبكة — `:core:data` (5 ملفات) | `core/data/.../update/UpdateVersion.kt` (منطق نقي: `Semver` متساهل — `v` اختيارية، و`1.2` ← 1.2.0، وpre-release أقدم من نفس القاعدة بلا لاحقة — + `parseSemver`/`compareTo` + `InstalledVersion` + `InstallSource` يدوي/F-Droid/Play + `ReleaseInfo` + `parseVersionCodeFromAssetName` + `decideUpdate` + ثوابت `LATEST_RELEASE_URL` و`FDROID_PACKAGE_NAME` و`PLAY_PACKAGE_NAME`) + `update/UpdateChecker.kt` (واجهة `UpdateChecker` + `GithubReleaseChecker` بعميل OkHttp المشترك من `DataModule` — بلا عميل جديد؛ ونقطة النهاية `https://api.github.com/repos/aalhdydy550-ui/tunnel-app/releases/latest` عامة بلا مصادقة، و`/latest` لا يعيد مسودة ولا pre-release أبدًا) + `update/UpdateDownloader.kt` (واجهة + `OkHttpUpdateDownloader`: ينزّل إلى `<cacheDir>/updates/update.apk` عبر ملف مؤقت ثم إعادة تسمية؛ والإلغاء يقطع الاتصال فورًا؛ وأي فشل/إلغاء يحذف الجزئي؛ وتنزيل مقطوع يُرفض؛ وملف مؤقت قديم يُحذف قبل البدء) + `update/InstalledVersionProvider.kt` (واجهة + تطبيق PackageManager: الاسم + الرمز + مصدر التثبيت عبر `getInstallSourceInfo` على API 30+ و`getInstallerPackageName` قبله — **ليس BuildConfig**: الكود في وحدتي مكتبة لا تريان `BuildConfig` الخاص بوحدة التطبيق، ولا كود في المشروع يستخدم BuildConfig أصلًا) + `core/data/.../di/UpdateCheckModule.kt` (وحدة Hilt جديدة: 3 `@Provides` + `@Singleton` للفاحص والمنزّل ومزوّد الإصدار؛ و`DataModule` لم يُلمس) |
| الحالة والواجهة — `:feature:connection` (4 ملفات) | `feature/connection/.../update/UpdateCheckState.kt` (`UpdateCheckUiState` مختومة؛ كل حالة تحمل الإصدار المثبَّت: `Idle`/`Checking`/`StoreManaged`/`UpToDate`/`UpdateAvailable`/`Downloading`/`Downloaded`/`CheckFailed`/`DownloadFailed`) + `update/UpdateCheckViewModel.kt` (`@HiltViewModel`: يقرأ الإصدار المثبَّت مرة عند البدء — فشل PackageManager ← «0.0.0» بدل انهيار الشاشة؛ ونسخة المتجر تبدأ `StoreManaged` والفحص فيها no-op؛ و`checkForUpdates()` يدوي ممنوع أثناء فحص/تنزيل؛ و`downloadUpdate()` يقبل `UpdateAvailable` و`DownloadFailed` لإعادة المحاولة؛ و`cancelDownload()` يعيد `UpdateAvailable` بلا خطأ؛ وترجمة الأخطاء لعربية: 403/429 ← حد GitHub، و404 ← لا إصدار منشور، وNetwork ← لا اتصال؛ والتثبيت ليس هنا عمدًا — الشاشة تملك intent التثبيت بسياق النشاط) + `update/UpdateCheckScreen.kt` (شاشة Compose عربية: بطاقة الإصدار المثبَّت دائمًا + فرع لكل حالة + تنزيل مع شريط تقدّم ونسبة وإلغاء + زرّا «تثبيت التحديث» و«إعدادات التثبيت من مصادر غير معروفة» + تنبيه إذن أندرويد 8+؛ **لا فحص تلقائي عند فتح الشاشة**) + `update/UpdateInstaller.kt` (كائن مساعد: `apkUri` عبر FileProvider + `canInstall` + `installIntent` بـ`ACTION_INSTALL_PACKAGE` + `unknownSourcesSettingsIntent` بـ`ACTION_MANAGE_UNKNOWN_APP_SOURCES`؛ وبلا أي محاولة لتفعيل الإذن برمجيًا) |
| مسارات FileProvider — `app` (ملف واحد) | `app/src/main/res/xml/file_paths.xml` (جديد): `<cache-path name="updates" path="updates/" />` فقط — لا شيء آخر من الكاش مكشوف؛ وإعلان الـ`<provider>` نفسه في `app/src/main/AndroidManifest.xml` (إذن `REQUEST_INSTALL_PACKAGES` + `FileProvider` بالسلطة `dev.aalhdydy550.tunnel.fileprovider`) |
| الاختبارات (5 ملفات = 47 اختبارًا) | `UpdateVersionTest` (20: تحليل semver ومقارنته ورمز الإصدار من اسم الملف ومصفوفة `decideUpdate` كاملة 8 حالات ومصدر التثبيت) + `GithubReleaseCheckerTest` (9 ضد `FakeHttpServer` على `::1`: إصدار صحيح وبلا APK و404 ← `HttpError(404)` و500 وJSON مشوّه وبلا `tag_name` ومضيف مغلق ← Network) + `UpdateDownloaderTest` (4: تنزيل 100KB بايتًا بايتًا + 404 بلا ملف + حذف المؤقت القديم + إلغاء فوري بلا جزئي) + `InstalledVersionProviderTest` (3 بـMockK: F-Droid ومثبِّت null ← يدوي وPlay) + `UpdateCheckViewModelTest` (11 بمزيفات يدوية: الحالة الأولية Idle وF-Droid ← StoreManaged والفحص no-op وتحديث متاح ومحدَّث و404 ← «لا يوجد إصدار» وتنزيل 50% ← 100% ثم Downloaded وفشل ثم إعادة محاولة وإلغاء ← عودة لـUpdateAvailable) |
| قاعدة القرار `decideUpdate` | الـ**semver أولًا** (وسم أحدث يفوز إطلاقًا؛ وسم أقدم لا يحدّث أبدًا حتى برمز أكبر)؛ وعند تساوي الـsemver يكسر **رمز الإصدار** التعادل (إعادة بناء لنفس الوسم)؛ وإن تعذّر تحليل semver أحد الطرفين يقرر رمز الإصدار وحده عندما يكون معروفًا؛ ولا شيء قابل للمقارنة إطلاقًا ← `CannotDetermine` (رسالة تدعو للتحديث اليدوي من GitHub، لا تحديث أعمى) |
| اتفاقية اسم ملف الإصدار | المحدِّث يأخذ أول أصل ينتهي بـ`.apk`؛ ورمز الإصدار يُقرأ من رقم أخير بعد `-`/`_` قبل `.apk` (مثال `ayman-tunnel-plus-v0.1.0-2.apk` ← 2)؛ والتوصية للنشر: سمِّ ملف الإصدار دائمًا **`ayman-tunnel-plus-v<versionName>-<versionCode>.apk`** — بدونه يعمل التحديث بالـsemver وحده، لكن إعادة بناء لنفس الوسم لن تُكتشف |
| **GitHub Releases فقط — ونسخة F-Droid لا تستخدمه** | **المحدِّث يفحص GitHub Releases فقط، ونسخة F-Droid لا تستخدمه**: إن كان مصدر التثبيت F-Droid أو Play تبدأ الشاشة `StoreManaged` بلا زر فحص (والفحص فيها no-op)، وتشرح أن التحديث يأتي من المتجر؛ ومسار الكود خامل تمامًا على تثبيت F-Droid (لا طلب شبكة ولا تنزيل)، فيبقى سلوك نسخة F-Droid مطابقًا لسياسة المتجر |
| التنزيل المباشر بـOkHttp بدل DownloadManager | التنزيل المباشر يعطي تقدّمًا حيًا داخل الشاشة بلا حلقة استعلام وبلا إشعار وبلا إذن جديد؛ ويعيش فقط ما دامت الشاشة مفتوحة؛ والمقايضة الموثقة: إن قُتل التطبيق أثناء التنزيل يضيع الجزئي (يُحذف عند المحاولة التالية) — مقبول مقابل صفر خدمة خلفية؛ والتحليل بـkotlinx.serialization.json لا org.json (ينفجر بـ«Stub!» في اختبار وحدة JVM) |
| الحد المجاني لـGitHub | `/releases/latest` بلا مصادقة = 60 طلبًا/ساعة لكل IP — كافٍ لفحص يدوي؛ و403/429 تُترجم «تجاوز حد طلبات GitHub — حاول بعد ساعة» |
| القيد: بلا إصدار منشور بعد | **بلا إصدار منشور بعد فالفحص يرد 404 صحيحًا** — الخادم يعيد `HttpError(404)` وتترجمه الشاشة «لا يوجد إصدار منشور»؛ وهذا سلوك صحيح متوقع حتى يُنشر أول إصدار على GitHub Releases، لا خطأ في المحدِّث |
| الربط (التكامل) | أيقونة الشريط العلوي + مدخل الدرج + فرع الشاشة في `MainActivity` + إذن `REQUEST_INSTALL_PACKAGES` و`FileProvider` في المانيفست — جولة تكامل منفصلة (2026-11-25) |
