package dev.aalhdydy550.tunnel.core.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {

    // parseSemver

    @Test
    fun `parses tags with and without v`() {
        assertEquals(Semver(0, 1, 0), parseSemver("v0.1.0"))
        assertEquals(Semver(0, 1, 0), parseSemver("0.1.0"))
        assertEquals(Semver(2, 0, 0), parseSemver("V2.0.0"))
    }

    @Test
    fun `parses short versions leniently`() {
        assertEquals(Semver(1, 2, 0), parseSemver("1.2"))
        assertEquals(Semver(1, 0, 0), parseSemver("1"))
    }

    @Test
    fun `parses pre-release suffixes`() {
        assertEquals(Semver(2, 0, 0, "beta.1"), parseSemver("v2.0.0-beta.1"))
        assertEquals(Semver(1, 0, 0, "rc.1"), parseSemver("1.0.0-rc.1+build.5"))
    }

    @Test
    fun `rejects non-versions`() {
        assertNull(parseSemver(null))
        assertNull(parseSemver(""))
        assertNull(parseSemver("abc"))
        assertNull(parseSemver("v"))
        assertNull(parseSemver("1.2.3.4"))
        assertNull(parseSemver("1.2.x"))
    }

    // Semver comparison

    @Test
    fun `numeric comparison wins over lexicographic`() {
        assertTrue(Semver(0, 2, 0) > Semver(0, 1, 0))
        assertTrue(Semver(0, 1, 1) > Semver(0, 1, 0))
        assertTrue(Semver(1, 0, 0) > Semver(0, 9, 9))
        assertTrue(Semver(0, 10, 0) > Semver(0, 9, 0))
        assertEquals(0, Semver(0, 1, 0).compareTo(Semver(0, 1, 0)))
    }

    @Test
    fun `release beats pre-release of the same base`() {
        assertTrue(Semver(1, 0, 0) > Semver(1, 0, 0, "beta.1"))
        assertTrue(Semver(1, 0, 0, "beta.1") < Semver(1, 0, 0, "beta.2"))
        assertTrue(Semver(1, 0, 0, "alpha") < Semver(1, 0, 0, "beta"))
        // Numeric pre-release identifiers compare numerically.
        assertTrue(Semver(1, 0, 0, "beta.10") > Semver(1, 0, 0, "beta.2"))
    }

    // Asset versionCode

    @Test
    fun `parses versionCode from the asset name`() {
        assertEquals(2L, parseVersionCodeFromAssetName("ayman-tunnel-plus-v0.1.0-2.apk"))
        assertEquals(123L, parseVersionCodeFromAssetName("app-123.apk"))
        assertEquals(2L, parseVersionCodeFromAssetName("app-2.APK"))
        assertEquals(7L, parseVersionCodeFromAssetName("app_v1.2.3_7.apk"))
    }

    @Test
    fun `no trailing number means no versionCode`() {
        assertNull(parseVersionCodeFromAssetName("ayman-tunnel-plus-v0.1.0.apk"))
        assertNull(parseVersionCodeFromAssetName("app.apk"))
        assertNull(parseVersionCodeFromAssetName("app.zip"))
    }

    // ReleaseInfo helpers

    @Test
    fun `picks the first apk asset and the published date`() {
        val release = ReleaseInfo(
            tag = "v0.2.0",
            name = "Release 0.2.0",
            publishedAt = "2026-11-24T10:00:00Z",
            assets = listOf(
                ReleaseAsset("notes.zip", 100, "http://example.invalid/n.zip"),
                ReleaseAsset("app-v0.2.0-3.apk", 50_000_000, "http://example.invalid/a.apk"),
            ),
        )
        assertEquals("app-v0.2.0-3.apk", release.apkAsset?.name)
        assertEquals(3L, release.versionCode)
        assertEquals("2026-11-24", release.publishedDate)
        assertEquals(Semver(0, 2, 0), release.semver)
    }

    @Test
    fun `no apk asset means no apk and no code`() {
        val release = ReleaseInfo("v0.2.0", null, null, listOf(ReleaseAsset("notes.zip", 1, "http://x")))
        assertNull(release.apkAsset)
        assertNull(release.versionCode)
    }

    // decideUpdate

    private fun installed(name: String, code: Long) =
        InstalledVersion(versionName = name, versionCode = code, installerPackageName = null)

    private fun release(tag: String, code: Long? = null): ReleaseInfo {
        val assetName = if (code != null) {
            "ayman-tunnel-plus-$tag-$code.apk"
        } else {
            "ayman-tunnel-plus-$tag.apk"
        }
        return ReleaseInfo(tag, tag, "2026-11-24T10:00:00Z", listOf(ReleaseAsset(assetName, 1024, "http://x/a.apk")))
    }

    @Test
    fun `newer tag is an update`() {
        val decision = decideUpdate(installed("0.1.0", 1), release("v0.2.0"))
        assertTrue(decision is UpdateDecision.UpdateAvailable)
    }

    @Test
    fun `same tag without a code is up to date`() {
        assertEquals(UpdateDecision.UpToDate, decideUpdate(installed("0.1.0", 1), release("v0.1.0")))
    }

    @Test
    fun `same tag with a bigger code is an update`() {
        val decision = decideUpdate(installed("0.1.0", 1), release("v0.1.0", code = 2))
        assertTrue(decision is UpdateDecision.UpdateAvailable)
    }

    @Test
    fun `same tag and same code is up to date`() {
        assertEquals(UpdateDecision.UpToDate, decideUpdate(installed("0.1.0", 2), release("v0.1.0", code = 2)))
    }

    @Test
    fun `older tag never updates`() {
        assertEquals(UpdateDecision.UpToDate, decideUpdate(installed("0.2.0", 1), release("v0.1.0")))
    }

    @Test
    fun `release without an apk asset`() {
        val noApk = ReleaseInfo("v0.2.0", null, null, emptyList())
        assertEquals(UpdateDecision.NoApkAsset, decideUpdate(installed("0.1.0", 1), noApk))
    }

    @Test
    fun `unparseable installed version falls back to versionCode`() {
        assertTrue(
            decideUpdate(installed("debug", 1), release("v0.1.0", code = 2))
                is UpdateDecision.UpdateAvailable,
        )
        assertEquals(
            UpdateDecision.UpToDate,
            decideUpdate(installed("debug", 5), release("v0.1.0", code = 2)),
        )
    }

    @Test
    fun `nothing comparable means cannot determine`() {
        val weird = ReleaseInfo("weird", null, null, listOf(ReleaseAsset("app.apk", 1, "http://x")))
        assertEquals(UpdateDecision.CannotDetermine, decideUpdate(installed("debug", 1), weird))
    }

    // Install source

    @Test
    fun `install source from the installer package`() {
        assertEquals(
            InstallSource.FDROID,
            InstalledVersion("0.1.0", 1, "org.fdroid.fdroid").installSource,
        )
        assertEquals(
            InstallSource.PLAY,
            InstalledVersion("0.1.0", 1, "com.android.vending").installSource,
        )
        assertEquals(
            InstallSource.SIDELOADED,
            InstalledVersion("0.1.0", 1, null).installSource,
        )
        assertEquals(
            InstallSource.SIDELOADED,
            InstalledVersion("0.1.0", 1, "com.example.other").installSource,
        )
    }

    @Test
    fun `formatBytes is already covered by UsageStats tests`() {
        // Sanity: the shared formatter used by the screen exists and is
        // stable — the screen imports it from the stats package.
        assertFalse(dev.aalhdydy550.tunnel.core.data.stats.formatBytes(2048).isEmpty())
    }
}
