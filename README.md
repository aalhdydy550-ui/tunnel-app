# Ayman Tunnel Plus

تطبيق VPN شخصي (استخدام شخصي أولًا، والمتجر لاحقًا) — بناء نظيف
(clean-room) بالكامل: نواة واحدة sing-box، وكل أنواع اتصالات التطبيق
الأصلي محسّنة، وبلا إعلانات وبلا تتبّع وبلا أي بيانات دخول مدمجة.

- **اسم العرض:** Ayman Tunnel Plus — يظهر في الشريط العلوي داخل
  التطبيق وفي شاشة About (لا على الأيقونة فقط).
- **أيقونة التطبيق:** شعار الهكر (مقنّع + بوابة نفق + كود ثنائي،
  سماوي/أخضر على أسود، بحروف ATP) — أيقونة adaptive: الخلفية سوداء
  والشعار في منطقة الأمان (لا قصّ لأي جزء)؛ والملف الأصلي محفوظ في
  `~/workspace/tunnel-ui-mockup/media-generation-ayman-tunnel-plus-hacker-logo-0-a85658c4-60a6-4bae-a895-ae0fabb8048f.webp`.
- **شكل الواجهة:** على نمط التطبيق الأصلي (Hamo) — أُعيد تنفيذه
  بـCompose من موارده المفكوكة ساكنًا (لا نسخ كود ولا رسوم ولا اسم):
  أصفر ذهبي `#F8CE29` على خلفية `#F8F9FF`، وقائمة جانبية برأس أصفر،
  وشريط علوي بالشعار والاسم، وبانر داكن بالشعار، وزر اتصال دائري كبير
  كحلي بحلقة صفراء، وقائمة خوادم ببطاقات بيضاء. بلا إعلانات إطلاقًا
  (البانر الإعلاني في الأصلي مستبعَد).
- **اسم الحزمة:** `dev.aalhdydy550.tunnel` (لا يتغيّر — الاسم المعروض
  منفصل عن الحزمة).
- **المستودع:** `github.com/aalhdydy550-ui/tunnel-app` (عام).
- **الإصدار:** v0.1.0.

---

## ما يفعله التطبيق (v0.1)

- استيراد خوادم من روابط (`vless` / `vmess` / `trojan` / `ss` /
  `hysteria2` / `tuic` / `ssh`) أو ملف WireGuard `.conf` أو ملف OpenVPN
  `.ovpn` (عبر تطبيق OpenVPN for Android المنفصل) أو JSON خام
  لـsing-box أو نسخة احتياطية JSON من التطبيق نفسه أو ملف تصدير مقفول
  `.aytun` (مشفر بكلمة سر)؛ ولصق من الحافظة؛ واختيار ملف من التخزين؛
  أو رابط اشتراك يُجلب كل يوم تلقائيًا (التحديث يستبدل خوادم الاشتراك
  فقط ويُبقي اليدوية).
- قائمة خوادم مع علم الدولة (إيموجي من رمز ISO يضعه المستخدم)،
  واختبار تأخير لكل خادم، واختيار أسرع سيرفر تلقائيًا (أقل تأخير،
  وعند التساوي تُفضَّل Hysteria2 ثم WireGuard).
- اتصال/قطع بزر واحد، مع حالة واضحة وسرعة حية (↓/↑) على الشاشة.
  تحميل اختياري.
- **استقرار بلا قطع في المنتصف:** خدمة أمامية بإشعار دائم، وإعادة
  اتصال تلقائية مع backoff عند تغيّر الشبكة أو فشل النفق أو موته
  الصامت (يكشفه نبض حياة كل 60 ثانية)، واستثناء من تحسين البطارية،
  وإعادة تشغيل عند الإقلاع (اختياري)، وإحياء الخدمة إن قتلها النظام
  (START_STICKY + جلسة محفوظة)، ومفتاح قتل (Kill Switch) داخل التطبيق
  (يبقي الـTUN مفتوحًا عند السقوط غير المقصود — ولا ينجو من موت
  العملية؛ وAlways-on في النظام يبقى الأقوى) إضافةً إلى Kill Switch
  عبر إعدادات أندرويد، وتسجيل سبب كل انقطاع.
- حظر إعلانات اختياري بقائمة محدّثة صغيرة تُنزَّل مرة واحدة (انظر
  «بيانات التوجيه» أدناه).
