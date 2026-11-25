package dev.aalhdydy550.tunnel.feature.connection.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Turns a downloaded APK file into the system install flow.
 *
 * The APK is shared with the installer only, through FileProvider
 * (`<applicationId>.fileprovider`, declared in the app manifest with
 * `res/xml/file_paths.xml` — the `updates/` cache folder only).
 * Nothing here downloads or checks anything; the ViewModel owns the
 * check/download states, and the screen calls these helpers with the
 * activity context.
 *
 * Android 10 flow ("unknown sources"): on Android 8+ the system
 * install is gated by a per-app toggle
 * ([Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES]). When the toggle is
 * off, [canInstall] is false and the screen offers the settings page
 * instead of the install intent — the app never asks for the toggle
 * silently, and it never tries to enable it by itself.
 */
object UpdateInstaller {

    /** FileProvider authority: `<applicationId>.fileprovider`. */
    fun fileProviderAuthority(context: Context): String = "${context.packageName}.fileprovider"

    /** Content URI for the downloaded APK, readable by the installer only. */
    fun apkUri(context: Context, apkFile: File): Uri =
        FileProvider.getUriForFile(context, fileProviderAuthority(context), apkFile)

    /**
     * True when the system will let this app start an install. Below
     * API 26 there is no per-app toggle (the old global
     * "unknown sources" setting applies), so the install intent is
     * used directly.
     */
    fun canInstall(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    /** System installer for the downloaded APK. */
    fun installIntent(context: Context, apkFile: File): Intent =
        Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(apkUri(context, apkFile), "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** The per-app "Install unknown apps" settings page for this app (Android 8+). */
    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
