package dev.aalhdydy550.tunnel.feature.connection

import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.profile.OvpnConfParser
import dev.aalhdydy550.tunnel.feature.connection.openvpn.OpenVpnLauncher
import dev.aalhdydy550.tunnel.feature.connection.openvpn.OpenVpnRemote
import dev.aalhdydy550.tunnel.feature.connection.openvpn.OpenVpnResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات [OpenVpnLauncher] بالخدمة الوهمية ([FakeRemote]) — بلا
 * أندرويد حقيقي وبلا تطبيق OpenVPN for Android مثبَّت.
 *
 * المنطق المختبر هنا هو الغلاف نفسه: ترتيب الفحوص (تثبيت ← نص
 * سليم ← إرسال)، ورفض النص الفاسد قبل أي اتصال، وتعيين حالة
 * [OpenVpnLauncher.isActive]، وتمرير نص الكونفيج المخزَّن كما هو.
 * البروفايلات الصالحة تُبنى بالمحلّل الحقيقي [OvpnConfParser]
 * (فـ`outboundJson` بصيغته المخزَّنة فعلًا)، والنصوص الفاسدة
 * تُبنى كـJSON يدويًا بحقل `config` كما يخزّنه المحلّل.
 *
 * ملاحظة: المنشئ الداخلي `OpenVpnLauncher(remote)` هو الفاصل
 * القابل للاختبار — الإنتاج يستخدم منشئ Hilt بالـ`Context`؛
 * والربط الحقيقي بالـBinder (رموز المعاملات، الـParcel) لا يُختبر
 * هنا — الحكم النهائي له هو CI ثم اختبار يدوي على الهاتف ضد
 * التطبيق الآخر المثبَّت فعلًا.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenVpnLauncherTest {

    /** خدمة وهمية تسجّل ما وصلها وتعيد النتيجة التي يضبطها الاختبار. */
    private class FakeRemote(
        var installed: Boolean = true,
        var startResult: OpenVpnRemote.StartResult = OpenVpnRemote.StartResult.Started,
    ) : OpenVpnRemote {
        var receivedConfig: String? = null
        var startCalls = 0
        var disconnectCalls = 0

        override fun isInstalled(): Boolean = installed

        override suspend fun startVpn(configText: String): OpenVpnRemote.StartResult {
            startCalls++
            receivedConfig = configText
            return startResult
        }

        override fun disconnect() {
            disconnectCalls++
        }
    }

    private val validOvpnText = """
        client
        dev tun
        proto tcp
        remote vpn.example.com 443 tcp
        resolv-retry infinite
        nobind
        cipher AES-256-GCM
        auth SHA256
        remote-cert-tls server
    """.trimIndent()

    private val validProfile: Profile =
        OvpnConfParser.parseConf(validOvpnText, "my-vpn.ovpn")

    /** بروفايل بنص مخزَّن [configText] (يبنيه الاختبار يدويًا كـJSON). */
    private fun profileWithStoredText(configText: String): Profile =
        validProfile.copy(
            outboundJson = buildJsonObject { put("config", configText) }.toString(),
        )

    @Test
    fun `valid stored text starts the vpn and marks it active`() = runTest {
        val remote = FakeRemote()
        val launcher = OpenVpnLauncher(remote)

        val result = launcher.connect(validProfile)

        assertEquals(OpenVpnResult.Started, result)
        assertEquals(1, remote.startCalls)
        assertEquals(
            OvpnConfParser.configTextOf(validProfile.outboundJson),
            remote.receivedConfig,
        )
        assertTrue(launcher.isActive())
    }

    @Test
    fun `blank stored text is rejected before start`() = runTest {
        val remote = FakeRemote()
        val launcher = OpenVpnLauncher(remote)

        val result = launcher.connect(profileWithStoredText("   \n  "))

        assertTrue(result is OpenVpnResult.InvalidConfig)
        assertTrue((result as OpenVpnResult.InvalidConfig).message.isNotBlank())
        assertEquals(0, remote.startCalls)
        assertFalse(launcher.isActive())
    }

    @Test
    fun `unparseable stored json is rejected before start`() = runTest {
        val remote = FakeRemote()
        val launcher = OpenVpnLauncher(remote)

        val result = launcher.connect(validProfile.copy(outboundJson = "not json at all"))

        assertTrue(result is OpenVpnResult.InvalidConfig)
        assertEquals(0, remote.startCalls)
        assertFalse(launcher.isActive())
    }

    @Test
    fun `text without remote is rejected before start`() = runTest {
        val remote = FakeRemote()
        val launcher = OpenVpnLauncher(remote)

        val result = launcher.connect(
            profileWithStoredText("client\ndev tun\nproto tcp\n"),
        )

        assertTrue(result is OpenVpnResult.InvalidConfig)
        assertEquals(0, remote.startCalls)
        assertFalse(launcher.isActive())
    }

    @Test
    fun `dev tap text is rejected before start`() = runTest {
        val remote = FakeRemote()
        val launcher = OpenVpnLauncher(remote)

        val result = launcher.connect(
            profileWithStoredText("client\ndev tap\nremote vpn.example.com 1194\n"),
        )

        assertTrue(result is OpenVpnResult.InvalidConfig)
        assertEquals(0, remote.startCalls)
        assertFalse(launcher.isActive())
    }

    @Test
    fun `not installed returns NotInstalled without starting`() = runTest {
        val remote = FakeRemote(installed = false)
        val launcher = OpenVpnLauncher(remote)

        val result = launcher.connect(validProfile)

        assertEquals(OpenVpnResult.NotInstalled, result)
        assertEquals(0, remote.startCalls)
        assertFalse(launcher.isActive())
    }

    @Test
    fun `remote failure maps to Failed and clears active`() = runTest {
        val remote = FakeRemote(
            startResult = OpenVpnRemote.StartResult.Failed("رفض التطبيق الكونفيج"),
        )
        val launcher = OpenVpnLauncher(remote)

        val result = launcher.connect(validProfile)

        assertEquals(OpenVpnResult.Failed("رفض التطبيق الكونفيج"), result)
        assertEquals(1, remote.startCalls)
        assertFalse(launcher.isActive())
    }

    @Test
    fun `remote needs-approval maps to Failed with approval message`() = runTest {
        val remote = FakeRemote(
            startResult = OpenVpnRemote.StartResult.NeedsApproval(
                "وافق في التطبيق الآخر ثم أعد الاتصال",
            ),
        )
        val launcher = OpenVpnLauncher(remote)

        val result = launcher.connect(validProfile)

        assertTrue(result is OpenVpnResult.Failed)
        assertTrue((result as OpenVpnResult.Failed).message.contains("وافق"))
        assertEquals(1, remote.startCalls)
        assertFalse(launcher.isActive())
    }

    @Test
    fun `auth-user-pass profile is handed over, credentials asked by the other app`() = runTest {
        val remote = FakeRemote()
        val launcher = OpenVpnLauncher(remote)
        val authProfile = OvpnConfParser.parseConf(
            """
            client
            dev tun
            proto udp
            remote vpn.example.com 1194 udp
            auth-user-pass
            """.trimIndent(),
            "auth-vpn.ovpn",
        )

        val result = launcher.connect(authProfile)

        // The launcher does not block on credentials: it hands the
        // text over and the other app asks the user at connect time.
        assertEquals(OpenVpnResult.Started, result)
        assertEquals(1, remote.startCalls)
        assertTrue(remote.receivedConfig.orEmpty().contains("auth-user-pass"))
        assertTrue(launcher.isActive())
    }

    @Test
    fun `disconnect stops and clears active flag`() = runTest {
        val remote = FakeRemote()
        val launcher = OpenVpnLauncher(remote)
        assertEquals(OpenVpnResult.Started, launcher.connect(validProfile))
        assertTrue(launcher.isActive())

        launcher.disconnect()

        assertEquals(1, remote.disconnectCalls)
        assertFalse(launcher.isActive())
    }

    @Test
    fun `isActive is false before any connect`() {
        val launcher = OpenVpnLauncher(FakeRemote())

        assertFalse(launcher.isActive())
    }

    @Test
    fun `playStoreUrl points at the OpenVPN for Android listing`() {
        val launcher = OpenVpnLauncher(FakeRemote())

        assertEquals(
            "https://play.google.com/store/apps/details?id=de.blinkt.openvpn",
            launcher.playStoreUrl(),
        )
    }

    @Test
    fun `isInstalled delegates to the remote`() {
        assertTrue(OpenVpnLauncher(FakeRemote(installed = true)).isInstalled())
        assertFalse(OpenVpnLauncher(FakeRemote(installed = false)).isInstalled())
    }

    @Test
    fun `validateConfigText accepts a normal stored text`() {
        val launcher = OpenVpnLauncher(FakeRemote())

        assertNull(
            launcher.validateConfigText(
                OvpnConfParser.configTextOf(validProfile.outboundJson),
            ),
        )
    }
}
