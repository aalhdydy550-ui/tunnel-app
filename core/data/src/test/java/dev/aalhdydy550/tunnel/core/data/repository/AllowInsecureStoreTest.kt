package dev.aalhdydy550.tunnel.core.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for the "allow insecure" setting: it is opt-in and OFF
 * by default, and toggling it round-trips through the real DataStore
 * file.
 */
class AllowInsecureStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var repository: DataStoreAllowInsecureRepository

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(tempFolder.newFolder(), "test-allow-insecure.preferences_pb")
        }
        repository = DataStoreAllowInsecureRepository(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `default is off`() = runTest {
        assertFalse(repository.enabled.first())
        assertEquals(DataStoreAllowInsecureRepository.DEFAULT_ENABLED, false)
    }

    @Test
    fun `enabling persists`() = runTest {
        repository.setEnabled(true)
        assertEquals(true, repository.enabled.first())
    }

    @Test
    fun `disabling after enabling persists`() = runTest {
        repository.setEnabled(true)
        repository.setEnabled(false)
        assertEquals(false, repository.enabled.first())
    }
}