- نسخة احتياطية يومية تلقائية + تصدير يدوي JSON + تصدير مشفّر مقفول
  (`.aytun` — AES-256-GCM + PBKDF2) بمشاركة QR لبروفايل واحد.
- **وضع بروكسي بدون VPN:** خدمة أمامية عادية (بلا إذن VPN) تشغّل
  المحرك نفسه بلا مدخل `tun` — للتطبيقات التي تضبط بروكسي يدويًا على
  `127.0.0.1:10808`؛ وبلا توجيه تلقائي وبلا per-app وبلا kill-switch
  وبلا التقاط DNS في هذا الوضع.
- **مجموعات خوادم ذكية + اختبار سرعة حقيقي:** مجموعات (الأسرع /
  عشوائي / تناوبي) مع تجاوز تلقائي محدود (محاولتان كحد أقصى) عند فشل
  الاتصال؛ واختبار سرعة تنزيل فعلي (5–25 ميجا، سقف 10 ثوانٍ) بضغطة
  المستخدم فقط أثناء اتصال قائم؛ وزر «اتصال عشوائي».

## تغطية البروتوكولات (كل أنواع الأصلي، محسّنة — لا حذف)

| النوع في الأصلي | عندنا | الحالة |
|---|---|---|
| OpenVPN | تطبيق OpenVPN for Android المنفصل عبر Remote API (كود `remoteExample` بترخيص Apache-2.0) — استيراد `.ovpn` + اتصال كامل؛ وبلا أي كود ics-openvpn (GPL-2.0-only) | ✅ جولة «التطبيق الأحسن» (البند 1) |
| SSH | sing-box `ssh` أصلي | ✅ v0.1 |
| VLESS / VMess / Trojan / Shadowsocks | sing-box أصلي | ✅ v0.1 |
| WireGuard | sing-box أصلي | ✅ v0.1 |
| Hysteria v1 | محذوف عمدًا (Hysteria2 يغطيه) | ✅ v0.1 |
| Hysteria2 | sing-box أصلي + Salamander + قفز منافذ | ✅ v0.1 |
| DNSTT | مكتبة DNSTT الأصلية | المرحلة 2 |
| ZIVPN | **غير منفذ** — البحث الاستكشافي (Spike 2026-11-25) انتهى إلى (ج) لا مصدر موثوق: صيغة السلك ليست Hysteria2 ولا مواصفة علنية ولا عميل/خادم مفتوح المصدر؛ والخطافات (`ProfileType.ZIVPN` + outbound `socks`) باقية | ⏳ بلا موعد — يُعاد النظر فقط إن ظهر مصدر موثوق |
| — | TUIC (إضافة) | ✅ v0.1 |

التفاصيل الكاملة (جدول المقارنة + الأوضاع الأربعة + الإصلاحات
الأمنية): [`docs/CONNECTIONS.md`](docs/CONNECTIONS.md).
المرحلة 2: [`docs/PHASE2.md`](docs/PHASE2.md).

## المضيفون العامّون (مأخوذون من الأصلي ومحسّنون)

المضيفون التالية **عامّة** (خدمات فحص اتصال وDNS عامة)، مأخوذة من
الأصلي ومحسّنة:

- **اختبار التأخير / نبض الحياة:** `https://www.gstatic.com/generate_204`
  و`https://www.google.com/generate_204` (من الأصلي) +
  `https://cp.cloudflare.com/generate_204` (إضافة). التحسين: فحص متعدد
  النقاط يختار الأسرع تلقائيًا بدل افتراض مضيف واحد.
- **DNS:** `1.1.1.1` / `223.5.5.5` / `dns.google` (من الأصلي).
  التحسين: عند كل اتصال يُقاس أسرع محلِّل (TCP إلى منفذ DoT 853)
  ويُستخدم؛ وإن لم يرد أحد يُستخدم `tls://1.1.1.1` كبديل ثابت —
  فالـDNS لا يمنع الاتصال أبدًا. المحللات الصينية غير ذات الصلة
  بمصر (`114DNS` و`1.12.12.12` و`119.29.29.29`) **غير موجودة أصلًا
  في قائمتنا**؛ و`223.5.5.5` بقي لأنه DNS الافتراضي في الأصلي
  نفسه، والاختيار يقيس السرعة الفعلية من مصر فالمحلِّل البطيء لا
  يفوز أبدًا. DoT في v0.1؛ وDoH (`https://dns.google/dns-query`)
  وقائمة محللات قابلة للتنزيل/التحديث في المرحلة 2.

