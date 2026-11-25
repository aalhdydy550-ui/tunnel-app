package dev.aalhdydy550.tunnel.feature.profiles

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.aalhdydy550.tunnel.core.data.groups.ConnectionGroup
import dev.aalhdydy550.tunnel.core.data.groups.GroupMode
import dev.aalhdydy550.tunnel.core.data.groups.GroupStore
import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.model.ProfileType
import dev.aalhdydy550.tunnel.core.data.model.SpeedTestResult
import dev.aalhdydy550.tunnel.core.data.repository.ProfileRepository
import dev.aalhdydy550.tunnel.core.data.repository.SettingsRepository
import dev.aalhdydy550.tunnel.core.data.repository.SpeedTestStore
import dev.aalhdydy550.tunnel.core.data.routing.RoutingDataCatalog
import dev.aalhdydy550.tunnel.core.data.routing.RoutingDataManager
import dev.aalhdydy550.tunnel.core.database.ProfileDao
import dev.aalhdydy550.tunnel.core.database.ProfileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests for [ProfilesViewModel.selectFastestAndWait] (item 6):
 * never null while at least one profile exists — including while a
 * latency test is already running. The pre-fix code returned null
 * there, which left the connect button (and the group failover,
 * which needs a server to start from) with nothing to connect to.
 *
 * Runs on Robolectric (a real Context for `filesDir` /
 * `PowerManager`) with an in-memory fake [ProfileDao], a real
 * DataStore-backed [SettingsRepository], and in-memory fake
 * [GroupStore] / [SpeedTestStore]. The routing-data file is
 * pre-created so the ViewModel's first-run download never touches
 * the network. Runs on CI (Gradle); the local sim harness cannot
 * build it (Android Context + PowerManager + Room + DataStore).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class ProfilesViewModelTest {

    private class FakeProfileDao : ProfileDao {
        private val _rows = MutableStateFlow<List<ProfileEntity>>(emptyList())
        private var nextId = 1L

        override fun getAll(): Flow<List<ProfileEntity>> = _rows

        override suspend fun getById(id: Long): ProfileEntity? =
            _rows.value.firstOrNull { it.id == id }

        override suspend fun insert(profile: ProfileEntity): Long {
            val stored = if (profile.id == 0L) {
                profile.copy(id = nextId++)
            } else {
                profile
            }
            _rows.value = _rows.value + stored
            return stored.id
        }

        override suspend fun update(profile: ProfileEntity) {
            _rows.value = _rows.value.map {
                if (it.id == profile.id) profile else it
            }
        }

        override suspend fun delete(profile: ProfileEntity) {
            _rows.value = _rows.value - profile
        }

        override suspend fun deleteById(id: Long) {
            _rows.value = _rows.value.filterNot { it.id == id }
        }

        override suspend fun getBySubscriptionId(
            subscriptionId: Long,
        ): List<ProfileEntity> =
            _rows.value.filter { it.subscriptionId == subscriptionId }

        override suspend fun deleteBySubscriptionId(subscriptionId: Long) {
            _rows.value = _rows.value.filterNot {
                it.subscriptionId == subscriptionId
            }
        }

        override suspend fun count(): Int = _rows.value.size
    }

    private class FakeGroupStore : GroupStore {
        private val _groups = MutableStateFlow<List<ConnectionGroup>>(emptyList())
        private val _active = MutableStateFlow<String?>(null)

        override val groups: Flow<List<ConnectionGroup>> = _groups
        override val activeGroupId: Flow<String?> = _active

        override suspend fun setActiveGroup(id: String?) {
            _active.value = id
        }

        override suspend fun createGroup(name: String): ConnectionGroup {
            val group = ConnectionGroup(name = name)
            _groups.value = _groups.value + group
            return group
        }

        override suspend fun renameGroup(id: String, name: String) {
            _groups.value = _groups.value.map {
                if (it.id == id) it.copy(name = name) else it
            }
        }

        override suspend fun deleteGroup(id: String) {
            _groups.value = _groups.value.filterNot { it.id == id }
            if (_active.value == id) _active.value = null
        }

        override suspend fun addProfile(groupId: String, profileId: Long) {
            _groups.value = _groups.value.map {
                if (it.id == groupId && profileId !in it.profileIds) {
                    it.copy(profileIds = it.profileIds + profileId)
                } else {
                    it
                }
            }
        }

        override suspend fun removeProfile(groupId: String, profileId: Long) {
            _groups.value = _groups.value.map {
                if (it.id == groupId) it.copy(profileIds = it.profileIds - profileId) else it
            }
        }

        override suspend fun setMode(groupId: String, mode: GroupMode) {
            _groups.value = _groups.value.map {
                if (it.id == groupId) it.copy(mode = mode) else it
            }
        }

        override suspend fun recordSuccess(groupId: String, profileId: Long) {
            _groups.value = _groups.value.map {
                if (it.id == groupId) it.copy(lastGoodProfileId = profileId) else it
            }
        }

        override suspend fun recordSpeed(
            groupId: String,
            profileId: Long,
            mbps: Double,
        ) {
            _groups.value = _groups.value.map {
                if (it.id == groupId) {
                    it.copy(lastSpeedByProfile = it.lastSpeedByProfile + (profileId to mbps))
                } else {
                    it
                }
            }
        }
    }

    private class FakeSpeedTestStore : SpeedTestStore {
        private val _results = MutableStateFlow<Map<Long, SpeedTestResult>>(emptyMap())

        override fun lastResult(profileId: Long): Flow<SpeedTestResult?> =
            _results.map { it[profileId] }

        override fun allResults(): Flow<Map<Long, SpeedTestResult>> = _results

        override suspend fun saveResult(profileId: Long, result: SpeedTestResult) {
            _results.value = _results.value + (profileId to result)
        }
    }

    private lateinit var context: Context
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var profileRepository: ProfileRepository

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        // Routing data already present: the ViewModel's first-run
        // download sees READY and never touches the network.
        val dir = File(context.filesDir, RoutingDataManager.RULE_SETS_DIR_NAME)
        dir.mkdirs()
        File(dir, RoutingDataCatalog.ADS_RULE_SET_FILE).writeText("dummy")
        val dataStore: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                scope = TestScope(UnconfinedTestDispatcher()),
                produceFile = { File(context.filesDir, "test.preferences_pb") },
            )
        settingsRepository = SettingsRepository(dataStore)
        profileRepository = ProfileRepository(FakeProfileDao())
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = ProfilesViewModel(
        context = context,
        profileRepository = profileRepository,
        settingsRepository = settingsRepository,
        groupStore = FakeGroupStore(),
        speedTestStore = FakeSpeedTestStore(),
    )

    private suspend fun addProfile(
        name: String,
        type: ProfileType,
        server: String,
        port: Int,
    ): Profile {
        val id = profileRepository.add(
            Profile(
                name = name,
                type = type,
                server = server,
                port = port,
                outboundJson = "{}",
            ),
        )
        return profileRepository.getById(id)!!
    }

    /**
     * The ViewModel's `profiles` is a `WhileSubscribed` sharing flow:
     * it only fills once something collects it. Collect in the
     * background and wait until the rows arrive.
     */
    private suspend fun TestScope.awaitProfiles(vm: ProfilesViewModel) {
        backgroundScope.launch { vm.profiles.collect {} }
        vm.profiles.first { it.isNotEmpty() }
    }

    @Test
    fun singleUdpOnlyProfile_returnsIt() = runTest {
        val profile = addProfile("wg-1", ProfileType.WIREGUARD, "10.0.0.1", 51820)
        val vm = viewModel()
        awaitProfiles(vm)
        val selected = vm.selectFastestAndWait()
        assertNotNull("one profile must always yield a server", selected)
        assertEquals(profile.id, selected!!.id)
    }

    @Test
    fun emptyList_returnsNull() = runTest {
        val vm = viewModel()
        assertNull(vm.selectFastestAndWait())
    }

    @Test
    fun singleProfile_whileTestRunning_returnsIt() = runTest {
        // Unroutable TCP target: the probe blocks the full 3s
        // timeout on IO, so the test is definitely still running
        // when selectFastestAndWait is called — the pre-fix code
        // returned null here.
        val profile = addProfile("vless-1", ProfileType.VLESS, "10.255.255.1", 443)
        val vm = viewModel()
        awaitProfiles(vm)
        vm.testLatency()
        assertTrue(
            "the latency test must be running for this case",
            vm.latencyTesting.value,
        )
        val selected = vm.selectFastestAndWait()
        assertNotNull(
            "must wait for the running test (or fall back), not give up with null",
            selected,
        )
        assertEquals(profile.id, selected!!.id)
    }
}
