package dev.aalhdydy550.tunnel.feature.connection.openvpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.profile.OvpnConfParser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * يشغّل بروفايل OpenVPN عبر تطبيق **OpenVPN for Android** المثبَّت
 * منفصلًا (حزمة `de.blinkt.openvpn`) — بلا دمج لأي كود من
 * ics-openvpn في تطبيقنا.
 *
 * ## كيف يعمل (Remote API — موثّقة في `feature/connection/libs/README-openvpn.md`)
 *
 * 1. يتحقق من تثبيت التطبيق الآخر (`PackageManager`).
 * 2. يقرأ نص `.ovpn` المخزَّن من [Profile.outboundJson] عبر
 *    [OvpnConfParser.configTextOf] ويرفض النص الفارغ أو الفاسد
 *    **قبل** أي اتصال بالتطبيق الآخر.
 * 3. يربط خدمة `de.blinkt.openvpn.api.ExternalOpenVPNService`
 *    (الإجراء `de.blinkt.openvpn.api.IOpenVPNAPIService`) ويستدعي
 *    `startVPN(inlineConfig)` — فيرسل نص الكونفيج كاملًا (بما فيه
 *    الكتل المضمّنة `<ca>`/`<cert>`/`<key>`) فينشئ التطبيق الآخر
 *    بروفايلًا مؤقتًا باسم «Remote APP VPN» ويشغّله.
 * 4. `disconnect()` يستدعي `disconnect()` على الخدمة نفسها.
 *
 * ## حدود صريحة (اقرأها قبل الاعتماد على هذه الواجهة)
 *
 * - **لا توجد قراءة حالة متزامنة في الـAIDL:** واجهة
 *   `IOpenVPNAPIService` المنشورة لا تحوي `getStatus()` ولا تسجيل
 *   ردّ حالة (تحقّقنا من ملف `.aidl` نفسه في `master`)، فـ[isActive]
 *   تعيد آخر حالة **نعرفها نحن فقط**: `true` بعد `connect()` ناجح
 *   إلى أن يُستدعى [disconnect] أو يفشل اتصال لاحق. لا تعرف إن
 *   أوقف المستخدم النفق من التطبيق الآخر أو من إشعاره، ولا إن
 *   سقط الاتصال وحده.
 * - **`Started` تعني «قُبل الطلب» لا «النفق يعمل»:** التطبيق الآخر
 *   قد يفتح شاشته هو (إذن VPN من النظام، أو اسم مستخدم/كلمة مرور
 *   إن احتاجها البروفايل) قبل أن يبدأ النفق فعلًا؛ وإشعار الاتصال
 *   يأتي منه لا منا.
 * - **بيانات الدخول:** إن احتاج البروفايل `auth-user-pass` بلا
 *   بيانات مضمّنة، فالتطبيق الآخر هو من يطلبها من المستخدم عند
 *   الاتصال — لا نعرض حقلًا لها ولا نمرّرها.
 * - **موافقة لمرة واحدة:** أول اتصال يستدعي `prepare()`؛ فإن لم
 *   يكن تطبيقنا مسموحًا له بالتحكم بعدُ، يفتح الغلاف شاشة الموافقة
 *   في التطبيق الآخر ويعيد [OpenVpnResult.Failed] برسالة عربية
 *   تطلب الموافقة وإعادة المحاولة — الاتصال نفسه لا يبدأ حينها.
 * - **خدمتا VPN لا تعملان معًا:** قبل تشغيل بروفايل OpenVPN يجب
 *   إيقاف محركنا أولًا (والعكس)، وهذا واجب الطرف المستدعي
 *   (`ConnectionViewModel`) لا هذا الغلاف.
 *
 * ## Clean-room
 *
 * كُتب هذا الغلاف من دراسة واجهة الـAIDL المنشورة
 * (`IOpenVPNAPIService.aidl`) وخدمة `ExternalOpenVPNService`
 * وملف `remoteExample/LICENSE.remoteExample` (Apache-2.0) — بلا نسخ
 * أي ملف أو مقطع من ics-openvpn. وبما أن شجرتنا لا تحوي ملفات
 * `.aidl` للواجهة، يُستدعى Binder مباشرة عبر [IBinder.transact]
 * برموز معاملات مشتقة من ترتيب الدوال في ملف `.aidl` (انظر
 * الثوابت `TRANSACT_*` أدناه)؛ فإن أعاد المنبع ترتيب الدوال في
 * إصدار لاحق وجب تحديث الرموز — والمسار الأمتن على المدى الطويل
 * هو إضافة نسخ `.aidl` نفسها (المعفاة من GPLv2 صراحة) إلى الشجرة،
 * وهو قرار تكامل لا يدخل في هذا الملف.
 */
