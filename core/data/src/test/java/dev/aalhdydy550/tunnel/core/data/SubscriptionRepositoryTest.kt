package dev.aalhdydy550.tunnel.core.data

import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.model.ProfileType
import dev.aalhdydy550.tunnel.core.data.repository.SubscriptionRepository
import dev.aalhdydy550.tunnel.core.database.ProfileDao
import dev.aalhdydy550.tunnel.core.database.ProfileEntity
import dev.aalhdydy550.tunnel.core.database.SubscriptionDao
import dev.aalhdydy550.tunnel.core.database.SubscriptionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [SubscriptionRepository] with in-memory fake DAOs
 * and a local HTTP server (same ::1 pattern as EndpointProbeTest).
 *
 * The rules under test: a refresh replaces only its own subscription's
 * profiles, a failed fetch keeps the old profiles and records the
 * error, and deleting a subscription removes only its profiles.
 */
class SubscriptionRepositoryTest {

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
            // Fake runs in-memory, so delete + insert cannot fail
            // halfway; the sequence still mirrors the real DAO.
            deleteBySubscriptionId(subscriptionId)
            for (profile in profiles) {
                insert(profile)
            }
        }

        override suspend fun count(): Int = rows.size
    }

    private class FakeSubscriptionDao : SubscriptionDao {
        private val rows = mutableListOf<SubscriptionEntity>()
        private var nextId = 1L
        private val flow = MutableStateFlow<List<SubscriptionEntity>>(emptyList())

        private fun emit() {
            flow.value = rows.toList()
        }

        override fun getAll(): Flow<List<SubscriptionEntity>> = flow

        override suspend fun getById(id: Long): SubscriptionEntity? =
            rows.firstOrNull { it.id == id }

        override suspend fun getAllOnce(): List<SubscriptionEntity> = rows.toList()

        override suspend fun insert(subscription: SubscriptionEntity): Long {
            val assigned = if (subscription.id == 0L) nextId++ else subscription.id
            rows += subscription.copy(id = assigned)
            emit()
            return assigned
        }

        override suspend fun update(subscription: SubscriptionEntity) {
            val index = rows.indexOfFirst { it.id == subscription.id }
            if (index >= 0) rows[index] = subscription
            emit()
        }

        override suspend fun deleteById(id: Long) {
            rows.removeIf { it.id == id }
            emit()
        }
    }

    private fun client() = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun serverWith(body: String, code: Int = 200): FakeHttpServer {
        val server = FakeHttpServer()
        server.route("/", code, body)
        server.start()
        return server
    }

    private fun manualProfile() = ProfileEntity(
        name = "Manual",
        type = "VLESS",
        server = "203.0.113.99",
        port = 443,
        outboundJson = """{"type":"vless"}""",
        createdAt = 1L,
        subscriptionId = null,
    )

    @Test
    fun `refresh replaces only its own profiles`() = runTest {
        val profileDao = FakeProfileDao()
        val subscriptionDao = FakeSubscriptionDao()
        profileDao.insert(manualProfile())
        val subId = subscriptionDao.insert(
            SubscriptionEntity(url = "http://example.invalid/sub", name = "Test"),
        )
        profileDao.insert(
            manualProfile().copy(
                name = "Old sub server",
                server = "203.0.113.50",
                subscriptionId = subId,
            ),
        )
        val body = "vless://11111111-1111-1111-1111-111111111111@203.0.113.10:443#New"
        val server = serverWith(body)
        try {
            val port = server.port
            subscriptionDao.update(
                subscriptionDao.getById(subId)!!.copy(
                    url = "http://[::1]:$port/sub",
                ),
            )
            val repository = SubscriptionRepository(subscriptionDao, profileDao, client())
            val outcome = repository.refreshSubscription(subId)
            assertTrue(outcome.errors.isEmpty())
            assertEquals(1, outcome.imported)
            val remaining = profileDao.getAll().first()
            assertEquals(2, remaining.size)
            assertTrue(remaining.any { it.name == "Manual" })
            assertTrue(remaining.none { it.name == "Old sub server" })
            val fresh = remaining.first { it.name == "New" }
            assertEquals(subId, fresh.subscriptionId)
            val updated = subscriptionDao.getById(subId)!!
            assertEquals(1, updated.lastProfileCount)
            assertEquals("", updated.lastError)
            assertTrue(updated.lastFetchedAt > 0)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `failed refresh keeps old profiles and records the error`() = runTest {
        val profileDao = FakeProfileDao()
        val subscriptionDao = FakeSubscriptionDao()
        val subId = subscriptionDao.insert(
            SubscriptionEntity(url = "http://example.invalid/sub", name = "Test"),
        )
        profileDao.insert(
            manualProfile().copy(
                name = "Old sub server",
                server = "203.0.113.50",
                subscriptionId = subId,
            ),
        )
        val server = serverWith("nope", 500)
        try {
            val port = server.port
            subscriptionDao.update(
                subscriptionDao.getById(subId)!!.copy(
                    url = "http://[::1]:$port/sub",
                ),
            )
            val repository = SubscriptionRepository(subscriptionDao, profileDao, client())
            val outcome = repository.refreshSubscription(subId)
            assertEquals(0, outcome.imported)
            assertEquals(1, outcome.errors.size)
            assertEquals(1, profileDao.count())
            assertEquals("Old sub server", profileDao.getAll().first()[0].name)
            assertTrue(subscriptionDao.getById(subId)!!.lastError.isNotBlank())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `deleteSubscription removes only its profiles`() = runTest {
        val profileDao = FakeProfileDao()
        val subscriptionDao = FakeSubscriptionDao()
        profileDao.insert(manualProfile())
        val subId = subscriptionDao.insert(
            SubscriptionEntity(url = "http://example.invalid/sub", name = "Test"),
        )
        profileDao.insert(
            manualProfile().copy(
                name = "Sub server",
                server = "203.0.113.50",
                subscriptionId = subId,
            ),
        )
        val repository = SubscriptionRepository(
            subscriptionDao,
            profileDao,
            client(),
        )
        repository.deleteSubscription(subId)
        assertEquals(1, profileDao.count())
        assertEquals("Manual", profileDao.getAll().first()[0].name)
        assertNull(subscriptionDao.getById(subId))
    }

    @Test
    fun `addSubscription rejects a non-http URL`() = runTest {
        val repository = SubscriptionRepository(
            FakeSubscriptionDao(),
            FakeProfileDao(),
            client(),
        )
        try {
            repository.addSubscription("ftp://example.com/sub")
            org.junit.Assert.fail("expected an exception for a non-http URL")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("http"))
        }
    }

    @Test
    fun `addSubscription rejects plain http to a non-loopback host`() = runTest {
        val repository = SubscriptionRepository(
            FakeSubscriptionDao(),
            FakeProfileDao(),
            client(),
        )
        try {
            repository.addSubscription("http://example.com/sub")
            org.junit.Assert.fail("expected an exception for plain http to a remote host")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("http"))
        }
    }

    @Test
    fun `addSubscription defaults the name to the host`() = runTest {
        val subscriptionDao = FakeSubscriptionDao()
        val repository = SubscriptionRepository(
            subscriptionDao,
            FakeProfileDao(),
            client(),
        )
        val id = repository.addSubscription("https://subs.example.com/list.txt")
        assertEquals("subs.example.com", subscriptionDao.getById(id)!!.name)
    }
}
