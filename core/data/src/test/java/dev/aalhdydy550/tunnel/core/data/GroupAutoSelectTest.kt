package dev.aalhdydy550.tunnel.core.data

import dev.aalhdydy550.tunnel.core.data.groups.ConnectionGroup
import dev.aalhdydy550.tunnel.core.data.groups.GroupAutoSelect
import dev.aalhdydy550.tunnel.core.data.groups.GroupMode
import dev.aalhdydy550.tunnel.core.data.groups.GroupStore
import dev.aalhdydy550.tunnel.core.data.groups.decodeGroups
import dev.aalhdydy550.tunnel.core.data.groups.encodeGroups
import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.model.ProfileType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * اختبارات البند 6ب: منطق الاختيار التلقائي للمجموعات
 * ([GroupAutoSelect]) + ترميز/فك ترميز المجموعات + عقد
 * [GroupStore] عبر واجهة وهمية in-memory.
 *
 * المخزن الحقيقي DataStore لا يُختبر هنا: stub الـ`edit` يرمي في
 * المحاكي المحلي (كما في `KillSwitchStore` بالبند 2) — الـCI هو
 * الحكم على جولة DataStore الحقيقية.
 */
class GroupAutoSelectTest {

    private fun profile(id: Long, name: String, type: ProfileType = ProfileType.VLESS) =
        Profile(
            id = id,
            name = name,
            type = type,
            server = "203.0.113.${id}",
            port = 443,
            outboundJson = "{}",
        )

    private val p1 = profile(1, "slow")
    private val p2 = profile(2, "fast")
    private val p3 = profile(3, "mid")
    private val all = listOf(p1, p2, p3)

    // ---- FASTEST ----

    @Test
    fun `fastest picks the lowest latency`() {
        val group = ConnectionGroup(name = "g", profileIds = listOf(1, 2, 3))
        val picked = GroupAutoSelect.pick(
            group, all,
            latencyMs = mapOf(1L to 300, 2L to 40, 3L to 120),
            lastSpeedMbps = emptyMap(),
        )
        assertEquals("fast", picked?.name)
    }

    @Test
    fun `fastest treats no-latency profiles as slowest not first`() {
        val group = ConnectionGroup(name = "g", profileIds = listOf(1, 2, 3))
        val picked = GroupAutoSelect.pick(
            group, all,
            latencyMs = mapOf(2L to 90),
            lastSpeedMbps = emptyMap(),
        )
        assertEquals("fast", picked?.name)
    }

    @Test
    fun `fastest ties break by faster protocol`() {
        val hy2 = profile(1, "hy2", ProfileType.HYSTERIA2)
        val vless = profile(2, "vless", ProfileType.VLESS)
        val group = ConnectionGroup(name = "g", profileIds = listOf(1, 2))
        val picked = GroupAutoSelect.pick(
            group, listOf(hy2, vless),
            latencyMs = mapOf(1L to 50, 2L to 50),
            lastSpeedMbps = emptyMap(),
        )
        assertEquals("hy2", picked?.name)
    }

    @Test
    fun `fastest with no answers falls back to the first member instead of null`() {
        val group = ConnectionGroup(name = "g", profileIds = listOf(1, 2, 3))
        val picked = GroupAutoSelect.pick(
            group, all,
            latencyMs = emptyMap(),
            lastSpeedMbps = emptyMap(),
        )
        assertEquals("slow", picked?.name)
    }

    // ---- RANDOM ----

    @Test
    fun `random prefers the recently successful profile`() {
        val good = profile(1, "good")
        val dead = profile(2, "dead")
        val group = ConnectionGroup(
            name = "g",
            profileIds = listOf(1, 2),
            mode = GroupMode.RANDOM,
            lastGoodProfileId = 1,
            lastSpeedByProfile = mapOf(1L to 25.0),
        )
        // الوزن: الناجح ~7 مقابل 1 للميت (نحو 87% نظريًا)؛
        // ببذرة ثابتة و200 محاولة يجب أن يفوز الناجح بأغلبية ساحقة
        // (الحد 140 متساهل جدًا أمام المتوسط ~175).
        val random = Random(1234)
        var goodCount = 0
        repeat(200) {
            val picked = GroupAutoSelect.pick(
                group, listOf(good, dead),
                latencyMs = emptyMap(),
                lastSpeedMbps = emptyMap(),
                random = random,
            )
            if (picked?.id == 1L) goodCount++
        }
        assertTrue("good=$goodCount of 200", goodCount > 140)
    }

    @Test
    fun `random with a fixed seed is reproducible and stays in the group`() {
        val group = ConnectionGroup(
            name = "g",
            profileIds = listOf(1, 2, 3),
            mode = GroupMode.RANDOM,
        )
        val first = GroupAutoSelect.pick(group, all, emptyMap(), emptyMap(), Random(42))
        val second = GroupAutoSelect.pick(group, all, emptyMap(), emptyMap(), Random(42))
        assertEquals(first?.id, second?.id)
        assertTrue(first?.id in listOf(1L, 2L, 3L))
    }

