package dev.aalhdydy550.tunnel.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.aalhdydy550.tunnel.core.data.repository.AllowInsecureStore
import dev.aalhdydy550.tunnel.core.data.repository.DataStoreAllowInsecureRepository
import dev.aalhdydy550.tunnel.core.data.repository.DataStoreLocalProxyStore
import dev.aalhdydy550.tunnel.core.data.repository.LocalProxyStore
import javax.inject.Singleton

/**
 * Binds the Hamo settings stores (local proxy + allow insecure) to
 * their interfaces. Kept in its own module so [DataModule] — and the
 * existing settings — are untouched.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class HamoSettingsModule {

    @Binds
    @Singleton
    abstract fun bindLocalProxyStore(
        impl: DataStoreLocalProxyStore,
    ): LocalProxyStore

    @Binds
    @Singleton
    abstract fun bindAllowInsecureStore(
        impl: DataStoreAllowInsecureRepository,
    ): AllowInsecureStore
}
