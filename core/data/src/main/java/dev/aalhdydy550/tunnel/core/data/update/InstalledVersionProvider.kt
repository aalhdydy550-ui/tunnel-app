package dev.aalhdydy550.tunnel.core.data.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * The installed build, read from PackageManager.
 *
 * Deliberately NOT BuildConfig: this code lives in `:core:data`, a
 * library module that cannot see the app module's BuildConfig, and
 * PackageManager reports the truth for any install source (GitHub
 * APK, F-Droid, Play) without a build-file change. The installer
 * package name is what decides whether the in-app updater applies
 * at all (see [InstalledVersion.installSource]).
 */
interface InstalledVersionProvider {
    fun installed(): InstalledVersion
}

class PackageManagerInstalledVersionProvider(
    private val context: Context,
) : InstalledVersionProvider {

    override fun installed(): InstalledVersion {
        val packageManager = context.packageManager
        val packageName = context.packageName
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                packageManager.getInstallSourceInfo(packageName).installingPackageName
            }.getOrNull()
        } else {
            @Suppress("DEPRECATION")
            runCatching { packageManager.getInstallerPackageName(packageName) }.getOrNull()
        }
        return InstalledVersion(
            versionName = info.versionName ?: "0.0.0",
            versionCode = versionCode,
            installerPackageName = installer,
        )
    }
}