    // ---- ROUND_ROBIN ----

    @Test
    fun `round robin picks the next after the last good profile`() {
        val group = ConnectionGroup(
            name = "g",
            profileIds = listOf(1, 2, 3),
            mode = GroupMode.ROUND_ROBIN,
            lastGoodProfileId = 2,
        )
        val picked = GroupAutoSelect.pick(group, all, emptyMap(), emptyMap())
        assertEquals("mid", picked?.name)
    }

    @Test
    fun `round robin wraps from the last member to the first`() {
        val group = ConnectionGroup(
            name = "g",
            profileIds = listOf(1, 2, 3),
            mode = GroupMode.ROUND_ROBIN,
            lastGoodProfileId = 3,
        )
        val picked = GroupAutoSelect.pick(group, all, emptyMap(), emptyMap())
        assertEquals("slow", picked?.name)
    }

    @Test
    fun `round robin with no last good starts from the first member`() {
        val group = ConnectionGroup(
            name = "g",
            profileIds = listOf(1, 2, 3),
            mode = GroupMode.ROUND_ROBIN,
        )
        val picked = GroupAutoSelect.pick(group, all, emptyMap(), emptyMap())
        assertEquals("slow", picked?.name)
    }

    @Test
    fun `round robin follows group order not profiles list order`() {
        val group = ConnectionGroup(
            name = "g",
            profileIds = listOf(3, 1, 2),
            mode = GroupMode.ROUND_ROBIN,
            lastGoodProfileId = 3,
        )
        // الترتيب في المجموعة: 3 ثم 1 ثم 2 — التالي بعد 3 هو 1.
        val picked = GroupAutoSelect.pick(group, all, emptyMap(), emptyMap())
        assertEquals("slow", picked?.name)
    }

    // ---- الحالات الحدّية ----

    @Test
    fun `empty group picks null`() {
        val group = ConnectionGroup(name = "g", profileIds = emptyList())
        assertNull(GroupAutoSelect.pick(group, all, emptyMap(), emptyMap()))
    }

    @Test
    fun `group with no matching profiles picks null`() {
        val group = ConnectionGroup(name = "g", profileIds = listOf(99, 100))
        assertNull(GroupAutoSelect.pick(group, all, emptyMap(), emptyMap()))
    }

    @Test
    fun `empty profiles list picks null`() {
        val group = ConnectionGroup(name = "g", profileIds = listOf(1, 2))
        assertNull(GroupAutoSelect.pick(group, emptyList(), emptyMap(), emptyMap()))
    }

    // ---- التجاوز (failover) ----

    @Test
    fun `failover excludes failed profiles and is capped at two attempts`() {
        val p4 = profile(4, "fourth")
        val group = ConnectionGroup(name = "g", profileIds = listOf(1, 2, 3, 4))
        val candidates = GroupAutoSelect.failoverCandidates(
            group, all + p4,
            failedIds = setOf(2L),
            latencyMs = mapOf(1L to 300, 2L to 40, 3L to 120, 4L to 200),
            lastSpeedMbps = emptyMap(),
        )
        assertEquals(GroupAutoSelect.MAX_FAILOVERS, candidates.size)
        assertEquals(listOf("mid", "fourth"), candidates.map { it.name })
        assertTrue(candidates.none { it.id == 2L })
    }

