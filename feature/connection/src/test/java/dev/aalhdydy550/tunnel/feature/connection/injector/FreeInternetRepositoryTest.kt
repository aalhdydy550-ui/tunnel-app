package dev.aalhdydy550.tunnel.feature.connection.injector

import dev.aalhdydy550.tunnel.core.database.ReferenceNetworkDao
import dev.aalhdydy550.tunnel.core.database.ReferenceNetworkEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FreeInternetRepositoryTest {

    private class FakeDao(
        private val rows: List<ReferenceNetworkEntity>,
    ) : ReferenceNetworkDao {
        override fun getAll(): Flow<List<ReferenceNetworkEntity>> = flowOf(rows)
        override suspend fun getAllOnce(): List<ReferenceNetworkEntity> = rows
        override suspend fun insertAll(networks: List<ReferenceNetworkEntity>) = Unit
        override suspend fun count(): Int = rows.size
    }

    private fun row(id: Long, name: String, sni: String, type: Int) =
        ReferenceNetworkEntity(
            id = id,
            name = name,
            sniHost = sni,
            tunnelType = type,
            carrierInfo = "WE — test",
        )

    @Test
    fun `only tunnel type 3 with a non-blank SNI qualifies`() = runTest {
        val dao = FakeDao(
            listOf(
                row(1, "ok-we", "sni.example.com", 3),
                row(2, "no-sni", "", 3),
                row(3, "blank-sni", "   ", 3),
                row(4, "type-9", "sni.example.com", 9),
                row(5, "type-2", "sni.example.com", 2),
                row(6, "type-8", "sni.example.com", 8),
                row(7, "type-1", "sni.example.com", 1),
            ),
        )
        val candidates = FreeInternetRepository(dao).loadCandidates()
        assertEquals(listOf(1L), candidates.map { it.id })
    }

    @Test
    fun `SIM-carrier match comes first, then verified, then name`() = runTest {
        val dao = FakeDao(
            listOf(
                ReferenceNetworkEntity(
                    1, "Zebra", "a.example.com", 3, "WE — x",
                ),
                ReferenceNetworkEntity(
                    2, "Alpha", "b.example.com", 3, "Etisalat — x",
                ),
                ReferenceNetworkEntity(
                    3, "Mango", "c.example.com", 3, "Etisalat — x",
                ),
                ReferenceNetworkEntity(
                    4, "Beta", "d.example.com", 3, "WE — x",
                ),
            ),
        )
        val repo = FreeInternetRepository(dao)
        // Carrier WE: WE rows first (Beta, Zebra by name), then the
        // verified Etisalat row (Mango), then the rest by name.
        val ordered = repo.loadCandidates(
            carrier = Carrier.WE,
            verifiedIds = setOf(3L),
        )
        assertEquals(listOf(4L, 1L, 3L, 2L), ordered.map { it.id })
    }

    @Test
    fun `with no carrier and nothing verified the order is by name`() = runTest {
        val dao = FakeDao(
            listOf(
                row(1, "Zebra", "a.example.com", 3),
                row(2, "Alpha", "b.example.com", 3),
                row(3, "Mango", "c.example.com", 3),
            ),
        )
        val ordered = FreeInternetRepository(dao).loadCandidates()
        assertEquals(listOf(2L, 3L, 1L), ordered.map { it.id })
    }
}
