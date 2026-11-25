package dev.aalhdydy550.tunnel.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.aalhdydy550.tunnel.core.data.groups.DataStoreGroupStore
import dev.aalhdydy550.tunnel.core.data.groups.GroupStore
import javax.inject.Singleton

/**
 * يربط مخزن مجموعات الاتصال (البند 6ب) بواجهته. على نمط
 * [HamoSettingsModule]: وحدة ربط مستقلة حتى لا تُلمس `DataModule`
 * ولا إعداداتها القائمة. التنفيذ `DataStoreGroupStore` يأخذ
 * `DataStore<Preferences>` نفسه الذي يوفّره `DataModule` (ملف
 * `tunnel_settings` المشترك) عبر بنائه المحقون.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class GroupsModule {

    @Binds
    @Singleton
    abstract fun bindGroupStore(
        impl: DataStoreGroupStore,
    ): GroupStore
}