### تطبيق بنود تحليل المضيفات (§4 — 10 بنود)

| # | البند | v0.1 |
|---|---|---|
| 1 | تأخير متعدد النقاط (Google + Cloudflare + ثالثة) يختار الأسرع | ✅ (`EndpointProbe`، ومهلة الفحص 10ث لضعف الشبكة)؛ واختبار URL المدمج في sing-box لكل سيرفر ⏳ المرحلة 2 |
| 2 | أسرع DNS تلقائيًا + DoH/DoT + حذف الصيني غير ذي الصلة | ✅ DoT + الاختيار التلقائي + الحذف؛ وDoH + قائمة قابلة للتنزيل ⏳ المرحلة 2 |
| 3 | مصادر توجيه محدّثة + تحديث داخل التطبيق | ✅ sing-geoip/sing-geosite (الإعلانات `ads-all` 8,175 بايت، مولَّدة من domain-list-community + Loyalsoldier) + تحديث داخلي (إعادة تنزيل كل 30 يومًا + زر إعادة محاولة)؛ وفحص تحديث عبر GitHub API لمستودعنا ⏳ المرحلة 2 |
| 4 | الأوضاع الأربعة + حظر إعلانات محلي | ✅ حظر الإعلانات بقاعدة محلية (`geosite-category-ads-all`) بلا أي اتصال بخوادم إعلانات؛ والأوضاع الأربعة ⏳ المرحلة 2 |
| 5 | روابط مجتمع لقنواتنا لا v2rayNG | ✅ زر «المستودع على GitHub» في بطاقة About (لا قنوات تليجرام لهذا التطبيق) |
| 6 | DNS الافتراضي مع إمكانية التغيير | ✅ الافتراضي `1.1.1.1` + البدائل `dns.google` / `one.one.one.one` (نفس خدمة 8.8.8.8)؛ وإعداد يدوي ⏳ المرحلة 2 |
| 7 | عناوين TUN/DNS المحلية | ✅ قيم محلية فقط (TUN ‏`172.19.0.1/30` + `fdfe:dcba:9876::1/126`، DNS المحلي) — ليست مضيفات خارجية |
| 8 | الإعلانات والتتبّع (21 مضيفًا) | ✅ محذوفة بالكامل — لا AdMob ولا قياس ولا موافقة GDPR؛ وتأكدنا بـgrep: صفر تطابق في الكود |
| 9 | الخادم التجريبي `v2ray.cool` | ✅ ممنوع نهائيًا (انظر أدناه) |
| 10 | خدمات Google/Play/Firebase | ✅ بلا Firebase/AdMob؛ ورابط Play لصفحة التطبيق نفسه يُضاف عند النشر فقط |

### مستبعَد صراحةً: الخادم التجريبي في `assets/v2ray_config.json`

الخادم التجريبي المدمج في الأصلي (`v2ray.cool` — VMess +
Shadowsocks بهوية دخول مكتوبة) **مستبعَد تمامًا**: موروث من قالب
للمستخدم؛ لا نستخدمه ولا نستخرج قيمه ولا نتصل به إطلاقًا. بيانات
دخول وُجدت في تحليل لا تُستخدم للوصول لأي سيرفر. تطبيقنا أصلًا بلا
أي قالب افتراضي وبلا أي بيانات دخول مدمجة — الخوادم تأتي من استيراد
المستخدم فقط.

### النت المجاني — مولّد الحمولة + الحقن HTTP + فاحص الهوستات (جولة «التطبيق الأحسن» — البند 3)

خيارات الشبكات المجانية المدمجة في الأصلي (Vodafone Free Internet /
Vodafone Free Flex / اتصالات / WE) تظهر أسماؤها فقط — وقيم مضيفيها
وحمولاتها **لم تُستخرج أبدًا** (طبقة renz مشفّرة)، فلا قيمة مضيف
لنسخها. اعتمادنا يبقى على مجموعة الكونفيجات
(`~/workspace/tunnel-app-configs/`: المضيف الوحيد غير الموثّق
`scorm.ekb.eg` لوي + أي مضيف يُوثَّق لاحقًا بالتجربة) عبر مولّد
الحمولة — لا على قيم مخفية من الأصلي. والتطبيق عميل
فقط: لا يأتي مع سيرفرات مجانية.

