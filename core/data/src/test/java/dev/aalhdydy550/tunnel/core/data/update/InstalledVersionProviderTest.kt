package dev.aalhdydy550.tunnel.core.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [PackageManagerInstalledVersionProvider] with a
 * mocked Context/PackageManager (MockK intercepts the stub calls, so
 * no Android runtime is needed).
 *
 * Note: `Build.VERSION.SDK_INT` reads 0 on the JVM, so these tests
 * exercise the legacy branches (getPackageInfo(String, Int) and
 * getInstallerPackageName). The API 33/30 branches are the same
 * values through the newer overloads and are verified on the phone
 * test instead (see the report).
 */
class InstalledVersionProviderTest {

    private fun providerWith(
        versionName: String,
        versionCode: Int,
        installer: String?,
    ): InstalledVersionProvider {
        val context = mockk<Context>()
        val packageManager = mockk<PackageManager>()
        val info = PackageInfo().apply {
            this.versionName = versionName
            this.versionCode = versionCode
        }
        every { context.packageName } returns "dev.aalhdydy550.tunnel"
        every { context.packageManager } returns packageManager
        every { packageManager.getPackageInfo("dev.aalhdydy550.tunnel", 0) } returns info
        every { packageManager.getInstallerPackageName("dev.aalhdydy550.tunnel") } returns installer
        return PackageManagerInstalledVersionProvider(context)
    }

    @Test
    fun `reads version name code and installer`() {
        val installed = providerWith("0.1.0", 3, "org.fdroid.fdroid").installed()
        assertEquals("0.1.0", installed.versionName)
        assertEquals(3L, installed.versionCode)
        assertEquals("org.fdroid.fdroid", installed.installerPackageName)
        assertEquals(InstallSource.FDROID, installed.installSource)
    }

    @Test
    fun `null installer means sideloaded`() {
        val installed = providerWith("0.1.0", 1, null).installed()
        assertEquals(InstallSource.SIDELOADED, installed.installSource)
    }

    @Test
    fun `play installer is detected`() {
        val installed = providerWith("0.2.0", 5, "com.android.vending").installed()
        assertEquals(InstallSource.PLAY, installed.installSource)
    }
}
