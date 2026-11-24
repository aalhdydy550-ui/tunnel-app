package dev.aalhdydy550.tunnel.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.aalhdydy550.tunnel.core.data.repository.DataStoreDnsPreferenceStore
import dev.aalhdydy550.tunnel.core.data.repository.DataStorePerAppProxyStore
import dev.aalhdydy550.tunnel.core.data.repository.DnsPreferenceStore
import dev.aalhdydy550.tunnel.core.data.repository.PerAppProxyStore
import javax.inject.Singleton

/**
 * Binds the two new DataStore-backed stores (per-app proxy choice and
 * DNS choice) to their interfaces. Kept in its own module so
 * [DataModule] — and the existing settings — are untouched.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PerAppDnsModule {

    @Binds
    @Singleton
    abstract fun bindPerAppProxyStore(
        impl: DataStorePerAppProxyStore,
    ): PerAppProxyStore

    @Binds
    @Singleton
    abstract fun bindDnsPreferenceStore(
        impl: DataStoreDnsPreferenceStore,
    ): DnsPreferenceStore
}