ما نُفّذ في البند 3: مولّد قوالب حمولة (`CONNECT`/`GET`/`POST` +
قالب مخصص، مع تطهير CRLF) + حقن HTTP عبر بروكسي الشريحة إلى JSch
(ينجح فقط على `200`/`101`) + فاحص هوستات (`CONNECT host:443` إلى
البروكسي، والهوستات من الجدول المرجعي أو إدخال المستخدم — **بلا
قائمة هوستات مدمجة ولا قيم بروكسي مخترعة**) — كلها في شاشة «نت
مجاني» (وضع SNI القديم باقٍ وهو الافتراضي). **القاعدة الصادقة:**
كل ميزة «نت مجاني» موسومة «جرّب على شريحتك — غير موثقة»، والحكم
الوحيد اختبار ميداني على شريحة المستخدم مع عدّاد البيانات؛ ونجاح
الفحص يعني «البروكسي رد 200» فقط — لا يعني نتًا مجانيًا.
التفاصيل: [`docs/FILES.md`](docs/FILES.md) §8.

## بيانات التوجيه: استبدال لا حذف

الأصلي يضمّن `geoip.dat` (15.3 MB) + `geosite.dat` (2.2 MB) قديمة.
تطبيقنا يضمّن **لا شيء**: rule-sets صغيرة (`.srs`) محدّثة تُنزَّل عند
أول تشغيل (قائمة الإعلانات 8,175 بايت — تحققنا من الرابط والحجم).
**القاعدة الإلزامية:** إن غابت البيانات وقت التشغيل، التطبيق لا
يعطّل ولا يفشل — يرجع للوضع الآمن (كل الترافيك عبر النفق) مع تنبيه
في الواجهة وزر إعادة محاولة. القاعدة منفذة ومختبرة بوحدة
(`RoutingFallback` + `RoutingDataManager`).
التفاصيل: [`docs/FILES.md`](docs/FILES.md).

## الملفات الداخلية (ملخص التحسين)

- التخزين: قاعدة Room واحدة بمخطط مكتوب + ترحيل حقيقي، بدل 7 مخازن
  MMKV بمفاتيح مشفّرة ودوال يدوية.
- النسخ الاحتياطي: JSON نظيف + نسخة يومية تلقائية في التخزين الخاص
  بالتطبيق + تصدير عبر SAF — بدل مجلد `backups/` وFileDialog على
  التخزين الخارجي.
- الأعلام: إيموجي من رمز دولة ISO (بلا 206 صورة) — ولا نخمّن الدولة
  أبدًا.
- بلا ثنائيات خارجية منسوخة في v0.1 (كل شيء في libbox).
التفاصيل: [`docs/FILES.md`](docs/FILES.md).

## البصمة (الحجم والذاكرة) — أهداف تصميم، لا أرقام مدّعاة

الهاتف المستهدف اقتصادي (Infinix SMART 5 من الفئة الدنيا)، فالبصمة
الخفيفة أولوية:

- APK صغير: نواة واحدة بدل 6 محركات + عمليات خارجية؛ R8/minify +
  shrinkResources مفعّلة؛ شريحة arm64-v8a فقط؛ بلا ملفات geoip
  مدمجة (وفّرنا 17.5 MB).
- عملية واحدة بدل عمليات منفصلة (لا `android:process` في أي مكوّن).
- الملفات الشخصية في Room (كيلوبايتات)؛ السجلات محدودة (آخر 100
  حدث في الذاكرة + سجل sing-box محدود بـ1000 سطر، بلا ملف سجل).
- بلا تحميل لبيانات التوجيه كلها في الذاكرة دفعة واحدة.
- بلا خدمات خلفية زائدة عن خدمة الـVPN نفسها.

**لا أرقام مقاسة بعد.** الأرقام الحقيقية (حجم APK، وحجم التثبيت،
والرام أثناء الاتصال، ومساحة بيانات التطبيق) تُقاس من الـAPK المبني
فعليًا عندما يبنيه GitHub Actions، بأداة `scripts/measure-footprint.sh`،
وتُسجَّل هنا حينها. لا يُدَّعى أي رقم لم يُقَس.
كذلك وقت الاتصال وزمن أول بايت وسرعة التحميل: تُقاس على الجهاز (السرعة
الحية تظهر على الشاشة دائمًا)، ولا أرقام مدّعاة قبل أول قياس حقيقي.

## المكدّس

