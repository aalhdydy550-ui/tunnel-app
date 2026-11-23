package dev.aalhdydy550.tunnel.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.aalhdydy550.tunnel.core.database.ProfileDao
import dev.aalhdydy550.tunnel.core.database.SubscriptionDao
import dev.aalhdydy550.tunnel.core.database.TunnelDatabase
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tunnel_settings",
)

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TunnelDatabase =
        Room.databaseBuilder(context, TunnelDatabase::class.java, "tunnel.db")
            .addMigrations(
                TunnelDatabase.MIGRATION_1_2,
                TunnelDatabase.MIGRATION_2_3,
            )
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideProfileDao(database: TunnelDatabase): ProfileDao = database.profileDao()

    @Provides
    fun provideSubscriptionDao(database: TunnelDatabase): SubscriptionDao =
        database.subscriptionDao()

    @Provides
    @Singleton
    fun provideOkHttpClient(): okhttp3.OkHttpClient =
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.settingsDataStore
}
