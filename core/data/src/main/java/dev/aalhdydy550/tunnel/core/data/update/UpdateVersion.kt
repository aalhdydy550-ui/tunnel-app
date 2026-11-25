package dev.aalhdydy550.tunnel.core.data.update

/**
 * Pure version/release logic for the in-app updater.
 *
 * No Android dependency: everything here is unit-tested on the JVM.
 * The updater only talks to the public GitHub Releases API of this
 * app's own repository — it never contacts any other server, and it
 * never runs in the background (manual check only, see
 * [dev.aalhdydy550.tunnel.feature.connection.update.UpdateCheckViewModel]).
 */

/** Latest-release endpoint of this app's public repository (no auth needed). */
const val LATEST_RELEASE_URL =
    "https://api.github.com/repos/aalhdydy550-ui/tunnel-app/releases/latest"

/** Package name of the F-Droid client. */
const val FDROID_PACKAGE_NAME = "org.fdroid.fdroid"

/** Package name of Google Play. */
const val PLAY_PACKAGE_NAME = "com.android.vending"

/**
 * Where the installed APK came from. F-Droid and Play installs are
 * updated by their store, never by the in-app GitHub updater.
 */
enum class InstallSource(val arabicName: String) {
    SIDELOADED("تثبيت يدوي"),
    FDROID("F-Droid"),
    PLAY("Google Play"),
}

/** The installed build, as reported by PackageManager (see InstalledVersionProvider). */
data class InstalledVersion(
    val versionName: String,
    val versionCode: Long,
    val installerPackageName: String?,
) {
    val semver: Semver? get() = parseSemver(versionName)

    val installSource: InstallSource
        get() = when (installerPackageName) {
            FDROID_PACKAGE_NAME -> InstallSource.FDROID
            PLAY_PACKAGE_NAME -> InstallSource.PLAY
            else -> InstallSource.SIDELOADED
        }
}

/**
 * Semantic version, parsed leniently: an optional leading `v`, one to
 * three numeric components (`1` → 1.0.0, `1.2` → 1.2.0), an optional
 * pre-release suffix (`-beta.1`), and an optional build suffix (`+...`,
 * ignored for comparison). A release is always newer than a pre-release
 * of the same base version.
 */
data class Semver(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
) : Comparable<Semver> {

    override fun compareTo(other: Semver): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        // Same base: a release beats any pre-release of that base.
        if (preRelease == null && other.preRelease == null) return 0
        if (preRelease == null) return 1
        if (other.preRelease == null) return -1
        return comparePreRelease(preRelease, other.preRelease)
    }

    override fun toString(): String =
        "$major.$minor.$patch" + (preRelease?.let { "-$it" } ?: "")
}

private fun comparePreRelease(a: String, b: String): Int {
    // Numeric identifiers compare numerically, alphanumerics lexically;
    // a shorter list that is a prefix of the longer one is smaller.
    // (SemVer 2.0.0 §11 — the common cases; full identifier rules.)
    val aParts = a.split('.')
    val bParts = b.split('.')
    val shared = minOf(aParts.size, bParts.size)
    for (i in 0 until shared) {
        val x = aParts[i]
        val y = bParts[i]
        if (x == y) continue
        val xNum = x.toLongOrNull()
        val yNum = y.toLongOrNull()
        if (xNum != null && yNum != null) return xNum.compareTo(yNum)
        if (xNum != null) return -1 // numeric < alphanumeric
        if (yNum != null) return 1
        return x.compareTo(y)
    }
    return aParts.size.compareTo(bParts.size)
}