@Singleton
class OpenVpnLauncher private constructor(
    private val context: Context?,
    remoteOverride: OpenVpnRemote?,
) {

    /**
     * نقطة دخول الإنتاج (Hilt): يبني الغلاف على `ApplicationContext`
     * ويربط خدمة التطبيق الآخر عند الحاجة فقط.
     */
    @Inject
    constructor(@ApplicationContext context: Context) : this(context, null)

    /**
     * نقطة دخول الاختبار: يبني الغلاف على [OpenVpnRemote] وهمي بلا
     * `Context` وبلا أندرويد حقيقي — لهذا هي `internal`، فلا يراها
     * Hilt ولا كود الإنتاج. أُضيفت لأن وحدة `feature:connection`
     * الاختبارية بلا Robolectric/MockK، فلا يمكن بناء `Context`
     * حقيقي في اختبار JVM.
     */
    internal constructor(remote: OpenVpnRemote) : this(null, remote)

    private val remote: OpenVpnRemote =
        remoteOverride ?: AndroidOpenVpnRemote(
            requireNotNull(context) { "OpenVpnLauncher needs an application Context" },
        )

    /** آخر حالة نعرفها (انظر حدود [isActive] في توثيق الكلاس). */
    @Volatile
    private var active = false

    /** هل تطبيق OpenVPN for Android مثبَّت على الجهاز؟ */
    fun isInstalled(): Boolean = try {
        remote.isInstalled()
    } catch (_: Exception) {
        false
    }

    /** رابط تثبيت OpenVPN for Android من Google Play. */
    fun playStoreUrl(): String =
        "https://play.google.com/store/apps/details?id=$OPENVPN_PACKAGE"

    /**
     * يشغّل [profile] عبر التطبيق الآخر.
     *
     * الترتيب ثابت: تثبيت ← قراءة الكونفيج ورفض الفاسد ← إرساله
     * وتشغيله. لا يلمس التطبيق الآخر إطلاقًا إن كان النص فاسدًا.
     */
    suspend fun connect(profile: Profile): OpenVpnResult {
        if (!isInstalled()) return OpenVpnResult.NotInstalled
        val configText = try {
            OvpnConfParser.configTextOf(profile.outboundJson)
        } catch (e: Exception) {
            return OpenVpnResult.InvalidConfig(
                "تعذّرت قراءة ملف .ovpn المخزَّن: ${e.message ?: "خطأ غير معروف"}",
            )
        }
        validateConfigText(configText)?.let { problem ->
            return OpenVpnResult.InvalidConfig(problem)
        }
        return when (val result = remote.startVpn(configText)) {
            OpenVpnRemote.StartResult.Started -> {
                active = true
                OpenVpnResult.Started
            }
            is OpenVpnRemote.StartResult.NeedsApproval -> {
                active = false
                OpenVpnResult.Failed(result.message)
            }
            is OpenVpnRemote.StartResult.Failed -> {
                active = false
                OpenVpnResult.Failed(result.message)
            }
        }
    }

    /**
     * يوقف اتصال OpenVPN (أفضل جهد: لا يرمي أبدًا). يمسح علم
     * [isActive] حتى لو تعذّر الوصول إلى التطبيق الآخر.
     */
    fun disconnect() {
        active = false
        try {
            remote.disconnect()
        } catch (_: Exception) {
            // Best effort: the other app may be gone already.
        }
    }

    /**
     * آخر حالة نعرفها فقط: `true` بعد [connect] ناجح إلى أن يُستدعى
     * [disconnect] أو يفشل اتصال لاحق. **لا** تعكس إيقاف المستخدم
     * للنفق من التطبيق الآخر ولا سقوطه وحده — الـAIDL المنشور بلا
     * طريقة لقراءة الحالة (انظر توثيق الكلاس).
     */
    fun isActive(): Boolean = active

    /**
     * فحص أولي رخيص للنص المخزَّن قبل إرساله — دفاع ثانٍ بعد
     * [OvpnConfParser] (الذي يرفض هذه الحالات أصلًا عند الاستيراد):
     * فارغ، أو بلا توجيه `remote`، أو `dev tap`. يعيد رسالة عربية
     * أو `null` إن كان النص سليمًا شكليًا. التحليل الكامل يبقى
     * للتطبيق الآخر (`ConfigParser`) ويظهر خطؤه كـ[OpenVpnResult.Failed].
     */
    internal fun validateConfigText(configText: String): String? {
        if (configText.isBlank()) return "ملف .ovpn فارغ — أعد استيراد الملف"
        var hasRemote = false
        for (rawLine in configText.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("\\s+"))
            when (parts.first().lowercase()) {
                "remote" -> hasRemote = true
                "dev" -> if (parts.getOrNull(1)?.lowercase() == "tap") {
                    return "ملف .ovpn يستخدم dev tap غير المدعوم — التطبيق يدعم dev tun فقط"
                }
            }
        }
        if (!hasRemote) return "ملف .ovpn لا يحتوي على خادم (remote) — أعد استيراد الملف"
        return null
    }

    companion object {
        /** حزمة OpenVPN for Android (الاسم كما في متجر Play). */
        const val OPENVPN_PACKAGE = "de.blinkt.openvpn"
    }
}

