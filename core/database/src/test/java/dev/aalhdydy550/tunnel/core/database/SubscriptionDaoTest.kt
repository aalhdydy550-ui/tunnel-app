package dev.aalhdydy550.tunnel.core.database

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Real DAO tests on the JVM via Robolectric (in-memory Room database).
 * Verifies subscription CRUD and that deleting by subscription id
 * removes only that subscription's profiles — never manual ones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubscriptionDaoTest {

    private lateinit var db: TunnelDatabase
    private lateinit var dao: SubscriptionDao
    private lateinit var profileDao: ProfileDao

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, TunnelDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.subscriptionDao()
        profileDao = db.profileDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun subscription(url: String) = SubscriptionEntity(
        url = url,
        name = "Test",
    )

    private fun profile(name: String, subscriptionId: Long?) = ProfileEntity(
        name = name,
        type = "VLESS",
        server = "203.0.113.10",
        port = 443,
        outboundJson = """{"type":"vless"}""",
        createdAt = 1L,
        subscriptionId = subscriptionId,
    )

    @Test
    fun `insert and list round-trips`() = runTest {
        val id = dao.insert(subscription("https://example.com/sub"))
        assertTrue(id > 0)
        val all = dao.getAll().first()
        assertEquals(1, all.size)
        assertEquals("https://example.com/sub", all[0].url)
        assertEquals(0L, all[0].lastFetchedAt)
        assertEquals("", all[0].lastError)
    }

    @Test
    fun `update changes the stored fetch state`() = runTest {
        val id = dao.insert(subscription("https://example.com/sub"))
        val stored = dao.getById(id)!!
        dao.update(stored.copy(lastFetchedAt = 123L, lastProfileCount = 4))
        val updated = dao.getById(id)!!
        assertEquals(123L, updated.lastFetchedAt)
        assertEquals(4, updated.lastProfileCount)
    }

    @Test
    fun `deleteBySubscriptionId removes only its profiles`() = runTest {
        val subId = dao.insert(subscription("https://example.com/sub"))
        profileDao.insert(profile("Manual", null))
        profileDao.insert(profile("Sub one", subId))
        profileDao.insert(profile("Sub two", subId))
        assertEquals(3, profileDao.count())
        profileDao.deleteBySubscriptionId(subId)
        val remaining = profileDao.getAll().first()
        assertEquals(1, remaining.size)
        assertEquals("Manual", remaining[0].name)
        assertNull(remaining[0].subscriptionId)
    }

    @Test
    fun `profile subscriptionId round-trips`() = runTest {
        val subId = dao.insert(subscription("https://example.com/sub"))
        val id = profileDao.insert(profile("Sub server", subId))
        assertEquals(subId, profileDao.getById(id)?.subscriptionId)
    }

    @Test
    fun `isDefault defaults to false and round-trips`() = runTest {
        val id = dao.insert(subscription("https://example.com/sub"))
        assertEquals(false, dao.getById(id)!!.isDefault)
        dao.update(dao.getById(id)!!.copy(isDefault = true))
        assertEquals(true, dao.getById(id)!!.isDefault)
    }
}
