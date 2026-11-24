package dev.aalhdydy550.tunnel.core.data

import dev.aalhdydy550.tunnel.core.data.backup.exportProfilesToJson
import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.model.ProfileType
import dev.aalhdydy550.tunnel.core.data.model.isSameServer
import dev.aalhdydy550.tunnel.core.data.repository.ProfileRepository
import dev.aalhdydy550.tunnel.core.database.ProfileDao
import dev.aalhdydy550.tunnel.core.database.ProfileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ProfileRepository.addOrReplace] with an in-memory
 * fake DAO (same pattern as SubscriptionRepositoryTest).
 *
 * The rule under test: importing the same server twice updates the
 * stored row instead of adding a second identical row — the exact
 * bug from the user's screenshot (one WireGuard `.conf` imported
 * twice produced two identical rows).
 */
class ProfileRepositoryTest {

    private class FakeProfileDao : ProfileDao {
        private val rows = mutableListOf<ProfileEntity>()
        private var nextId = 1L
        private val flow = MutableStateFlow<List<ProfileEntity>>(emptyList())

        private fun emit() {
            flow.value = rows.toList()
        }

        override fun getAll(): Flow<List<ProfileEntity>> = flow

        override suspend fun getById(id: Long): ProfileEntity? =
            rows.firstOrNull { it.id == id }

        override suspend fun insert(profile: ProfileEntity): Long {
            val assigned = if (profile.id == 0L) nextId++ else profile.id
            rows += profile.copy(id = assigned)
            emit()
            return assigned
        }

        override suspend fun update(profile: ProfileEntity) {
            val index = rows.indexOfFirst { it.id == profile.id }
            if (index >= 0) rows[index] = profile
            emit()
        }

        override suspend fun delete(profile: ProfileEntity) {
            rows.removeIf { it.id == profile.id }
            emit()
        }

        override suspend fun deleteById(id: Long) {
            rows.removeIf { it.id == id }
            emit()
        }

        override suspend fun getBySubscriptionId(
            subscriptionId: Long,
        ): List<ProfileEntity> = rows.filter { it.subscriptionId == subscriptionId }

        override suspend fun deleteBySubscriptionId(subscriptionId: Long) {
            rows.removeIf { it.subscriptionId == subscriptionId }
            emit()
        }

        override suspend fun replaceBySubscriptionId(
            subscriptionId: Long,
            profiles: List<ProfileEntity>,
        ) {
            deleteBySubscriptionId(subscriptionId)
            for (profile in profiles) {
                insert(profile)
            }
        }

        override suspend fun count(): Int = rows.size
    }

    private fun wireGuard(
        name: String = "msf:6023",
        server: String = "190.2.149.6",
        port: Int = 51820,
        outboundJson: String = """{"type":"wireguard"}""",
        countryCode: String = "",
        createdAt: Long = 1L,
    ) = Profile(
        name = name,
        type = ProfileType.WIREGUARD,
        server = server,
        port = port,
        outboundJson = outboundJson,
        countryCode = countryCode,
        createdAt = createdAt,
    )

    private fun repository(dao: FakeProfileDao) = ProfileRepository(dao)

    private val ovpnText = """
        client
        dev tun
        proto udp
        remote vpn.example.com 1194
        resolv-retry infinite
        nobind
    """.trimIndent()

    @Test
    fun `ovpn text with the ovpn extension routes to the OpenVPN parser`() {
        val repo = repository(FakeProfileDao())
        val result = repo.importFromText(ovpnText, "my-server.ovpn")
        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertEquals(1, result.profiles.size)
        val profile = result.profiles[0]
        assertEquals(ProfileType.OPENVPN, profile.type)
        assertEquals("vpn.example.com", profile.server)
        assertEquals(1194, profile.port)
        assertEquals("my-server", profile.name)
    }

    @Test
    fun `ovpn text without a file name routes by content, not to the link parser`() {
        // The `.conf` lesson: content that looks like a config file
        // must never fall through to the link parser ("no valid
        // links") or the JSON branches.
        val repo = repository(FakeProfileDao())
        val result = repo.importFromText(ovpnText)
        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertEquals(1, result.profiles.size)
        assertEquals(ProfileType.OPENVPN, result.profiles[0].type)
        assertEquals("OpenVPN vpn.example.com", result.profiles[0].name)
    }

    @Test
    fun `ovpn extension match ignores case`() {
        val repo = repository(FakeProfileDao())
        val result = repo.importFromText(ovpnText, "SERVER.OVPN")
        assertEquals(1, result.profiles.size)
        assertEquals(ProfileType.OPENVPN, result.profiles[0].type)
    }

    @Test
    fun `invalid ovpn text reports the parser error instead of profiles`() {
        val repo = repository(FakeProfileDao())
        val result = repo.importFromText(
            "client\ndev tun\nproto udp\n",
            "broken.ovpn",
        )
        assertTrue(result.profiles.isEmpty())
        assertEquals(1, result.errors.size)
        assertTrue(result.errors[0].contains("remote"))
    }