Kotlin أصلي + Jetpack Compose؛ بنية nowinandroid مخفّفة (6 وحدات) +
Hilt + Room + WorkManager + Retrofit/OkHttp؛ النواة sing-box عبر
libbox؛ minSdk 24، targetSdk 34، compileSdk 36، JDK 17، Gradle
8.14.8، Kotlin DSL.

الإصدارات (مُتحقق منها على Maven Central / Google Maven 2026-11-23):
Kotlin 2.3.21، KSP 2.3.14، AGP 8.13.2، Compose BOM 2026.11.01، Room
2.8.5، WorkManager 2.12.0، Hilt 2.60.1، hilt-navigation-compose
1.4.0، Retrofit 2.12.0، OkHttp 4.12.0، kotlinx-serialization 1.13.0،
coroutines 1.11.0، lifecycle 2.11.1، datastore 1.3.0، JUnit 4.13.2،
Robolectric 4.15.1، MockK 1.14.3.

## libbox — كيف وُصل

**لا يوجد AAR منشور لـlibbox على Maven Central** (بُحث عنه بنفسي:
`io.nekohasekai:libbox` بلا نتائج). لذلك يُبنى الـAAR من مصدر
sing-box داخل البناء:

- محليًا: `./scripts/build-libbox.sh` (يستنسخ sing-box **v1.14.4**،
  ثم `make lib_install` ثم `make lib_android`، وينسخ الناتج إلى
  `feature/connection/libs/libbox.aar`). يحتاج Go 1.25.5 + JDK 17 +
  Android NDK 28.0.13004108.
- في CI: نفس الخطوات في `.github/workflows/build.yml` قبل Gradle.
- الـAAR منتج بناء ولا يُرفع لـgit (انظر `.gitignore`)؛ وواجهة Java
  المستخدمة مُتحقق منها بتفكيك APK الرسمي sing-box-for-android
  1.14.4 (dexdump).

## البناء

```bash
./scripts/build-libbox.sh        # يبني libbox.aar أولًا
./gradlew :app:assembleDebug     # ثم الـAPK
./gradlew testDebugUnitTest      # الاختبارات
```

الـCI (GitHub Actions): عند الدفع على `main` يبني libbox ثم يشغّل
الاختبارات ثم يبني الـAPK ويرفعه كـartifact، مع طباعة حجمه.
**ملاحظة:** ملف `.github/workflows/build.yml` لا يُرفع عبر GitHub
API (403) — يُرفع عبر موقع github.com.

## GPL-3.0 وعواقب النشر على المتجر

النواة sing-box مرخّصة GPL-3.0، والتطبيق كله GPL-3.0 (انظر
`LICENSE`). عند النشر على المتجر:

- **الخيار 1:** نشر المصدر كاملًا (المستودع عام أصلًا) — يفي
  بالتزامات GPL-3.0.
- **الخيار 2:** استبدال النواة بـXray-core (MPL-2.0) أو v2ray-core
  (MIT) قبل النشر — يتطلب إعادة كتابة طبقة `feature/connection`
  (الواجهات `TunnelBackend` / `PlatformInterface` تجعل الاستبدال
  موضعيًا، لكنه عمل كبير).

## ما يشمله v0.1 وما يستثنيه

**يشمل:** كل ما سبق — البروتوكولات السبعة عبر sing-box، والاستيراد
بأشكاله (روابط + ملفات + اشتراكات بتحديث يومي تلقائي)، والاستقرار (خدمة أمامية + إعادة اتصال + نبض حياة + بطارية
+ إقلاع + إحياء + kill switch داخل التطبيق)، والسرعة (أسرع سيرفر + mux/fragment
الاختياريان + DNS الأسرع + سرعة حية تظهر على الشاشة)، والبصمة
(R8 + arm64 فقط + بلا ملفات مدمجة)، وحظر الإعلانات مع fallback آمن،
والنسخ الاحتياطي، والأعلام بالإيموجي — **إضافةً إلى جولة «التطبيق
الأحسن» (2026-11-24/25):** مولّد حمولة + حقن HTTP + فاحص هوستات،
والتصدير المقفول `.aytun` + QR، واستيراد `.ovpn` والاتصال عبر تطبيق
OpenVPN for Android المنفصل، ووضع البروكسي بدون VPN، ومفتاح القتل
داخل التطبيق، واختبار سرعة حقيقي + مجموعات ذكية مع تجاوز محدود.

