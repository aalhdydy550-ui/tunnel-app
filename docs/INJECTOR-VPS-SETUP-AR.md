# إعداد السيرفر لميزة «نت مجاني» (SNI + نفق SSH)

دليل إعداد الطرف السيرفري على جهاز Oracle Always Free (Ubuntu، معالج ARM64
Ampere A1). هذا الدليل يكمل مستند `ORACLE-FREE-VPS-AR.md` (الحصول على الجهاز
نفسه) — هنا نجهّز ما فوق الجهاز: OpenSSH + stunnel.

## الفكرة في سطرين

```
الهاتف ──TLS (SNI = المضيف المعفى، المنفذ 443)──► stunnel ──► sshd (127.0.0.1:22) ──► الإنترنت
```

الهاتف لا يتصل بـ SSH مباشرة على المنفذ 22؛ بل يفتح اتصال TLS على المنفذ 443
بحيث يرى مقدّم الخدمة اسم المضيف المعفى (SNI) فقط، وداخل هذا الاتصال المشفر
يجري بروتوكول SSH. لذلك نحتاج على السيرفر طبقتين:

1. **OpenSSH (sshd)** — خادم SSH الحقيقي، يستمع على المنفذ 22 محليًا.
2. **stunnel** — ينهي TLS على المنفذ 443 ويمرّر الاتصال إلى sshd.

## تنبيه مهم: لماذا stunnel وليس sing-box؟

**sing-box لا يملك inbound من نوع SSH إطلاقًا** — الـ `ssh` في sing-box هو
outbound (عميل) فقط، أي أن sing-box يستطيع أن *يتصل* بخادم SSH لكنه لا
يستطيع أن *يستقبل* اتصالات SSH. لذلك لا يصلح sing-box طرفًا سيرفريًا هنا
أبدًا. الطرف السيرفري الصحيح هو **OpenSSH + stunnel**، وهذا ما يطابقه
التطبيق: التطبيق يبني مستند sing-box فيه outbound من نوع `socks` يشير إلى
`127.0.0.1:10810` فقط، وطبقة TLS تعيش في عميل JSch داخل التطبيق لا في
sing-box (`InjectorConfig` + `TlsSniSocketFactory`).

## 0) المتطلبات المسبقة

- جهاز Oracle Ubuntu (ARM64 — `uname -m` يطبع `aarch64`). لا خطوات خاصة
  بـ ARM64: stunnel وopenssl متوفران في مستودعات Ubuntu لـ arm64 كما لـ x86.
- وصول SSH إلى الجهاز (المستخدم `ubuntu` ومفتاحك).
- عنوان IP العام للجهاز (من لوحة OCI أو من `curl -4 ifconfig.me`).

## 1) تثبيت stunnel وOpenSSH

```bash
sudo apt update
sudo apt install -y stunnel4 openssh-server
```

تأكد أن sshd يعمل ويستمع على 22:

```bash
sudo systemctl enable --now ssh
sudo ss -tlnp | grep ':22'
```

## 2) توليد شهادة ذاتية (self-signed) بـ openssl

الشهادة هنا **ليست** شهادة للمضيف المعفى (SNI) — الشهادة للسيرفر نفسه،
والتطبيق يقبل أي شهادة على هذا المسار تحديدًا (لأن التحقق العادي من
السلسلة/اسم المضيف لا يمكن أن ينجح أبدًا حين يكون SNI اسمًا لا تملكه).
لذلك شهادة ذاتية تكفي تمامًا، ولا تشترِ شهادة ولا تستخدم Let's Encrypt
(شهادة حقيقية لن تغيّر شيئًا في هذا المسار).

```bash
sudo mkdir -p /etc/stunnel
sudo openssl req -x509 -nodes -days 3650 -newkey rsa:2048 \
  -keyout /etc/stunnel/stunnel.key \
  -out /etc/stunnel/stunnel.crt \
  -subj "/CN=localhost"

sudo chmod 600 /etc/stunnel/stunnel.key
sudo chmod 644 /etc/stunnel/stunnel.crt
```

- مدة الشهادة 10 سنوات (3650 يومًا) حتى لا تنتهي فجأة.
- `CN=localhost` مقصود: الاسم هنا لا يُتحقق منه أصلًا؛ لا تضع اسم المضيف
  المعفى ولا اسم نطاقك.

## 3) ملف `/etc/stunnel/stunnel.conf` كاملًا

```ini
; /etc/stunnel/stunnel.conf — «نت مجاني»: قبول 443 وتحويلها إلى sshd المحلي.
; الطرف السيرفري (server mode): ينهي TLS ثم يمرر الاتصال كما هو إلى 127.0.0.1:22.

client = no
cert = /etc/stunnel/stunnel.crt
key = /etc/stunnel/stunnel.key
pid = /var/run/stunnel4/stunnel.pid

; اختياري: سجل منفصل لاستكشاف الأخطاء (الافتراضي syslog).
; output = /var/log/stunnel4/stunnel.log

[ssh-tls]
accept = 443
connect = 127.0.0.1:22
```