    @Test
    fun `failover returns empty when every member failed`() {
        val group = ConnectionGroup(name = "g", profileIds = listOf(1, 2))
        val candidates = GroupAutoSelect.failoverCandidates(
            group, all,
            failedIds = setOf(1L, 2L),
            latencyMs = emptyMap(),
            lastSpeedMbps = emptyMap(),
        )
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `failover in round robin continues after the last good profile`() {
        val group = ConnectionGroup(
            name = "g",
            profileIds = listOf(1, 2, 3),
            mode = GroupMode.ROUND_ROBIN,
            lastGoodProfileId = 1,
        )
        val candidates = GroupAutoSelect.failoverCandidates(
            group, all,
            failedIds = setOf(2L),
            latencyMs = emptyMap(),
            lastSpeedMbps = emptyMap(),
        )
        // التالي بعد 1 هو 2 (فاشل) ثم 3 ثم الالتفاف إلى 1.
        assertEquals(listOf("mid", "slow"), candidates.map { it.name })
    }

    @Test
    fun `max failovers is two`() {
        assertEquals(2, GroupAutoSelect.MAX_FAILOVERS)
    }

    // ---- الترميز / فك الترميز ----

    @Test
    fun `groups json round trip preserves every field`() {
        val groups = listOf(
            ConnectionGroup(
                id = "g1",
                name = "الأساسية",
                profileIds = listOf(1, 2),
                mode = GroupMode.RANDOM,
                lastGoodProfileId = 2,
                lastSpeedByProfile = mapOf(1L to 12.5, 2L to 40.0),
            ),
            ConnectionGroup(id = "g2", name = "فارغة"),
        )
        val decoded = decodeGroups(encodeGroups(groups))
        assertEquals(groups, decoded)
    }

    @Test
    fun `old file without the new fields decodes with defaults`() {
        // ملف قديم: بلا mode ولا lastGoodProfileId ولا
        // lastSpeedByProfile — وحقل مستقبلي غير معروف يُتجاهل.
        val oldJson = """[{"id":"g1","name":"قديمة","profileIds":[1,2],"futureField":123}]"""
        val decoded = decodeGroups(oldJson)
        assertEquals(1, decoded.size)
        assertEquals("g1", decoded[0].id)
        assertEquals(listOf(1L, 2L), decoded[0].profileIds)
        assertEquals(GroupMode.FASTEST, decoded[0].mode)
        assertNull(decoded[0].lastGoodProfileId)
        assertTrue(decoded[0].lastSpeedByProfile.isEmpty())
    }

    @Test
    fun `decode of blank or corrupt json is an empty list never a throw`() {
        assertTrue(decodeGroups(null).isEmpty())
        assertTrue(decodeGroups("").isEmpty())
        assertTrue(decodeGroups("not json at all").isEmpty())
        assertTrue(decodeGroups("[{broken").isEmpty())
    }

    // ---- عقد GroupStore عبر واجهة وهمية in-memory ----

    /** واجهة وهمية تطبّق العقد نفسه الذي يعتمد عليه عامل الواجهة. */
    private class InMemoryGroupStore : GroupStore {
        private val state = MutableStateFlow<List<ConnectionGroup>>(emptyList())
        private val active = MutableStateFlow<String?>(null)
        override val groups: Flow<List<ConnectionGroup>> = state
        override val activeGroupId: Flow<String?> = active

        override suspend fun setActiveGroup(id: String?) {
            active.value = id
        }

        override suspend fun createGroup(name: String): ConnectionGroup {
            val group = ConnectionGroup(name = name)
            state.value = state.value + group
            return group
        }

        override suspend fun renameGroup(id: String, name: String) {
            state.value = state.value.map { if (it.id == id) it.copy(name = name) else it }
        }

        override suspend fun deleteGroup(id: String) {
            state.value = state.value.filterNot { it.id == id }
            if (active.value == id) active.value = null
        }

        override suspend fun addProfile(groupId: String, profileId: Long) {
            state.value = state.value.map {
                if (it.id == groupId && profileId !in it.profileIds) {
                    it.copy(profileIds = it.profileIds + profileId)
                } else it
            }
        }

        override suspend fun removeProfile(groupId: String, profileId: Long) {
            state.value = state.value.map {
                if (it.id == groupId) it.copy(profileIds = it.profileIds - profileId) else it
            }
        }

        override suspend fun setMode(groupId: String, mode: GroupMode) {
            state.value = state.value.map { if (it.id == groupId) it.copy(mode = mode) else it }
        }

        override suspend fun recordSuccess(groupId: String, profileId: Long) {
            state.value = state.value.map {
                if (it.id == groupId) it.copy(lastGoodProfileId = profileId) else it
            }
        }

        override suspend fun recordSpeed(groupId: String, profileId: Long, mbps: Double) {
            state.value = state.value.map {
                if (it.id == groupId) {
                    it.copy(lastSpeedByProfile = it.lastSpeedByProfile + (profileId to mbps))
                } else it
            }
        }
    }

    @Test
    fun `store contract create rename add remove mode success speed active delete`() = runTest {
        val store = InMemoryGroupStore()
        val group = store.createGroup("تجريبية")
        store.setActiveGroup(group.id)
        assertEquals(group.id, store.activeGroupId.first())

        store.renameGroup(group.id, "جديدة")
        store.addProfile(group.id, 5L)
        store.addProfile(group.id, 5L) // بلا تكرار
        store.addProfile(group.id, 7L)
        store.setMode(group.id, GroupMode.ROUND_ROBIN)
        store.recordSuccess(group.id, 7L)
        store.recordSpeed(group.id, 7L, 33.5)

        val saved = store.groups.first().single()
        assertEquals("جديدة", saved.name)
        assertEquals(listOf(5L, 7L), saved.profileIds)
        assertEquals(GroupMode.ROUND_ROBIN, saved.mode)
        assertEquals(7L, saved.lastGoodProfileId)
        assertEquals(mapOf(7L to 33.5), saved.lastSpeedByProfile)

        store.removeProfile(group.id, 5L)
        assertEquals(listOf(7L), store.groups.first().single().profileIds)

        store.deleteGroup(group.id)
        assertTrue(store.groups.first().isEmpty())
        assertNull(store.activeGroupId.first()) // حذف النشطة يلغي اختيارها
    }

    @Test
    fun `group mode arabic labels exist for every mode`() {
        assertEquals("الأسرع", GroupMode.FASTEST.arabicLabel)
        assertEquals("عشوائي", GroupMode.RANDOM.arabicLabel)
        assertEquals("تناوبي", GroupMode.ROUND_ROBIN.arabicLabel)
    }
}
