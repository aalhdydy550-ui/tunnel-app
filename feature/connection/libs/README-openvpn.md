# OpenVPN عبر Remote API — بلا دمج كود (clean-room)

هذا الملف يوثّق الطرف الآخر الذي يتحدث معه
`feature/connection/.../openvpn/OpenVpnLauncher.kt`: تطبيق
**OpenVPN for Android** المثبَّت منفصلًا. لا يوجد أي كود من
ics-openvpn في هذه الشجرة — لا ملفات `.aidl` ولا نسخ من
`remoteExample` — والغلاف مكتوب من الصفر من دراسة الواجهة
المنشورة فقط.

## المصدر والإصدار الموثَّق

- المستودع: <https://github.com/schwabe/ics-openvpn> (`master`)
- الالتزام المفحوص: `660901ce320539aec476bd04bd1999c396d5e778`
  (فُحص 2026-11-24)
- الإصدار الموثَّق: **0.7.69** (`versionCode = 225` في
  `main/build.gradle.kts` — `minSdk = 23`، `targetSdk = 37`)
- الواجهة: `main/src/main/aidl/de/blinkt/openvpn/api/IOpenVPNAPIService.aidl`
  (+ `IOpenVPNStatusCallback.aidl` و`APIVpnProfile.aidl`)
- التنفيذ (الطرف الخادم): `main/src/main/java/de/blinkt/openvpn/api/ExternalOpenVPNService.java`
- توثيق الاستخدام في المنبع: قسم «Controlling from external apps»
  في `README.md` + `doc/README.txt` (سؤال «How can I control the
  app from an external app?»)

## الترخيص — لماذا هذا الطريق قانوني

- التطبيق نفسه **GPL-2.0-only** (`doc/LICENSE.txt`: «OpenVPN for
  Android is distributed under the GPL license version 2») — يتعارض
  مع GPL-3.0 الخاصة بنا، فلا يُدمج أي كود منه إطلاقًا.
- كود التحكم من تطبيق آخر (`remoteExample`) **Apache-2.0** صراحة
  (`doc/LICENSE.txt`: «The source code needed to control OpenVPN for
  Android from another app (the remoteExample) is licensed under the
  Apache v2 license»)، ونفس النص في
  `remoteExample/LICENSE.remoteExample`.
- ملفات الواجهة نفسها معفاة من GPLv2 صراحة (ترويسة
  `APIVpnProfile.java`: «This file is used for implementing the
  external API and this file like the AIDL and is exempted from the
  GPLv2»)، ويؤكد `doc/README.txt`: «The use of the AIDL API to
  control OpenVPN for Android from an external app is not subject to
  the license».
- الخلاصة: التحكم عبر الـAIDL إلى تطبيق مثبَّت منفصلًا لا يدمج
  كودًا ولا يخلق عملًا مشتقًا في ثنائيتنا.

## الواجهة كما فُحصت (master ‏660901c)

الخدمة: `de.blinkt.openvpn.api.ExternalOpenVPNService`
(`android:exported="true"`)، تُربط بالإجراء
`de.blinkt.openvpn.api.IOpenVPNAPIService` على الحزمة
`de.blinkt.openvpn`. الدوال التي يستخدمها الغلاف (برموز
المعاملات المشتقة من ترتيبها في ملف `.aidl` — AIDL يرقّم من
`FIRST_CALL_TRANSACTION` أي 1 بالترتيب):

| الدالة في الـAIDL | الرمز | ما يفعله الغلاف بها |
|---|---|---|
| `Intent prepare(in String packagename)` | 5 | يعيد `null` إن كان تطبيقنا مسموحًا له بالتحكم، وإلا Intent لشاشة الموافقة (`ConfirmDialog`) في التطبيق الآخر — يفتحها الغلاف ويعيد `Failed` برسالة تطلب الموافقة وإعادة المحاولة |
| `Intent prepareVPNService()` | 6 | يعيد `null` إن كان التطبيق الآخر يملك إذن VPN من النظام، وإلا Intent لشاشة منح الإذن (`GrantPermissionsActivity`) — نفس المعالجة |
| `void startVPN(in String inlineconfig)` | 4 | يرسل نص `.ovpn` كاملًا (بما فيه الكتل المضمّنة)؛ ينشئ التطبيق الآخر بروفايلًا مؤقتًا باسم «Remote APP VPN» (`mProfileCreator` = حزمتنا) ويشغّله. خطأ تحليل الكونفيج يعود `RemoteException` برسالة |
| `void disconnect()` | 7 | يوقف الاتصال |

دوال أخرى موجودة في الواجهة ولا يستخدمها الغلاف اليوم:
`getProfiles()` (1)، `startProfile(uuid)` (2)،
`addVPNProfile(name, config)` (3)، `pause()` (8)، `resume()` (9)،
`removeProfile(uuid)` (10)، `protectSocket(fd)` (11)،
`addNewVPNProfile(name, userEditable, config)` (12)،
`startVPNwithExtras(inlineconfig, extras)` (13)،
`addNewVPNProfileWithExtras(...)` (14)،
`getDefaultProfile()` (15)، `setDefaultProfile(uuid)` (16).

