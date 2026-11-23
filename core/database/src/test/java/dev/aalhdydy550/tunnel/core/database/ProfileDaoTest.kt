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
 * Real DAO test on the JVM via Robolectric (in-memory Room database).
 * Verifies insert / list / get / delete round-trips.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileDaoTest {

    private lateinit var db: TunnelDatabase
    private lateinit var dao: ProfileDao

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, TunnelDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.profileDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun entity(name: String) = ProfileEntity(
        name = name,
        type = "VLESS",
        server = "203.0.113.10",
        port = 443,
        outboundJson = """{"type":"vless"}""",
        createdAt = 1L,
    )

    @Test
    fun `insert and list round-trips`() = runTest {
        val id = dao.insert(entity("first"))
        dao.insert(entity("second"))
        assertTrue(id > 0)
        val all = dao.getAll().first()
        assertEquals(2, all.size)
        assertEquals("first", all[0].name)
        assertEquals("second", all[1].name)
    }

    @Test
    fun `getById returns the inserted profile and null after delete`() = runTest {
        val id = dao.insert(entity("one"))
        val found = dao.getById(id)
        assertEquals("one", found?.name)
        dao.deleteById(id)
        assertNull(dao.getById(id))
        assertEquals(0, dao.count())
    }

    @Test
    fun `update changes the stored name`() = runTest {
        val id = dao.insert(entity("old"))
        val stored = dao.getById(id)!!
        dao.update(stored.copy(name = "new"))
        assertEquals("new", dao.getById(id)?.name)
    }
}
