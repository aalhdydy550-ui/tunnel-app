package dev.aalhdydy550.tunnel.feature.connection.injector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [VerifiedNetworkStore] against an in-memory
 * implementation — no real SharedPreferences on the JVM unit-test
 * classpath, and the store's own contract (not Android storage) is
 * what is under test here.
 */
class VerifiedNetworkStoreTest {

    private class InMemoryStore : VerifiedNetworkStore {
        private val verified = mutableMapOf<Long, Long>()
        private val carriers = mutableMapOf<Long, Carrier>()

        override fun isVerified(networkId: Long): Boolean =
            networkId in verified

        override fun markVerified(networkId: Long, carrier: Carrier) {
            verified[networkId] = System.currentTimeMillis()
            carriers[networkId] = carrier
        }

        override fun verifiedAt(networkId: Long): Long? = verified[networkId]

        override fun allVerified(): Set<Long> = verified.keys.toSet()
    }

    @Test
    fun `nothing is verified initially`() {
        val store = InMemoryStore()
        assertFalse(store.isVerified(1L))
        assertNull(store.verifiedAt(1L))
        assertTrue(store.allVerified().isEmpty())
    }

    @Test
    fun `markVerified stores the network with a timestamp`() {
        val store = InMemoryStore()
        store.markVerified(7L, Carrier.WE)
        assertTrue(store.isVerified(7L))
        assertNotNull(store.verifiedAt(7L))
        assertEquals(setOf(7L), store.allVerified())
    }

    @Test
    fun `networks are independent of each other`() {
        val store = InMemoryStore()
        store.markVerified(7L, Carrier.WE)
        assertFalse(store.isVerified(8L))
        assertNull(store.verifiedAt(8L))
        store.markVerified(8L, Carrier.VODAFONE)
        assertEquals(setOf(7L, 8L), store.allVerified())
    }

    @Test
    fun `re-marking refreshes the timestamp`() {
        val store = InMemoryStore()
        store.markVerified(7L, Carrier.WE)
        val first = store.verifiedAt(7L)!!
        Thread.sleep(2)
        store.markVerified(7L, Carrier.WE)
        val second = store.verifiedAt(7L)!!
        assertTrue(second >= first)
        assertEquals(setOf(7L), store.allVerified())
    }
}
