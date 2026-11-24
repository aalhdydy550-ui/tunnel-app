package dev.aalhdydy550.tunnel.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * مفتاح القتل داخل التطبيق (البند 2): هل يُبقي التطبيق الـTUN مفتوحًا
 * عند سقوط النفق سقوطًا غير مقصود بدل إغلاقه، فيُحظر الترافيك فعليًا
 * إلى أن يعود الاتصال أو يوقف المستخدم النفق يدويًا.
 *
 * الافتراضي **مغلق (OFF)** — القيمة غير المخزنة تُقرأ `false`، فالميزة
 * لا تعمل إلا بعد أن يفعّلها المستخدم بنفسه من الإعدادات بعد قراءة
 * التحذير العربي هناك.
 *
 * **انحراف موثق عن مكان الخطة (نفس سابقة البند 5أ `ProxyModeStore`):**
 * الخطة تضع `KillSwitchStore` في `feature/connection`، لكن تلك الوحدة
 * بلا اعتماد DataStore، وإضافته تعني لمس
 * `feature/connection/build.gradle.kts` — ممنوع في هذه الجولة. لذلك
 * يعيش المخزن هنا في `core/data/.../repository/` بجانب مخازن الإعدادات
 * الأخرى، ويُربط في `HamoSettingsModule` الموجود. آلة الحالات نفسها
 * (`KillSwitchManager` في `feature/connection`) كوتلن خالصة بلا أي
 * اعتماد على هذا المخزن.
 *
 * **مصنع الاستخدام بلا Hilt:** خدمة الـVPN ليست خدمة Hilt، فلا تستطيع
 * حقن هذا المخزن؛ لذلك يوفّر [createKillSwitchStore] مخزنًا فوق ملف
 * DataStore نفسه `"tunnel_settings"` الذي يوفّره `DataModule` — الوكيل
 * (delegate) هنا يحمل الاسم نفسه، والوكلاء بالاسم نفسه يتشاركون نسخة
 * DataStore الأساسية نفسها، فالكتابة من الخدمة تصل إلى `Flow` الواجهة
 * والعكس. يُستخدم `context.applicationContext` دائمًا حتى لا يُحتفظ
 * بسياق نشاط.
 */
interface KillSwitchStore {

    /** هل مفتاح القتل مفعّل (الافتراضي `false`). */
    val enabled: Flow<Boolean>

    /** يحفظ اختيار المستخدم. */
    suspend fun setEnabled(enabled: Boolean)
}

@Singleton
class DataStoreKillSwitchStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : KillSwitchStore {

    companion object {
        private val ENABLED_KEY = booleanPreferencesKey("kill_switch_enabled")
    }

    override val enabled: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[ENABLED_KEY] ?: false }

    override suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[ENABLED_KEY] = enabled }
    }
}

/** وكيل DataStore فوق ملف الإعدادات المشترك نفسه `"tunnel_settings"`. */
private val Context.killSwitchDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tunnel_settings",
)

/**
 * مخزن مفتاح قتل فوق ملف الإعدادات المشترك، للاستخدام من خدمة الـVPN
 * (ليست خدمة Hilt فلا حقن فيها). يعيد مخزنًا يقرأ ويكتب المفتاح نفسه
 * الذي يقرؤه `DataStoreKillSwitchStore` المحقون في الواجهة.
 */
fun createKillSwitchStore(context: Context): KillSwitchStore =
    DataStoreKillSwitchStore(context.applicationContext.killSwitchDataStore)
