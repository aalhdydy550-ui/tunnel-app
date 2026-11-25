package dev.aalhdydy550.tunnel.core.data.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.aalhdydy550.tunnel.core.data.update.GithubReleaseChecker
import dev.aalhdydy550.tunnel.core.data.update.InstalledVersionProvider
import dev.aalhdydy550.tunnel.core.data.update.OkHttpUpdateDownloader
import dev.aalhdydy550.tunnel.core.data.update.PackageManagerInstalledVersionProvider
import dev.aalhdydy550.tunnel.core.data.update.UpdateChecker
import dev.aalhdydy550.tunnel.core.data.update.UpdateDownloader
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Wires the in-app updater (GitHub Releases check + direct APK
 * download + installed-version lookup). Kept in its own module so
 * [DataModule] — and the existing settings — are untouched.
 *
 * All three bindings are singletons: the checker and the downloader
 * share the app's single [OkHttpClient], and the download target is
 * always `<cacheDir>/updates/update.apk` (the folder FileProvider
 * shares with the installer — see `app/src/main/res/xml/file_paths.xml`).
 */
@Module
@InstallIn(SingletonComponent::class)
object UpdateCheckModule {

    @Provides
    @Singleton
    fun provideUpdateChecker(client: OkHttpClient): UpdateChecker =
        GithubReleaseChecker(client)

    @Provides
    @Singleton
    fun provideUpdateDownloader(
        client: OkHttpClient,
        @ApplicationContext context: Context,
    ): UpdateDownloader = OkHttpUpdateDownloader(client, context.cacheDir)

    @Provides
    @Singleton
    fun provideInstalledVersionProvider(
        @ApplicationContext context: Context,
    ): InstalledVersionProvider = PackageManagerInstalledVersionProvider(context)
}