private val SEMVER_REGEX =
    Regex("""^[vV]?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

/** Parses a tag or version name leniently; null when it is not a version at all. */
fun parseSemver(raw: String?): Semver? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    val match = SEMVER_REGEX.matchEntire(text) ?: return null
    return Semver(
        major = match.groupValues[1].toInt(),
        minor = match.groupValues[2].toIntOrNull() ?: 0,
        patch = match.groupValues[3].toIntOrNull() ?: 0,
        preRelease = match.groupValues[4].takeIf { it.isNotEmpty() },
    )
}

/** One file attached to a GitHub release. */
data class ReleaseAsset(
    val name: String,
    val sizeBytes: Long,
    val downloadUrl: String,
)

/** A GitHub release, as returned by the `/releases/latest` endpoint. */
data class ReleaseInfo(
    val tag: String,
    val name: String?,
    val publishedAt: String?,
    val assets: List<ReleaseAsset>,
) {
    val semver: Semver? get() = parseSemver(tag)

    /** First attached file whose name ends in `.apk`, or null when the release ships none. */
    val apkAsset: ReleaseAsset?
        get() = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

    /**
     * versionCode parsed from the APK asset name, when the release
     * follows the `-<versionCode>.apk` convention (for example
     * `ayman-tunnel-plus-v0.1.0-2.apk` → 2). Null when the name carries
     * no trailing number — then the semver alone decides.
     */
    val versionCode: Long? get() = apkAsset?.let { parseVersionCodeFromAssetName(it.name) }

    /** Date part (`2026-11-24`) of an ISO-8601 `published_at`, or null. */
    val publishedDate: String?
        get() = publishedAt?.takeIf { it.length >= 10 }?.substring(0, 10)
}

private val ASSET_VERSION_CODE_REGEX = Regex("""[-_](\d+)\.apk$""", RegexOption.IGNORE_CASE)

/**
 * Reads the versionCode from a release APK file name.
 * `ayman-tunnel-plus-v0.1.0-2.apk` → 2; `app-123.apk` → 123;
 * `ayman-tunnel-plus-v0.1.0.apk` → null (no trailing number — the
 * `.0` before `.apk` is part of the version, not a code).
 */
fun parseVersionCodeFromAssetName(name: String): Long? =
    ASSET_VERSION_CODE_REGEX.find(name)?.groupValues?.getOrNull(1)?.toLongOrNull()

/** The verdict of comparing an installed build with a published release. */
sealed interface UpdateDecision {
    /** Installed is the same or newer — nothing to download. */
    data object UpToDate : UpdateDecision

    /** A newer release exists (semver, or versionCode on an equal semver). */
    data class UpdateAvailable(val release: ReleaseInfo) : UpdateDecision

    /** The latest release attaches no `.apk` file at all. */
    data object NoApkAsset : UpdateDecision

    /** Neither side could be compared (unparseable tag and no usable versionCode). */
    data object CannotDetermine : UpdateDecision
}

/**
 * Decides whether [release] is an update over [installed].
 *
 * Order: semver first (a newer tag wins outright; an older tag never
 * updates, even with a bigger versionCode); on an equal semver the
 * versionCode breaks the tie (a rebuild of the same tag). When either
 * semver is unparseable, the versionCode alone decides when it is
 * known on the release side; with nothing comparable, [CannotDetermine].
 */
fun decideUpdate(installed: InstalledVersion, release: ReleaseInfo): UpdateDecision {
    if (release.apkAsset == null) return UpdateDecision.NoApkAsset
    val installedSemver = installed.semver
    val releaseSemver = release.semver
    if (installedSemver != null && releaseSemver != null) {
        if (releaseSemver > installedSemver) return UpdateDecision.UpdateAvailable(release)
        if (releaseSemver < installedSemver) return UpdateDecision.UpToDate
        val releaseCode = release.versionCode
        if (releaseCode != null && installed.versionCode < releaseCode) {
            return UpdateDecision.UpdateAvailable(release)
        }
        return UpdateDecision.UpToDate
    }
    val releaseCode = release.versionCode
    if (releaseCode != null) {
        return if (installed.versionCode < releaseCode) {
            UpdateDecision.UpdateAvailable(release)
        } else {
            UpdateDecision.UpToDate
        }
    }
    return UpdateDecision.CannotDetermine
}