**يستثني (المرحلة 2، انظر `docs/PHASE2.md`):** ZIVPN (البحث
الاستكشافي انتهى إلى «لا مصدر موثوق» — يبقى غير منفذ)، وDNSTT،
والأوضاع الأربعة للتوجيه + البروكسي لكل تطبيق، وتشفير قاعدة
البيانات (SQLCipher)، واختبار تأخير عبر النفق (CommandClient).
التفاصيل الكاملة لكل بند من الجولة: [`docs/FILES.md`](docs/FILES.md)
§8–§14، والتقرير النهائي للجولة في
`~/workspace/tunnel-app-work/better-app-round/REPORT-AR.md`.

## قرارات اتخذتها بتفويض المستخدم («اعمل افضل حاجه للتطبيق»)

الأساس ثابت (نواة واحدة، كل الاتصالات، الاستقرار، السرعة، البصمة،
الاسم داخل الواجهة، الملفات محسّنة، البنية جاهزة للمرحلة 2). وفيما
عدا ذلك اخترتُ الأفضل وسجّلتُ السبب:

- **بلا شاشة بداية (splash):** الشاشة الرئيسية تفتح مباشرة والاسم في
  الشريط العلوي — فيرى المستخدم الاسم فور فتح التطبيق بلا انتظار.
  شاشة البداية تجميل اختياري لاحقًا.
- **التنبيهات:** إشعار الخدمة الأمامية دائم وبسيط (عنوان + حالة + زر
  قطع) — بلا أصوات وبلا اهتزاز.
- **الوضع الافتراضي لكل شيء آمن:** حظر الإعلانات متوقف حتى يختاره
  المستخدم؛ التشغيل عند الإقلاع متوقف؛ إعادة الاتصال تعمل دائمًا
  (لا خيار لإيقافها — الاستقرار أساسي).
- **السرعة الحية من عدّادات النظام (TrafficStats)** بدل واجهة
  إحصاءات libbox — أبسط، ولا تحتاج CommandClient، وتقيس التدفق
  الحقيقي للنفق.
- **النسخ الاحتياطي اليومي تلقائي** (WorkManager) في التخزين الخاص —
  بلا إذن تخزين وبلا تعريض لقائمة الخوادم.
- **اختيار DNS عند كل اتصال** (أسرع محلِّل عام) بدل إعداد يدوي —
  القرار الصحيح لمعظم المستخدمين، مع إبقاء الخيار اليدوي للمرحلة 2.
- **الاختبارات:** كل منطق نقي (محللات، باني، تأخير، إعادة اتصال،
  سرعة، توجيه، نسخ احتياطي) مغطى باختبارات وحدة حقيقية على JVM
  (انظر «الاختبارات» أدناه) — بلا اختبار شكلي.

## الاختبارات

اختبارات وحدة حقيقية (JVM، بلا محاكي):

- `ProfileLinkParserTest` (21 اختبارًا): كل المخططات + mux/fragment/
  قفز المنافذ + SSH + رفض ZIVPN.
- `WireGuardConfParserTest` (4) و`SingBoxConfigBuilderTest` (9،
  منها قاعدة الإعلانات + DNS).
- `LatencyTesterTest` (3) و`ReconnectPolicyTest` (2) و
  `ProtocolSpeedTest` (5) و`SpeedTesterTest` (1) و`ProfileBackupTest`
  (4) و`RoutingFallbackTest` (4) و`RoutingDataManagerTest` (2) و
  `EndpointProbeTest` (3) و`DnsSelectorTest` (3).
- `ProfileDaoTest` (Robolectric): عمليات Room.
- `SpeedMonitorTest` (3) و`HeartbeatMonitorTest` (3).

**المجموع قبل جولة «التطبيق الأحسن»: 70 اختبارًا.** وأضافت الجولة
اختبارات جديدة لكل بند (كلها أُعيد تشغيلها في محاكي kotlinc قبل
الدفع، وCI الأخضر على `c916100` هو الحكم النهائي):

