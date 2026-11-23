# الاتصالات: الأصلي ← Ayman Tunnel Plus

> Comparison of the original app's (Hamo Tunnel Plus 3.3) connections
> with this app's improved design. Facts about the original come from
> the static reverse-engineering report (`~/workspace/hamo-rev/`).
> The rule throughout: **keep every connection type the original had,
> improved — never remove one.**

## 1. Architecture: one core instead of three services + five processes

| الأصلي | Ayman Tunnel Plus | الحالة |
|---|---|---|
| 3 خدمات VPN منفصلة: `RenzOpenVPNservice` / `SSHVPNService` / `V2RayVpnService` (الأخيرة Always-on وفي عملية منفصلة `:RunSoLibV2RayDaemon`) | خدمة واحدة `TunnelVpnService` في العملية الرئيسية، نواة واحدة sing-box/libbox | ✅ v0.1 |
| مسار TUN خاص بكل محرك: tun2socks (BadVPN 1.999.127) + pdnsd كعمليات خارجية منسوخة إلى filesDir | TUN واحد من `VpnService.Builder`، وDNS مدمج في sing-box (بلا pdnsd) | ✅ v0.1 |
| Hysteria v1 + Hysteria v2 كعمليتين خارجيتين، وDNSTT كعملية خارجية من خدمة SSH، وZIVPN مخفي | لا عمليات خارجية في v0.1 إطلاقًا — كل شيء داخل libbox | ✅ v0.1 |
| خطوات الاتصال: اختيار السيرفر ← اختبار تأخير `testAllTcping/testAllRealPing` وفرز ← `VpnService.prepare()` ← تشغيل الخدمة المناسبة | نفس التجربة: اختبار تأخير (`LatencyTester`, TCP connect) + فرز ← `VpnService.prepare()` ← خدمة واحدة | ✅ v0.1 |

لماذا يهم التوحيد: مسار اتصال واحد وTUN واحد يعنيان نقاط فشل أقل —
وهذا ما يجعل الاستقرار ممكنًا أصلًا (خدمة أمامية + إعادة اتصال +
keep-alive على مسار واحد بدل 3 خدمات و5 عمليات).

## 2. Protocol coverage: every original type, improved

| النوع في الأصلي | عندنا | الحالة | التحسين |
|---|---|---|---|
| OpenVPN (OpenVPN3 3.11.6) | ics-openvpn | ⏳ المرحلة 2 | sing-box لا يغطي OpenVPN؛ يُضاف كمكتبة ics-openvpn (GPL) بمحركها الخاص، لا كعملية خارجية منسوخة |
| SSH (Trilead + DynamicPortForwarder) | sing-box `ssh` outbound أصلي | ✅ v0.1 | نفس الوظيفة (dynamic forward) داخل النواة الموحدة؛ روابط `ssh://user[:password]@host:port`؛ مفاتيح SSH عبر JSON خام |
| V2Ray: VLESS / VMess / Trojan / Shadowsocks (Xray 1.8.25 عبر JNI) | sing-box أصلي | ✅ v0.1 | نواة واحدة بدل JNI + عمليات؛ TLS سليم افتراضيًا؛ `allow_insecure` فقط إن طلب الرابط صراحةً |
| WireGuard | sing-box `wireguard` أصلي | ✅ v0.1 | استيراد من ملف `.conf`؛ المسار الأصلي في النواة بدل خدمة منفصلة |
| Hysteria v1 (عملية خارجية) | **محذوف عمدًا — لا v1 إطلاقًا** | ✅ v0.1 | v1 قديم؛ Hysteria2 يغطيه كاملًا |
| Hysteria2 (عملية خارجية) | sing-box `hysteria2` أصلي | ✅ v0.1 | QUIC + Brutal (عبر `up_mbps`/`down_mbps`) + تمويه Salamander + قفز منافذ (`server_ports`، منذ sing-box 1.11) — كلها من الرابط نفسه |
| DNSTT (Slow DNS، عملية خارجية) | مكتبة DNSTT الأصلية | ⏳ المرحلة 2 | عميل clean-room من مكتبة DNSTT الأصلية (dnstt-client) كمكتبة نظامية، لا ثنائية منسوخة |
| ZIVPN (مخفي في libogkayouy.so) | عملية خارجية + SOCKS5 | ⏳ المرحلة 2 | `ProfileType.ZIVPN` + `Backend.EXTERNAL_PROCESS` + فرع SOCKS في الباني جاهزة؛ التنفيذ clean-room فقط (انظر `docs/PHASE2.md`) |
| — (غير موجود في الأصلي) | TUIC | ✅ v0.1 | إضافة: sing-box أصلي، بلا كلفة إضافية |

## 3. Latency test + fastest server

