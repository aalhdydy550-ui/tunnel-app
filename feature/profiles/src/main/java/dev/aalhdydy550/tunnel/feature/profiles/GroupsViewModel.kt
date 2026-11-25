package dev.aalhdydy550.tunnel.feature.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.aalhdydy550.tunnel.core.data.groups.ConnectionGroup
import dev.aalhdydy550.tunnel.core.data.groups.GroupMode
import dev.aalhdydy550.tunnel.core.data.groups.GroupStore
import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.repository.ProfileRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Connection groups screen state (item 6b).
 *
 * Owns nothing itself: every operation delegates to [GroupStore]
 * (create / rename / delete + membership + mode + the active group),
 * and the profile list comes from [ProfileRepository] so the screen
 * can show names when adding or removing members. The default mode
 * for a new group is [GroupMode.FASTEST] (see [ConnectionGroup]) —
 * random is never the default (it may pick a dead server).
 */
@HiltViewModel
class GroupsViewModel @Inject constructor(
    private val groupStore: GroupStore,
    profileRepository: ProfileRepository,
) : ViewModel() {

    val groups: StateFlow<List<ConnectionGroup>> = groupStore.groups
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeGroupId: StateFlow<String?> = groupStore.activeGroupId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val profiles: StateFlow<List<Profile>> = profileRepository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Creates a group; blank names are ignored. */
    fun createGroup(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { groupStore.createGroup(trimmed) }
    }

    fun renameGroup(groupId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { groupStore.renameGroup(groupId, trimmed) }
    }

    fun deleteGroup(groupId: String) {
        viewModelScope.launch { groupStore.deleteGroup(groupId) }
    }

    fun addProfile(groupId: String, profileId: Long) {
        viewModelScope.launch { groupStore.addProfile(groupId, profileId) }
    }

    fun removeProfile(groupId: String, profileId: Long) {
        viewModelScope.launch { groupStore.removeProfile(groupId, profileId) }
    }

    fun setMode(groupId: String, mode: GroupMode) {
        viewModelScope.launch { groupStore.setMode(groupId, mode) }
    }

    fun setActiveGroup(groupId: String?) {
        viewModelScope.launch { groupStore.setActiveGroup(groupId) }
    }
}