/** نتيجة [OpenVpnLauncher.connect]. */
sealed interface OpenVpnResult {
    /** قُبل الطلب وأُرسل الكونفيج إلى التطبيق الآخر (انظر حدود `Started`). */
    data object Started : OpenVpnResult

    /** تطبيق OpenVPN for Android غير مثبَّت — ثبّته من [OpenVpnLauncher.playStoreUrl]. */
    data object NotInstalled : OpenVpnResult

    /** نص `.ovpn` المخزَّن فاسد/فارغ — رُفض قبل أي اتصال بالتطبيق الآخر. */
    data class InvalidConfig(val message: String) : OpenVpnResult

    /** فشل التشغيل (رفض الكونفيج، أو حاجة موافقة، أو تعذّر الربط). */
    data class Failed(val message: String) : OpenVpnResult
}

/**
 * الفاصل القابل للاختبار بين [OpenVpnLauncher] وخدمة التطبيق الآخر:
 * الإنتاج يستخدم [AndroidOpenVpnRemote]، والاختبار يمرّر وهميًا
 * عبر المنشئ الداخلي. كل لمسة لأندرويد تمر من هنا — فالغلاف نفسه
 * منطق خالص قابل لاختبار JVM.
 */
internal interface OpenVpnRemote {

    fun isInstalled(): Boolean

    suspend fun startVpn(configText: String): StartResult

    fun disconnect()

    sealed interface StartResult {
        data object Started : StartResult

        /**
         * يلزم المستخدم خطوة في التطبيق الآخر (موافقة تحكم أو إذن
         * VPN) — وقد فُتحت شاشته بالفعل؛ يعيد الغلاف [OpenVpnResult.Failed]
         * برسالة تطلب الموافقة وإعادة المحاولة.
         */
        data class NeedsApproval(val message: String) : StartResult

        data class Failed(val message: String) : StartResult
    }
}

/**
 * التنفيذ الحقيقي لـ[OpenVpnRemote] فوق Binder مباشرة (بلا ملفات
 * `.aidl` في شجرتنا — انظر توثيق [OpenVpnLauncher] عن رموز
 * المعاملات ومسار `.aidl` الأمتن).
 *
 * دورة الربط: يُربط عند `startVpn` ويبقى مربوطًا بعد `Started`
 * (ليبقى `disconnect()` متزامنًا وسريعًا)، ويُفكّ الربط عند
 * `disconnect()` أو عند أي فشل/موافقة معلّقة. إن ماتت خدمة
 * التطبيق الآخر يصفّر الربط عبر `onServiceDisconnected`.
 */