**لا توجد قراءة حالة في الواجهة المنشورة:** ملف `.aidl`
المفحوص لا يحوي `getStatus()` ولا `registerStatusCallback` /
`unregisterStatusCallback` (يستورد `IOpenVPNStatusCallback` بلا
استخدام)، فـ`isActive()` في الغلاف تعيد آخر حالة نعرفها نحن
فقط — انظر حدودها في توثيق `OpenVpnLauncher`.

بديل قائم على الـIntent (لا يستخدمه الغلاف): أنشطة
`de.blinkt.openvpn.api.ConnectVPN` / `DisconnectVPN` /
`PauseVPN` / `ResumeVPN` مع extra
`de.blinkt.openvpn.api.profileName` لاسم بروفايل مخزَّن في
التطبيق الآخر — لا يصلح لنا لأنه يتطلب بروفايلًا مخزَّنًا
بالاسم لا نص كونفيج مرسلًا.

## نموذج الإذن (طرف التطبيق الآخر)

- كل استدعاء AIDL يمر عبر `ExternalAppDatabase.checkOpenVPNPermission`:
  حزمتنا يجب أن تكون في قائمة `allowed_apps` لدى التطبيق الآخر،
  وإلا رُمي `SecurityException("Unauthorized OpenVPN API Caller")`.
- تُمنح الموافقة مرة واحدة من شاشة `ConfirmDialog` التي يفتحها
  `prepare()` — بلا إذن manifest من جهتنا وبلا توقيع مشترك.
- إن احتاج البروفايل اسم مستخدم/كلمة مرور (`auth-user-pass` بلا
  بيانات مضمّنة) أو كان إذن VPN غير ممنوح بعدُ، فتح التطبيق
  الآخر شاشته هو (`LaunchVPN`) ليكمل المستخدم — الغلاف لا يعرض
  حقل بيانات دخول ولا يمرّرها.
- عند إلغاء تثبيت تطبيقنا يُوقف التطبيق الآخر البروفايل المؤقت
  تلقائيًا (مراقب `ACTION_PACKAGE_REMOVED` في الخدمة).

## شرط التثبيت

- يجب تثبيت **OpenVPN for Android** (مجاني) بجانب تطبيقنا:
  <https://play.google.com/store/apps/details?id=de.blinkt.openvpn>
  (أو F-Droid) — بلا هذا التطبيق يعيد الغلاف
  `OpenVpnResult.NotInstalled` ولا يفعل شيئًا آخر.
- كشف التثبيت: `PackageManager.getPackageInfo("de.blinkt.openvpn")`.
  رؤية الحزم مكفولة بإذن `QUERY_ALL_PACKAGES` الموجود أصلًا في
  `feature/connection/src/main/AndroidManifest.xml` (من batch-1،
  لشاشة per-app proxy) — **لا يلزم إعلان `<queries>` إضافي**
  ما دام هذا الإذن موجودًا. إن أُزيل الإذن يومًا ما وجب إعلان:
  `<queries><package android:name="de.blinkt.openvpn" /></queries>`
  وإلا عاد كشف التثبيت `false` دائمًا على Android 11+.

## حدود موثقة (لا تُتجاوز في الواجهة)

1. `Started` تعني «قُبل الطلب» لا «النفق يعمل» — وقد يفتح
   التطبيق الآخر شاشته (إذن/بيانات دخول) قبل بدء النفق.
2. `isActive()` آخر حالة معروفة لنا فقط؛ لا تعرف إيقاف المستخدم
   للنفق من التطبيق الآخر ولا سقوطه وحده.
3. إشعار الاتصال يأتي من التطبيق الآخر لا منا.
4. بروفايلات OpenVPN لا تمر ببيلدر sing-box: فلا بروكسي per-app
   ولا DNS switcher ولا TCP No Delay ولا kill-switch داخل التطبيق
   عليها؛ وخدمتا VPN (محركنا والتطبيق الآخر) لا تعملان معًا —
   فقبل التشغيل يوقف المستدعي المحرك الآخر أولًا.
5. رموز معاملات Binder مشتقة من ترتيب الدوال في ملف `.aidl`؛
   فإن أعاد المنبع ترتيب الدوال في إصدار لاحق وجب تحديث الثوابت
   `TRANSACT_*` في `AndroidOpenVpnRemote`. المسار الأمتن على
   المدى الطويل: إضافة نسخ ملفات `.aidl` نفسها (المعفاة من
   GPLv2) إلى `feature/connection/src/main/aidl/` وترك توليد
   الـStub للبناء — قرار تكامل لاحق، لا يدخل في هذا الغلاف.

## الاختبار اليدوي على الهاتف (بعد CI)

1. ثبّت OpenVPN for Android من Play.
2. استورد `.ovpn` حقيقيًا في تطبيقنا ← اتصل ← عند أول مرة وافق
   في شاشة التطبيق الآخر ثم أعد الاتصال.
3. تأكد أن النفق يعمل (الإشعار من التطبيق الآخر) ثم افصل من
   تطبيقنا.
4. بدّل إلى بروفايل sing-box وبالعكس — يجب أن يتوقف المحرك
   الأول صراحة قبل تشغيل الثاني.
