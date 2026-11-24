package dev.aalhdydy550.tunnel.feature.connection.perapp

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** One installed app, as shown in the per-app proxy list. */
data class InstalledApp(
    val packageName: String,
    val label: String,
)

/**
 * Source of the installed-apps list. An interface (not the concrete
 * provider) so the ViewModel is unit-testable with a fake on the JVM.
 */
interface InstalledAppsSource {
    /** All installed apps except this app itself, sorted by label. */
    suspend fun load(): List<InstalledApp>

    /** The app's icon, or null when it cannot be loaded. */
    fun loadIcon(packageName: String): Drawable?
}

/**
 * Reads the installed apps with `PackageManager`.
 *
 * Runs on [Dispatchers.IO]: `getInstalledApplications` + `loadLabel`
 * over a few hundred apps must never run on the main thread — on a
 * 2 GB phone that is the difference between a smooth screen and a
 * frozen one. Icons are NOT loaded here: the screen loads each
 * visible row's icon lazily (see `PerAppProxyScreen`), so opening the
 * screen costs one cheap list query, not hundreds of icon decodes.
 *
 * Needs `QUERY_ALL_PACKAGES` (declared in this module's manifest):
 * on Android 11+ the list is filtered without it.
 */
@Singleton
class InstalledAppsProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : InstalledAppsSource {

    override suspend fun load(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val ownPackage = context.packageName
        val infos = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(
                    PackageManager.ApplicationInfoFlags.of(
                        PackageManager.GET_META_DATA.toLong(),
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            }
        }.getOrElse { emptyList() }
        infos
            .filter { it.packageName != ownPackage }
            .map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    label = runCatching { info.loadLabel(pm).toString() }
                        .getOrElse { info.packageName },
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    override fun loadIcon(packageName: String): Drawable? = runCatching {
        context.packageManager.getApplicationIcon(packageName)
    }.getOrNull()
}
