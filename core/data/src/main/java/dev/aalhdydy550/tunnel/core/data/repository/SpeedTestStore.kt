package dev.aalhdydy550.tunnel.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.aalhdydy550.tunnel.core.data.model.SpeedTestResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * آخر نتيجة اختبار سرعة لكل بروفايل (البند 6أ): تُعرض في بطاقة
 * الخادم وتُستخدم في الاختيار الذكي (البند 6ب).
 *
 * نتيجة واحدة فقط لكل بروفايل — الأحدث تحل محل السابقة؛ لا سجل
 * تاريخي (يكفي «آخر سرعة» للعرض والاختيار، ويبقي المخزن صغيرًا).
 *
 * **انحراف موثق عن مكان الخطة (نفس سابقة البند 2 `KillSwitchStore`
 * والبند 5أ `ProxyModeStore`):** الخطة تضع `SpeedTestStore` في
 * `feature/connection`، لكن تلك الوحدة بلا اعتماد DataStore،
 * وإضافته تعني لمس `feature/connection/build.gradle.kts` — ممنوع
 * في هذه الجولة. لذلك يعيش المخزن هنا في
 * `core/data/.../repository/` بجانب مخازن الإعدادات الأخرى، ويُربط
 * في `HamoSettingsModule` الموجود. المحرك نفسه (`SpeedTest` في
 * `feature/connection`) كوتلن + OkHttp بلا أي اعتماد على هذا المخزن.
 *
 * **مصنع الاستخدام بلا Hilt:** يوفّر [createSpeedTestStore] مخزنًا
 * فوق ملف DataStore نفسه `"tunnel_settings"` الذي يوفّره
 * `DataModule` — الوكيل (delegate) هنا يحمل الاسم نفسه، والوكلاء
 * بالاسم نفسه يتشاركون نسخة DataStore الأساسية نفسها، فالكتابة من
 * أي مكان تصل إلى `Flow` الواجهة والعكس. يُستخدم
 * `context.applicationContext` دائمًا حتى لا يُحتفظ بسياق نشاط.
 *
 * JSON تالف (مثلًا بعد تعديل يدوي) يُقرأ خريطة فارغة بدل رمي
 * استثناء — نتيجة قديمة أفضل من شاشة مكسورة.
 */
interface SpeedTestStore {

    /** آخر نتيجة للبروفايل، أو `null` إن لم يُختبر بعد. */
    fun lastResult(profileId: Long): Flow<SpeedTestResult?>

    /** آخر نتيجة لكل بروفايل اختُبر (المفتاح هو `profileId`). */
    fun allResults(): Flow<Map<Long, SpeedTestResult>>

    /** يحفظ نتيجة اختبار (تحل محل نتيجة البروفايل السابقة). */
    suspend fun saveResult(profileId: Long, result: SpeedTestResult)
}

@Singleton
class DataStoreSpeedTestStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SpeedTestStore {

    companion object {
        private val RESULTS_KEY = stringPreferencesKey("speed_test_results_json")

        private val json = Json { ignoreUnknownKeys = true }
    }

    private val results: Flow<Map<Long, SpeedTestResult>> =
        dataStore.data.map { prefs ->
            val raw = prefs[RESULTS_KEY] ?: return@map emptyMap()
            runCatching { json.decodeFromString<Map<Long, SpeedTestResult>>(raw) }
                .getOrElse { emptyMap() }
        }

    override fun lastResult(profileId: Long): Flow<SpeedTestResult?> =
        results.map { it[profileId] }

    override fun allResults(): Flow<Map<Long, SpeedTestResult>> = results

    override suspend fun saveResult(profileId: Long, result: SpeedTestResult) {
        dataStore.edit { prefs ->
            val raw = prefs[RESULTS_KEY]
            val current = if (raw == null) {
                emptyMap()
            } else {
                runCatching { json.decodeFromString<Map<Long, SpeedTestResult>>(raw) }
                    .getOrElse { emptyMap() }
            }
            prefs[RESULTS_KEY] = json.encodeToString(current + (profileId to result))
        }
    }
}

/** وكيل DataStore فوق ملف الإعدادات المشترك نفسه `"tunnel_settings"`. */
private val Context.speedTestDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tunnel_settings",
)

/**
 * مخزن نتائج سرعة فوق ملف الإعدادات المشترك، للاستخدام بلا Hilt
 * (مثلًا من ViewModel غير محقونة أو من خدمة). يعيد مخزنًا يقرأ
 * ويكتب المفتاح نفسه الذي يقرؤه `DataStoreSpeedTestStore` المحقون
 * في الواجهة.
 */
fun createSpeedTestStore(context: Context): SpeedTestStore =
    DataStoreSpeedTestStore(context.applicationContext.speedTestDataStore)
