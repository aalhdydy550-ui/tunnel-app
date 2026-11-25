package dev.aalhdydy550.tunnel.core.data.groups

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * مخزن مجموعات الاتصال (البند 6ب): المجموعات + المجموعة النشطة.
 *
 * التخزين DataStore JSON فوق ملف الإعدادات المشترك نفسه
 * `"tunnel_settings"` الذي يوفّره `DataModule` — مفتاح String واحد
 * `connection_groups_json` يحمل مصفوفة [ConnectionGroup] كاملة،
 * ومفتاح `active_group_id` يحمل id المجموعة النشطة (أو غائب = بلا
 * مجموعة نشطة). بلا ترحيل Room وبلا ملف DataStore جديد.
 *
 * **ملاحظة المحاكي:** stub الـ`edit` يرمي في المحاكي المحلي (كما في
 * `KillSwitchStore` بالبند 2) — لذلك اختبارات هذا المخزن تختبر دوال
 * الترميز ([encodeGroups]/[decodeGroups]) + واجهة وهمية in-memory،
 * لا جولة DataStore حقيقية. الـCI هو الحكم على التنفيذ الحقيقي.
 */
interface GroupStore {

    /** كل المجموعات، بالترتيب الذي أُنشئت به. */
    val groups: Flow<List<ConnectionGroup>>

    /** id المجموعة النشطة، أو null إن لم تُختر مجموعة. */
    val activeGroupId: Flow<String?>

    /** يجعل المجموعة نشطة، أو `null` لإلغاء اختيار أي مجموعة. */
    suspend fun setActiveGroup(id: String?)

    /** ينشئ مجموعة باسم جديد ويعيدها (id مولّد). */
    suspend fun createGroup(name: String): ConnectionGroup

    /** يعيد تسمية مجموعة موجودة؛ لا شيء إن لم توجد. */
    suspend fun renameGroup(id: String, name: String)

    /** يحذف مجموعة؛ وإن كانت النشطة أُلغي اختيارها. */
    suspend fun deleteGroup(id: String)

    /** يضيف بروفايلًا إلى مجموعة (بلا تكرار). */
    suspend fun addProfile(groupId: String, profileId: Long)

    /** يحذف بروفايلًا من مجموعة. */
    suspend fun removeProfile(groupId: String, profileId: Long)

    /** يغيّر وضع الاختيار التلقائي لمجموعة. */
    suspend fun setMode(groupId: String, mode: GroupMode)

    /** يسجّل نجاح اتصال: يحدّث `lastGoodProfileId` للمجموعة. */
    suspend fun recordSuccess(groupId: String, profileId: Long)

    /** يسجّل آخر سرعة مقيسة لبروفايل في مجموعة. */
    suspend fun recordSpeed(groupId: String, profileId: Long, mbps: Double)
}

/** ترميز خالص — يُختبر على JVM مباشرة بلا DataStore. */
internal fun encodeGroups(groups: List<ConnectionGroup>): String =
    groupsJson.encodeToString(groups)

/**
 * فك ترميز خالص — يُختبر على JVM مباشرة بلا DataStore.
 * نص فارغ/غائب/تالف ← قائمة فارغة (لا رمي أبدًا)؛ وملف قديم
 * بلا الحقول الجديدة يُفكّ بقيمها الافتراضية (`ignoreUnknownKeys`
 * يتجاهل الحقول غير المعروفة، وقيم `ConnectionGroup` الافتراضية
 * تملأ الحقول الغائبة).
 */
internal fun decodeGroups(json: String?): List<ConnectionGroup> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching { groupsJson.decodeFromString<List<ConnectionGroup>>(json) }
        .getOrElse { emptyList() }
}

private val groupsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Singleton
class DataStoreGroupStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : GroupStore {

    companion object {
        private val GROUPS_KEY = stringPreferencesKey("connection_groups_json")
        private val ACTIVE_GROUP_KEY = stringPreferencesKey("active_group_id")
    }

    override val groups: Flow<List<ConnectionGroup>> =
        dataStore.data.map { prefs -> decodeGroups(prefs[GROUPS_KEY]) }

    override val activeGroupId: Flow<String?> =
        dataStore.data.map { prefs -> prefs[ACTIVE_GROUP_KEY] }

    override suspend fun setActiveGroup(id: String?) {
        dataStore.edit { prefs ->
            if (id == null) prefs.remove(ACTIVE_GROUP_KEY) else prefs[ACTIVE_GROUP_KEY] = id
        }
    }

    override suspend fun createGroup(name: String): ConnectionGroup {
        val group = ConnectionGroup(name = name)
        updateGroups { it + group }
        return group
    }

    override suspend fun renameGroup(id: String, name: String) {
        updateGroups { groups ->
            groups.map { if (it.id == id) it.copy(name = name) else it }
        }
    }

    override suspend fun deleteGroup(id: String) {
        updateGroups { groups -> groups.filterNot { it.id == id } }
        if (activeGroupId.first() == id) setActiveGroup(null)
    }

    override suspend fun addProfile(groupId: String, profileId: Long) {
        updateGroups { groups ->
            groups.map {
                if (it.id == groupId && profileId !in it.profileIds) {
                    it.copy(profileIds = it.profileIds + profileId)
                } else {
                    it
                }
            }
        }
    }

    override suspend fun removeProfile(groupId: String, profileId: Long) {
        updateGroups { groups ->
            groups.map {
                if (it.id == groupId) it.copy(profileIds = it.profileIds - profileId) else it
            }
        }
    }

    override suspend fun setMode(groupId: String, mode: GroupMode) {
        updateGroups { groups ->
            groups.map { if (it.id == groupId) it.copy(mode = mode) else it }
        }
    }

    override suspend fun recordSuccess(groupId: String, profileId: Long) {
        updateGroups { groups ->
            groups.map {
                if (it.id == groupId) it.copy(lastGoodProfileId = profileId) else it
            }
        }
    }

    override suspend fun recordSpeed(groupId: String, profileId: Long, mbps: Double) {
        updateGroups { groups ->
            groups.map {
                if (it.id == groupId) {
                    it.copy(lastSpeedByProfile = it.lastSpeedByProfile + (profileId to mbps))
                } else {
                    it
                }
            }
        }
    }

    private suspend fun updateGroups(transform: (List<ConnectionGroup>) -> List<ConnectionGroup>) {
        val current = groups.first()
        val updated = transform(current)
        if (updated == current) return
        dataStore.edit { prefs -> prefs[GROUPS_KEY] = encodeGroups(updated) }
    }
}

/** وكيل DataStore فوق ملف الإعدادات المشترك نفسه `"tunnel_settings"`. */
private val Context.groupDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tunnel_settings",
)

/**
 * مخزن مجموعات فوق ملف الإعدادات المشترك، للاستخدام بلا Hilt
 * (على نمط `createKillSwitchStore` في `KillSwitchStore.kt`).
 * يعيد مخزنًا يقرأ ويكتب المفتاحين نفسهما اللذين يقرؤهما
 * `DataStoreGroupStore` المحقون في الواجهة.
 */
fun createGroupStore(context: Context): GroupStore =
    DataStoreGroupStore(context.applicationContext.groupDataStore)