    @Test
    fun `a JSON backup containing an OpenVPN profile still restores as a backup`() {
        // The backup embeds the normalised `.ovpn` text inside its
        // `outbound_json` string (with the words "remote" and
        // "dev tun" in it) — the line-anchored content check must
        // not misroute it as a lone `.ovpn` file.
        val repo = repository(FakeProfileDao())
        val imported = repo.importFromText(ovpnText, "my-server.ovpn").profiles[0]
        val backup = exportProfilesToJson(listOf(imported))
        val restored = repo.importFromText(backup)
        assertTrue(restored.errors.toString(), restored.errors.isEmpty())
        assertEquals(1, restored.profiles.size)
        assertEquals(ProfileType.OPENVPN, restored.profiles[0].type)
        assertEquals("vpn.example.com", restored.profiles[0].server)
        assertEquals(imported.outboundJson, restored.profiles[0].outboundJson)
    }

    @Test
    fun `importing the same ovpn twice keeps one row`() = runTest {
        val dao = FakeProfileDao()
        val repo = repository(dao)
        val first = repo.importFromText(ovpnText, "my-server.ovpn").profiles[0]
        val second = repo.importFromText(ovpnText, "my-server.ovpn").profiles[0]
        assertTrue(isSameServer(first, second))
        val firstId = repo.addOrReplace(first)
        val secondId = repo.addOrReplace(second)
        assertEquals(firstId, secondId)
        assertEquals(1, dao.count())
    }

    @Test
    fun `importing the same WireGuard server twice keeps one row`() = runTest {
        val dao = FakeProfileDao()
        val repo = repository(dao)
        val firstId = repo.addOrReplace(wireGuard())
        val secondId = repo.addOrReplace(
            wireGuard(name = "msf:6023-renamed", outboundJson = """{"type":"wireguard","v":2}"""),
        )
        assertEquals(firstId, secondId)
        assertEquals(1, dao.count())
        val stored = dao.getById(firstId)!!
        assertEquals("msf:6023-renamed", stored.name)
        assertEquals("""{"type":"wireguard","v":2}""", stored.outboundJson)
    }

    @Test
    fun `addOrReplace returns a new id for a new server`() = runTest {
        val dao = FakeProfileDao()
        val repo = repository(dao)
        val id1 = repo.addOrReplace(wireGuard())
        val id2 = repo.addOrReplace(wireGuard(server = "203.0.113.7"))
        assertTrue(id1 > 0)
        assertTrue(id2 > 0)
        assertTrue(id1 != id2)
        assertEquals(2, dao.count())
    }

    @Test
    fun `different port is a different profile`() = runTest {
        val dao = FakeProfileDao()
        val repo = repository(dao)
        repo.addOrReplace(wireGuard())
        repo.addOrReplace(wireGuard(port = 51821))
        assertEquals(2, dao.count())
    }

    @Test
    fun `different type is a different profile`() = runTest {
        val dao = FakeProfileDao()
        val repo = repository(dao)
        repo.addOrReplace(wireGuard())
        repo.addOrReplace(
            wireGuard().copy(type = ProfileType.VLESS, outboundJson = """{"type":"vless"}"""),
        )
        assertEquals(2, dao.count())
    }

    @Test
    fun `re-import keeps id, createdAt and the stored country code`() = runTest {
        val dao = FakeProfileDao()
        val repo = repository(dao)
        val id = repo.addOrReplace(wireGuard(countryCode = "DE", createdAt = 12_345L))
        repo.addOrReplace(wireGuard(countryCode = "", createdAt = 99_999L))
        val stored = dao.getById(id)!!
        assertEquals(12_345L, stored.createdAt)
        assertEquals("DE", stored.countryCode)
    }

    @Test
    fun `re-import of a subscription profile keeps its subscription link`() = runTest {
        val dao = FakeProfileDao()
        val repo = repository(dao)
        val id = dao.insert(
            ProfileEntity(
                name = "Sub server",
                type = "WIREGUARD",
                server = "190.2.149.6",
                port = 51820,
                outboundJson = """{"type":"wireguard"}""",
                createdAt = 1L,
                subscriptionId = 7L,
            ),
        )
        repo.addOrReplace(wireGuard())
        assertEquals(7L, dao.getById(id)?.subscriptionId)
        assertEquals(1, dao.count())
    }

    @Test
    fun `sing-box JSON dedups only on an identical document`() = runTest {
        val dao = FakeProfileDao()
        val repo = repository(dao)
        val jsonProfile = Profile(
            name = "config",
            type = ProfileType.SINGBOX_JSON,
            server = "",
            port = 0,
            outboundJson = """{"outbounds":[]}""",
        )
        repo.addOrReplace(jsonProfile)
        repo.addOrReplace(jsonProfile)
        assertEquals(1, dao.count())
        repo.addOrReplace(jsonProfile.copy(outboundJson = """{"outbounds":[{"type":"direct"}]}"""))
        assertEquals(2, dao.count())
    }

    @Test
    fun `isSameServer matches on type plus server plus port`() {
        val base = wireGuard()
        assertTrue(isSameServer(base, base.copy(name = "other name")))
        assertFalse(isSameServer(base, base.copy(port = 51821)))
        assertFalse(isSameServer(base, base.copy(server = "203.0.113.9")))
        assertFalse(isSameServer(base, base.copy(type = ProfileType.HYSTERIA2)))
        val json = Profile(
            name = "j", type = ProfileType.SINGBOX_JSON,
            server = "", port = 0, outboundJson = """{"a":1}""",
        )
        assertTrue(isSameServer(json, json.copy(name = "renamed")))
        assertFalse(isSameServer(json, json.copy(outboundJson = """{"a":2}""")))
    }
}