| البند | الاختبارات الجديدة | العدد |
|---|---|---|
| 3 — الحمولة + الحقن + الفاحص | `PayloadGeneratorTest` + `HttpInjectorTest` + `HostScannerTest` | 40 |
| 4 — التصدير المقفول | `LockedExportTest` + `QrCodeEncoderTest` | 15 |
| 1 — `.ovpn` | `OvpnConfParserTest` + `OpenVpnLauncherTest` (+ تمديد 3 ملفات اختبار قائمة) | 37 + 14 |
| 5أ — وضع البروكسي | `ProxyOnlyConfigTest` + `ConnectRouteTest` | 12 |
| 2 — Kill Switch | `KillSwitchManagerTest` (+ تأكيدات `Blocked` في اختبار البلاطة) | 16 |
| 6 — السرعة + المجموعات | `GroupAutoSelectTest` + `SpeedTestTest` + `ProfilesViewModelTest` (Robolectric) + 5 اختبارات واجهة في المحاكي | 22 + 9 + 3 + 5 |

(تُشغَّل بـ`./gradlew testDebugUnitTest`؛ وتتحقق محليًا بأداة kotlinc
لأن Gradle daemon مستحيل على هذا الـVM — انظر المهارة
`~/workspace/custom-skills/tunnel-app-stack/SKILL.md`).
البناء الكامل (APK) يُتحقق منه عبر GitHub Actions بعد الرفع.

## بنية المشروع

```
app/                  التطبيق (MainActivity + TunnelApp + البيان)
core/ui/              الثيم + مكونات Compose المشتركة
core/data/            النماذج + المحللات + الباني + المستودعات +
                      الشبكات (تأخير/سرعة/DNS/نقاط) + التوجيه + النسخ
                      + المجموعات (`groups/`) + التصدير المقفول (`backup/`)
core/database/        Room (الكيان + DAO + قاعدة البيانات v2)
feature/connection/   المحرك + الخدمة + المراقبون + الواجهة +
                      الحقن (`injector/` + `injector/payload/`) +
                      OpenVPN المنفصل (`openvpn/`) + البروكسي
                      (`ProxyOnlyService`) + Kill Switch + اختبار السرعة
feature/profiles/     شاشتا الخوادم والاستيراد + المجموعات
                      (`GroupsScreen`) + التصدير المقفول
scripts/              build-libbox.sh + measure-footprint.sh
.github/workflows/    build.yml (يُرفع عبر الموقع)
docs/                 CONNECTIONS.md + FILES.md + PHASE2.md
```

---

## English summary

**Ayman Tunnel Plus** — a personal VPN app (personal use first, store
later), clean-room built: one sing-box core, every connection type of
the original app improved, no ads, no tracking, no embedded
credentials. Package `dev.aalhdydy550.tunnel`, repo
`github.com/aalhdydy550-ui/tunnel-app`, v0.1.0.

- Protocols: VLESS / VMess / Trojan / Shadowsocks / WireGuard /
  Hysteria2 / TUIC / SSH via sing-box in v0.1; OpenVPN via the
  separate OpenVPN for Android app (Remote API, Apache-2.0) since the
  "better-app round"; DNSTT in Phase 2; ZIVPN stays unimplemented
  (Spike 2026-11-25, decision (c) — no trusted source).
- Stability: foreground service + auto-reconnect with backoff +
  heartbeat + battery exemption + boot auto-start + START_STICKY
  revival + in-app kill switch (holds the TUN open on unintended
  failure; does not survive process death — system Always-on stays
  the stronger cover).
- Speed: fastest-server selection, optional mux / TLS fragment,
  fastest-DNS selection, live throughput shown on screen, plus a real
  speed test (Cloudflare download, user-triggered only) and smart
  server groups (fastest / random / round-robin) with capped failover
  (2 attempts).
- Free internet: SNI injection plus an HTTP-injection payload
  generator and host scanner — all labelled "try on your own SIM,
  unverified"; no host or proxy value is ever copied from another app.
- Locked export: `.aytun` files (AES-256-GCM + PBKDF2) with QR
  sharing for a single profile; losing the password loses the export.
- Proxy-only mode: a plain foreground service (no VPN permission)
  running the same engine without a `tun` inbound, for apps that set
  a manual proxy on 127.0.0.1:10808.
- Footprint: R8 + arm64-v8a only + no embedded geoip data; measured
  numbers are recorded only after the first CI build
  (`scripts/measure-footprint.sh`).
- libbox is built from sing-box v1.14.4 source (no Maven coordinates
  exist); see `scripts/build-libbox.sh`.
- License: GPL-3.0 (sing-box core). Store publishing requires either
  full source publication or swapping the core for Xray-core
  (MPL-2.0) / v2ray-core (MIT).