internal class AndroidOpenVpnRemote(
    private val context: Context,
) : OpenVpnRemote {

    private val boundConnection = AtomicReference<ServiceConnection?>(null)
    private val boundBinder = AtomicReference<IBinder?>(null)

    override fun isInstalled(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                OPENVPN_PACKAGE,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(OPENVPN_PACKAGE, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    override suspend fun startVpn(configText: String): OpenVpnRemote.StartResult {
        if (!isInstalled()) {
            return OpenVpnRemote.StartResult.Failed(
                "تطبيق OpenVPN for Android غير مثبَّت — ثبّته أولًا ثم أعد المحاولة",
            )
        }
        return try {
            withContext(Dispatchers.IO) {
                val binder = bindBinderBlocking()
                    ?: return@withContext OpenVpnRemote.StartResult.Failed(
                        "تعذّر الاتصال بتطبيق OpenVPN for Android — تأكد أنه مثبَّت ومحدَّث ثم أعد المحاولة",
                    )
                val outcome: OpenVpnRemote.StartResult
                try {
                    // 1) هل سمح المستخدم لهذا التطبيق بالتحكم الخارجي؟
                    // prepare()‎ تعيد null إن كان مسموحًا، وإلا Intent
                    // لشاشة الموافقة (ConfirmDialog) في التطبيق الآخر.
                    val grantIntent = transactForIntent(binder, TRANSACT_PREPARE) {
                        it.writeString(context.packageName)
                    }
                    outcome = if (grantIntent != null) {
                        launchIntent(grantIntent)
                        OpenVpnRemote.StartResult.NeedsApproval(
                            "يحتاج OpenVPN for Android موافقتك مرة واحدة للسماح لتطبيقنا " +
                                "بالتحكم — وافق في الشاشة التي فُتحت ثم أعد الاتصال",
                        )
                    } else {
                        // 2) هل يملك التطبيق الآخر إذن VPN من النظام؟
                        // prepareVPNService()‎ تعيد null إن كان الإذن ممنوحًا،
                        // وإلا Intent لشاشة منح الإذن (GrantPermissionsActivity).
                        val vpnGrantIntent =
                            transactForIntent(binder, TRANSACT_PREPARE_VPN_SERVICE)
                        if (vpnGrantIntent != null) {
                            launchIntent(vpnGrantIntent)
                            OpenVpnRemote.StartResult.NeedsApproval(
                                "يحتاج OpenVPN for Android إذن VPN من النظام — وافق في " +
                                    "الشاشة التي فُتحت ثم أعد الاتصال",
                            )
                        } else {
                            // 3) إرسال نص الكونفيج كاملًا وتشغيله. التطبيق الآخر
                            // يحلّل النص بنفسه (ConfigParser)؛ وإن احتاج اسم
                            // مستخدم/كلمة مرور فتح شاشته هو لطلبهما.
                            transactVoid(binder, TRANSACT_START_VPN) {
                                it.writeString(configText)
                            }
                            OpenVpnRemote.StartResult.Started
                        }
                    }
                } catch (e: Exception) {
                    releaseBinder()
                    throw e
                }
                // يُبقى الربط فقط بعد Started (ليبقى disconnect()
                // متزامنًا)؛ في أي نتيجة أخرى يُفكّ فورًا.
                if (outcome != OpenVpnRemote.StartResult.Started) {
                    releaseBinder()
                }
                outcome
            }
        } catch (e: SecurityException) {
            releaseBinder()
            OpenVpnRemote.StartResult.Failed(
                "رفض OpenVPN for Android التحكم: التطبيق غير مصرَّح له — " +
                    "افتح OpenVPN for Android واسمح لتطبيقنا بالتحكم الخارجي ثم أعد المحاولة",
            )
        } catch (e: RemoteException) {
            releaseBinder()
            OpenVpnRemote.StartResult.Failed(
                "رفض OpenVPN for Android الكونفيج: ${e.message ?: "خطأ غير معروف"}",
            )
        } catch (e: Exception) {
            releaseBinder()
            OpenVpnRemote.StartResult.Failed(
                "تعذّر بدء الاتصال عبر OpenVPN for Android: ${e.message ?: "خطأ غير معروف"}",
            )
        }
    }

    override fun disconnect() {
        val binder = boundBinder.get()
        if (binder != null) {
            try {
                transactVoid(binder, TRANSACT_DISCONNECT)
            } catch (_: Exception) {
                // Best effort.
            } finally {
                releaseBinder()
            }
            return
        }
        // بلا ربط حي (مثلًا: ماتت عمليتنا بعد الاتصال): محاولة
        // أخيرة في خيط خلفية — بلا حظر لمستدعي disconnect().
        Thread(
            {
                try {
                    val rebound = bindBinderBlocking(DISCONNECT_BIND_TIMEOUT_MS)
                    if (rebound != null) {
                        try {
                            transactVoid(rebound, TRANSACT_DISCONNECT)
                        } catch (_: Exception) {
                            // Best effort.
                        } finally {
                            releaseBinder()
                        }
                    }
                } catch (_: Exception) {
                    // Best effort.
                }
            },
            "openvpn-disconnect",
        ).apply { isDaemon = true }.start()
    }

    /**
     * يربط الخدمة وينتظر اتصالها حتى [BIND_TIMEOUT_MS] — يعيد
     * الـBinder أو `null`. الربط نفسه متزامن بـCountDownLatch
     * ليصلح من خيط IO ومن خيط disconnect() الخلفي معًا.
     */
    private fun bindBinderBlocking(timeoutMs: Long = BIND_TIMEOUT_MS): IBinder? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<IBinder?>(null)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                // تجاهل ردّ متأخر بعد فكّ ربط سابق (مهلة).
                if (boundConnection.get() === this) {
                    result.set(service)
                    boundBinder.set(service)
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                boundBinder.set(null)
            }
        }
        val intent = Intent(SERVICE_ACTION).setPackage(OPENVPN_PACKAGE)
        val bound = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (_: Exception) {
            false
        }
        if (!bound) return null
        boundConnection.set(connection)
        val connected = try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!connected) {
            releaseBinder()
            return null
        }
        val binder = result.get()
        if (binder == null) {
            releaseBinder()
            return null
        }
        return binder
    }

    /** يفكّ الربط مرة واحدة فقط (آمن عند الاستدعاء المتكرر). */
    private fun releaseBinder() {
        boundBinder.set(null)
        val connection = boundConnection.getAndSet(null) ?: return
        try {
            context.unbindService(connection)
        } catch (_: Exception) {
            // Already unbound / process gone.
        }
    }

    private fun launchIntent(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            // The approval screen could not be opened; the caller
            // still reports NeedsApproval so the UI can explain.
        }
    }

    /** استدعاء Binder بلا قيمة عائدة (void في الـAIDL). */
    private fun transactVoid(binder: IBinder, code: Int, write: (Parcel) -> Unit = {}) {
        transact(binder, code, write) {}
    }

    /** استدعاء Binder يعيد Intent أو null (Parcelable اختياري في الـAIDL). */
    private fun transactForIntent(
        binder: IBinder,
        code: Int,
        write: (Parcel) -> Unit = {},
    ): Intent? {
        var result: Intent? = null
        transact(binder, code, write) { reply ->
            // The typed readParcelable(loader, Class) overload needs
            // API 33+; the untyped one works on our minSdk 24.
            @Suppress("DEPRECATION")
            result = reply.readParcelable(Intent::class.java.classLoader)
        }
        return result
    }

    /**
     * استدعاء Binder خام: رمز الواجهة + المعاملات، ثم
     * `readException()` (يعيد رمي RemoteException/SecurityException
     * التي يرسلها التطبيق الآخر كما هي).
     */
    private fun transact(
        binder: IBinder,
        code: Int,
        write: (Parcel) -> Unit,
        read: (Parcel) -> Unit,
    ) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE_DESCRIPTOR)
            write(data)
            binder.transact(code, data, reply, 0)
            reply.readException()
            read(reply)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    companion object {
        /** حزمة OpenVPN for Android. */
        const val OPENVPN_PACKAGE = OpenVpnLauncher.OPENVPN_PACKAGE

        /** إجراء خدمة الـRemote API كما أعلنته في manifest الخاص به. */
        const val SERVICE_ACTION = "de.blinkt.openvpn.api.IOpenVPNAPIService"

        /** واصف الواجهة في الـAIDL (اسمها الكامل). */
        const val INTERFACE_DESCRIPTOR = "de.blinkt.openvpn.api.IOpenVPNAPIService"

        /**
         * رموز معاملات Binder — مشتقة من ترتيب إعلان الدوال في
         * `main/src/main/aidl/de/blinkt/openvpn/api/IOpenVPNAPIService.aidl`
         * (master ‏660901c): AIDL يرقّم من `FIRST_CALL_TRANSACTION`
         * (1) بالترتيب: getProfiles=1، startProfile=2،
         * addVPNProfile=3، **startVPN=4**، **prepare=5**،
         * **prepareVPNService=6**، **disconnect=7**، pause=8،
         * resume=9، removeProfile=10، protectSocket=11،
         * addNewVPNProfile=12، startVPNwithExtras=13،
         * addNewVPNProfileWithExtras=14، getDefaultProfile=15،
         * setDefaultProfile=16. لا توجد getStatus ولا تسجيل ردّ
         * حالة في الملف المنشور.
         */
        const val TRANSACT_START_VPN = 4
        const val TRANSACT_PREPARE = 5
        const val TRANSACT_PREPARE_VPN_SERVICE = 6
        const val TRANSACT_DISCONNECT = 7

        /** مهلة انتظار ربط الخدمة عند الاتصال. */
        const val BIND_TIMEOUT_MS = 10_000L

        /** مهلة ربط محاولة الفصل الاحتياطية (بلا ربط حي). */
        const val DISCONNECT_BIND_TIMEOUT_MS = 5_000L
    }
}