| الأصلي | عندنا | الحالة |
|---|---|---|
| `testAllTcping` / `testAllRealPing` + فرز حسب النتائج | `LatencyTester.tcpPing` (TCP connect) + فرز + `ProtocolSpeed.pickFastest` | ✅ v0.1 |
| اختبار التأخير عبر `generate_204` | نبض الحياة (heartbeat) يستخدم `https://www.gstatic.com/generate_204` نفسه للكشف عن الموت الصامت | ✅ v0.1 |
| — | اختيار أسرع سيرفر تلقائيًا: أقل تأخير أولًا، وعند التساوي تُفضَّل البروتوكولات الأسرع (Hysteria2 ← WireGuard ← TUIC ← VLESS …) | ✅ v0.1 |
| — | اختبار سرعة تحميل اختياري داخل التطبيق (`SpeedTester`, Cloudflare `__down`) + عرض السرعة الحية (TrafficStats) | ✅ v0.1 |
| اختبار تأخير عبر النفق (URL test في sing-box) | — | ⏳ المرحلة 2 (يحتاج CommandClient) |

## 4. Routing: the four modes + per-app proxy

| الأصلي | عندنا | الحالة |
|---|---|---|
| 4 قوالب JSON: global / white / black / white_iran | v0.1: global فقط (كل الترافيك عبر النفق). الأوضاع الأربعة كقواعد sing-box + rule-sets محدّثة | ⏳ المرحلة 2 |
| `geoip.dat` (15.3 MB) + `geosite.dat` (2.2 MB) مدمجة وقديمة | لا ملفات مدمجة؛ rule-sets صغيرة (`.srs`) تُنزَّل عند أول تشغيل (انظر `docs/FILES.md`) | ✅ v0.1 (الاستبدال) |
| قائمة 241 حزمة للبروكسي الانتقائي (`proxy_packagename.txt`, ملف ثابت) | بلا ملف ثابت؛ الاختيار لكل تطبيق من الواجهة (عبر `OverrideOptions` + `PlatformInterface`) | ⏳ المرحلة 2 (السباكة موجودة) |
| DNS افتراضي 1.1.1.1 / 223.5.5.5 + pdnsd خارجي | DNS مدمج: `tls://1.1.1.1` (بعيد) + local detour، بلا عمليات خارجية | ✅ v0.1 |
| إعدادات mux وfragment وallow_insecure وper-app proxy وprefer IPv6 وsniffing | كلها مدعومة في sing-box أصلًا: mux عبر `?mux=1` في روابط VLESS/Trojan؛ TLS fragment عبر `?fragment=1`؛ ECH في إعدادات TLS (المرحلة 2)؛ sniffing مفعّل في TUN؛ prefer IPv6 عبر `domain_strategy` (المرحلة 2) | ✅ v0.1 (mux/fragment)، ⏳ الباقي |

Mapping the four modes (Phase 2 design, recorded here so the
implementation does not need re-design):
- **global** → `final: proxy` (v0.1 behavior).
- **white** (whitelist) → rule-sets for the listed domains/categories → proxy, `final: direct`.
- **black** (blacklist) → rule-sets for the listed domains/categories → direct, `final: proxy`.
- **white_iran** → `geoip-ir` + `geosite-ir` rule-sets → direct, `final: proxy`.

## 5. Security fixes (vs the original)

| الأصلي | عندنا |
|---|---|
| `usesCleartextTraffic=true`, بلا networkSecurityConfig | `usesCleartextTraffic=false` + `network_security_config.xml` يمنع cleartext كليًا |
| `allowBackup=true` | `allowBackup=false` |
| بيانات دخول تجريبية مدمجة في `assets/v2ray_config.json` | بلا أي بيانات دخول مدمجة إطلاقًا — لا قالب افتراضي أصلًا |
| توقيع debug العام (شهادة أندرويد التجريبية) | الإصدار يُوقَّع بمفتاح المالك (خطوة النشر)؛ لا شهادة debug في الإصدار |
| TLS مع `allow_insecure` كخيار شائع | التحقق من TLS سليم افتراضيًا؛ `insecure` فقط إن طلبه الرابط صراحةً |
| 10 مكتبات أصلية + ثنائيات منسوخة في filesDir | libbox واحد كمكتبة AAR نظامية؛ بلا ثنائيات منسوخة |

## 6. Speed: what v0.1 does (no unmeasured numbers claimed)

- **No artificial speed cap in code.** Hysteria2 `up_mbps`/`down_mbps`
  تأتي من الرابط فقط؛ وغيابها يعني BBR (لا قيد). لا throttling في أي
  طبقة.
- **MTU 9000** على TUN (gVisor) — رأس أقل لكل حزمة.
- **Brutal congestion control** في Hysteria2 عند ضبط السرعات؛ وBBR
  وإلا.
- **Mux** (h2mux) و**TLS fragment** اختياريان عبر `?mux=1` / `?fragment=1`.
- **DNS سريع**: TLS إلى 1.1.1.1 + local fallback؛ بلا pdnsd خارجي.
- **قياس لا ادعاء**: وقت الاتصال وزمن أول بايت وسرعة التحميل تُقاس
  على الجهاز (زر «اختبار السرعة» + السرعة الحية في الواجهة)؛ لا أرقام
  سرعة مدّعاة في هذا المستند قبل أول قياس حقيقي — انظر قسم البصمة
  في README.
