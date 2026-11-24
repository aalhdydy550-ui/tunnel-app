package dev.aalhdydy550.tunnel.core.database

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Real DAO tests on the JVM via Robolectric (in-memory Room database).
 *
 * Covers the v4 reference tables: the seeder inserts exactly the 23
 * servers + 195 networks from [HamoReferenceSeed], is idempotent, and
 * the reference rows stay strictly separate from `profiles` — they are
 * never profiles, never shown as servers, never connected to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReferenceDaoTest {

    private lateinit var db: TunnelDatabase
    private lateinit var serverDao: ReferenceServerDao
    private lateinit var networkDao: ReferenceNetworkDao
    private lateinit var profileDao: ProfileDao

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, TunnelDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        serverDao = db.referenceServerDao()
        networkDao = db.referenceNetworkDao()
        profileDao = db.profileDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `reference tables start empty`() = runTest {
        assertEquals(0, serverDao.count())
        assertEquals(0, networkDao.count())
    }

    @Test
    fun `seeder inserts 23 servers and 195 networks`() = runTest {
        HamoReferenceSeeder(db).ensureSeeded()
        assertEquals(23, serverDao.count())
        assertEquals(195, networkDao.count())
        assertEquals(23, serverDao.getAllOnce().size)
        assertEquals(195, networkDao.getAllOnce().size)
    }

    @Test
    fun `seeder is idempotent`() = runTest {
        val seeder = HamoReferenceSeeder(db)
        seeder.ensureSeeded()
        seeder.ensureSeeded()
        assertEquals(23, serverDao.count())
        assertEquals(195, networkDao.count())
    }

    @Test
    fun `seeded servers keep the documented shape`() = runTest {
        HamoReferenceSeeder(db).ensureSeeded()
        val servers = serverDao.getAllOnce()
        val hostPattern = Regex("[0-9A-Za-z.\\-]*")
        for (server in servers) {
            assertTrue(server.name.isNotBlank())
            assertTrue(hostPattern.matches(server.host))
            assertTrue(server.tcpPort.isNotBlank())
            assertTrue(server.sslPort.isNotBlank())
            assertTrue(server.protocol in 0..3)
            assertTrue(server.flag.isNotBlank())
            assertTrue("v2ray.cool" !in server.name)
            assertTrue("v2ray.cool" !in server.host)
        }
        assertEquals(servers.size, servers.map { it.name }.toSet().size)
    }

    @Test
    fun `seeded networks keep the documented shape`() = runTest {
        HamoReferenceSeeder(db).ensureSeeded()
        val networks = networkDao.getAllOnce()
        val sniPattern = Regex("[0-9A-Za-z.\\-]*")
        for (network in networks) {
            assertTrue(network.name.isNotBlank())
            assertTrue(sniPattern.matches(network.sniHost))
            assertTrue(network.tunnelType in setOf(1, 2, 3, 8, 9))
            assertTrue("v2ray.cool" !in network.name)
            assertTrue("v2ray.cool" !in network.sniHost)
        }
        assertEquals(networks.size, networks.map { it.name }.toSet().size)
    }

    @Test
    fun `seeded data holds no credentials`() = runTest {
        HamoReferenceSeeder(db).ensureSeeded()
        val text = buildString {
            for (server in serverDao.getAllOnce()) {
                append(server.name).append(server.host)
                    .append(server.tcpPort).append(server.sslPort)
                    .append(server.flag)
            }
            for (network in networkDao.getAllOnce()) {
                append(network.name).append(network.sniHost)
                    .append(network.carrierInfo)
            }
        }.lowercase()
        assertTrue("password" !in text)
        assertTrue("passwd" !in text)
        assertTrue("v2ray.cool" !in text)
    }

    @Test
    fun `reference rows never appear in profiles`() = runTest {
        HamoReferenceSeeder(db).ensureSeeded()
        profileDao.insert(
            ProfileEntity(
                name = "Manual",
                type = "VLESS",
                server = "203.0.113.10",
                port = 443,
                outboundJson = """{"type":"vless"}""",
                createdAt = 1L,
            ),
        )
        assertEquals(1, profileDao.count())
        assertEquals(23, serverDao.count())
        assertEquals(195, networkDao.count())
        val profileNames = profileDao.getAll().first().map { it.name }.toSet()
        val referenceNames = serverDao.getAllOnce().map { it.name }.toSet() +
            networkDao.getAllOnce().map { it.name }.toSet()
        assertTrue(profileNames.intersect(referenceNames).isEmpty())
    }
}
