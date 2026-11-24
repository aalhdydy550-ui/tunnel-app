package dev.aalhdydy550.tunnel.feature.connection.perapp

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the installed-apps provider to its source interface. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PerAppProxyModule {

    @Binds
    @Singleton
    abstract fun bindInstalledAppsSource(
        impl: InstalledAppsProvider,
    ): InstalledAppsSource
}
