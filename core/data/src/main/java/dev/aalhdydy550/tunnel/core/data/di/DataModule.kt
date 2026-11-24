package dev.aalhdydy550.tunnel.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.aalhdydy550.tunnel.core.data.net.defaultOkHttpClient
import dev.aalhdydy550.tunnel.core.database.HamoReferenceSeeder
import dev.aalhdydy550.tunnel.core.database.ProfileDao
import dev.aalhdydy550.tunnel.core.database.SubscriptionDao
import dev.aalhdydy550.tunnel.core.database.TunnelDatabase
import dev.aalhdydy550.tunnel.core.database.TunnelDatabaseHolder
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tunnel_settings",
)

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    /**
     * The shared instance from [TunnelDatabaseHolder] — the same one the
     * background workers use, so worker writes reach the UI's `Flow`.
     * No destructive fallback: the profile table holds the user's
     * servers, so an uncovered migration path must fail loudly instead
     * of silently wiping the database.
     */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TunnelDatabase =
        TunnelDatabaseHolder.get(context)

    @Provides
    fun provideProfileDao(database: TunnelDatabase): ProfileDao = database.profileDao()

    @Provides
    fun provideSubscriptionDao(database: TunnelDatabase): SubscriptionDao =
        database.subscriptionDao()

    /**
     * The reference-data seeder. Provided here (not via an `@Inject`
     * constructor on the class) because `:core:database` has no Hilt
     * dependency.
     */
    @Provides
    @Singleton
    fun provideHamoReferenceSeeder(database: TunnelDatabase): HamoReferenceSeeder =
        HamoReferenceSeeder(database)

    @Provides
    @Singleton
    fun provideOkHttpClient(): okhttp3.OkHttpClient = defaultOkHttpClient()

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.settingsDataStore
}
