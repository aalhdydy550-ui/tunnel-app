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
| `assets/v2ray_config.json`: قالب افتراضي يحوي بيانات دخول تجريبية مدمجة | **لا قالب افتراضي إطلاقًا** — لا ملف إعدادات مدمج، ولا أي بيانات دخول في التطبيق. الخوادم تأتي من استيراد المستخدم فقط |

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