ملاحظات على الملف:

- `accept = 443`: المنفذ الذي يراه مقدّم الخدمة والهاتف. لا تغيّره.
- `connect = 127.0.0.1:22`: sshd المحلي. لا تضع IP الجهاز العام هنا —
  الاتصال يبقى داخل الجهاز ولا يخرج إلى الشبكة.
- لا تضع `verify` ولا `CAfile`: الشهادة ذاتية والتحقق من العميل غير مطلوب.
- لا توجد أي إشارة إلى SNI في هذا الملف: stunnel ينهي أي TLS يصل إلى 443
  أيًا كان SNI — والتطبيق هو من يضع SNI (المضيف المعفى) في ClientHello.

## 4) تفعيل الخدمة

تنبيه Ubuntu/Debian الخاص: حزمة stunnel4 **معطّلة افتراضيًا** في
`/etc/default/stunnel4` (`ENABLED=0`)، وبدون تغييرها لن تعمل الخدمة حتى
لو نفّذت `systemctl enable`:

```bash
# 1) فعّل الحزمة:
sudo sed -i 's/^ENABLED=0/ENABLED=1/' /etc/default/stunnel4
grep '^ENABLED=' /etc/default/stunnel4   # يجب أن تطبع ENABLED=1

# 2) فعّل الخدمة وشغّلها:
sudo systemctl enable --now stunnel4
sudo systemctl status stunnel4 --no-pager

# 3) تأكد أن 443 تستمع فعلًا:
sudo ss -tlnp | grep ':443'
```

يجب أن ترى سطرًا مثل `LISTEN ... *:443 ... stunnel`. إن لم يظهر، راجع
قسم استكشاف الأخطاء أدناه.

## 5) فتح المنفذ 443 في قائمة أمان OCI

المنفذ 443 مغلق افتراضيًا على مستوى شبكة Oracle السحابية، وفتحه من
الجهاز نفسه لا يكفي:

1. لوحة OCI ← Networking ← Virtual Cloud Networks ← اختر الـ VCN.
2. Security Lists ← اختر قائمة الأمان المرتبطة بالـ Subnet.
3. Add Ingress Rule:
   - **Source Type:** CIDR — **Source CIDR:** `0.0.0.0/0`
   - **IP Protocol:** TCP
   - **Destination Port Range:** `443`
4. احفظ. (لا حاجة لقاعدة Egress إضافية: الرد على اتصال وارد مسموح
   افتراضيًا.)

ثم تحقق من جدار الجهاز نفسه (صور Ubuntu من Oracle عادةً بلا قواعد حاجبة،
لكن تحقق):

```bash
sudo ufw status          # إن كان UFW موجودًا: يجب أن يسمح 443 أو يكون inactive
sudo iptables -L -n | head -20
```

إن كان UFW مفعّلًا وحاجبًا: `sudo ufw allow 443/tcp`.

## 6) إنشاء مستخدم SSH مخصص بكلمة مرور

لا تستخدم مستخدم `ubuntu` ولا حسابًا بصلاحيات sudo لهذا الغرض — أنشئ
مستخدمًا مخصصًا بلا أي صلاحيات إدارية:

```bash
sudo adduser tunnel
```

سيطلب منك الأمر تعيين كلمة مرور (هي كلمة المرور التي ستدخلها في رابط
`ssh://` داخل التطبيق) وبعض الحقول الاختيارية (اتركها فارغة بـ Enter).
ثم تأكد أنه ليس في مجموعة sudo:

```bash
groups tunnel   # يجب ألا تظهر sudo في القائمة
```

وأخيرًا تأكد أن sshd يسمح بالمصادقة بكلمة المرور (صور Ubuntu الافتراضية
تسمح بها، لكن تحقق):

```bash
grep -E '^[[:space:]]*PasswordAuthentication' /etc/ssh/sshd_config
```

- إن طبع `PasswordAuthentication yes` فالوضع سليم.
- إن طبع `no` فغيّره إلى `yes` ثم أعد تشغيل sshd:
  `sudo sed -i 's/^PasswordAuthentication no/PasswordAuthentication yes/' /etc/ssh/sshd_config && sudo systemctl restart ssh`
- `AllowTcpForwarding` يبقى على افتراضيه (`yes`) — التطبيق يعتمد على
  Dynamic Forward (SOCKS) داخل جلسة SSH.

## 7) الاختبار من سطر الأوامر

من أي جهاز (حاسوبك أو الجهاز نفسه عبر عنوانه العام):

```bash
openssl s_client -connect <PUBLIC_IP>:443 -servername scorm.ekb.eg
```

ما الذي يجب أن تراه:

- **نجاح:** يكتمل الـ handshake وتظهر أسطر مثل
  `SSL handshake has read ... bytes and written ... bytes` ثم
  `Verify return code: 18 (self-signed certificate)` أو `21`.
  كود 18/21 **متوقع** — الشهادة ذاتية، وهذا طبيعي هنا وليس خطأ.
  بعد الـ handshake قد تظهر أسطر `SSH-2.0-OpenSSH...` (بانر sshd عبر
  النفق) — هذه علامة ممتازة: تعني أن stunnel يمرّر فعلًا إلى sshd.
  اخرج بـ `Ctrl+C`.
- **فشل فوري (`connect: Connection refused`):** المنفذ مغلق — راجع
  الخطوتين 4 و5.
- **مهلة (`connect: Operation timed out`):** قاعدة أمان OCI لم تُفتح
  أو جدار الجهاز يحجب — راجع الخطوة 5.

ثم اختبر SSH نفسه عبر النفق من التطبيق (لا توجد أداة سطر أوامر سهلة
لـ SSH-over-TLS): أضف البروفايل في التطبيق واتصل من شاشة «نت مجاني»
حسب دليل الاختبار `~/workspace/tunnel-app-work/injector/TEST-GUIDE-AR.md`.

## 8) استكشاف الأخطاء الشائعة

| العرض | السبب المرجح | الحل |
|---|---|---|
| `systemctl status stunnel4` يظهر `failed` أو `inactive (dead)` | `ENABLED=0` في `/etc/default/stunnel4` | نفّذ خطوة التفعيل في §4 ثم `sudo systemctl restart stunnel4` |
| `failed: Address already in use` في السجل | شيء آخر يستمع على 443 (مثلًا خادم ويب) | `sudo ss -tlnp \| grep ':443'` لمعرفة صاحب المنفذ؛ أوقفه أو غيّر منفذه |
| `error: certificate ... No such file` | مسار الشهادة/المفتاح في `stunnel.conf` لا يطابق الملفات | صحح `cert`/`key` في §3؛ تأكد من وجود `/etc/stunnel/stunnel.crt` و`.key` |
| `Permission denied` على المفتاح | صلاحيات المفتاح خاطئة | `sudo chmod 600 /etc/stunnel/stunnel.key` ثم أعد التشغيل |
| `Connection refused` من خارج الجهاز | stunnel لا يعمل، أو OCI مغلق | `sudo systemctl status stunnel4` + `ss -tlnp \| grep 443`، ثم راجع §5 |
| مهلة (timeout) من خارج الجهاز | قاعدة OCI أو جدار الجهاز | راجع §5؛ جرّب من الجهاز نفسه `openssl s_client -connect 127.0.0.1:443 -servername scorm.ekb.eg` لعزل المشكلة: إن نجح محليًا فالمشكلة في الشبكة لا في stunnel |
| التطبيق يقول «فشل اتصال الحقن» | قد يكون SSH نفسه (مستخدم/كلمة مرور/منفذ) لا TLS | تحقق من `sudo systemctl status ssh`، ومن أن البروفايل في التطبيق على المنفذ **443** لا 22، ومن أن كلمة المرور صحيحة بتجربة `ssh tunnel@127.0.0.1` من الجهاز نفسه |
| الشهادة انتهت | مرّت 10 سنوات على التوليد | أعد توليدها بأوامر §2 ثم `sudo systemctl restart stunnel4` |

عرض السجلات:

```bash
sudo journalctl -u stunnel4 --no-pager -n 50
sudo systemctl status stunnel4 --no-pager
sudo ss -tlnp | grep -E ':(443|22)'
```

## 9) قواعد ذهبية

1. **المنفذ في بروفايل SSH داخل التطبيق يجب أن يكون 443 لا 22.**
   الرابط يكون بالصيغة `ssh://user:password@host:443` — المنفذ 22 يتصل
   بـ SSH مباشرة بلا TLS وبلا SNI، فيراه مقدّم الخدمة اتصال SSH عاديًا
   ويخصمه من الباقة (أو يحجبه).
2. **لا تضع اسم المضيف المعفى في الشهادة** — الشهادة للسيرفر نفسه، وSNI
   يضعه التطبيق في الـ handshake فقط.
3. **لا تشارك كلمة مرور مستخدم `tunnel` مع أحد**، ولا تضعها في أي ملف
   في المستودع — مكانها الوحيد رابط `ssh://` الذي تدخله أنت بنفسك في
   شاشة الاستيراد داخل التطبيق.
4. **لا تمنح مستخدم `tunnel` صلاحيات sudo** ولا مفتاح SSH لمستخدم
   `ubuntu` في هذا السياق.
5. بعد أي تعديل في `stunnel.conf` أو الشهادة:
   `sudo systemctl restart stunnel4` ثم أعد اختبار §7.
