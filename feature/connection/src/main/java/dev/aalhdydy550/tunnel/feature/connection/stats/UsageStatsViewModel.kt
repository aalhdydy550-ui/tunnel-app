package dev.aalhdydy550.tunnel.feature.connection.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.aalhdydy550.tunnel.core.data.stats.UsageSnapshot
import dev.aalhdydy550.tunnel.core.data.stats.UsageStatsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Statistics screen ViewModel (§4-#8): exposes the repository
 * snapshot and the clear-all action. No sampling, no timers — the
 * snapshot only changes when a session begins, ends, or is cleared.
 */
@HiltViewModel
class UsageStatsViewModel @Inject constructor(
    private val repository: UsageStatsRepository,
) : ViewModel() {

    val snapshot: StateFlow<UsageSnapshot?> = repository.snapshot.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null,
    )

    init {
        viewModelScope.launch { repository.refresh() }
    }

    fun clearAll() {
        viewModelScope.launch { repository.clearAll() }
    }
}
