package dev.aalhdydy550.tunnel.feature.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.aalhdydy550.tunnel.core.data.backup.LockedExport
import dev.aalhdydy550.tunnel.core.data.backup.QrCodeEncoder
import dev.aalhdydy550.tunnel.core.data.backup.exportProfilesToJson
import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.repository.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Locked (encrypted) export — item 4 of the "better app" round.
 *
 * Holds the profile selection, the lock password, and the results of
 * locking / QR generation. The password lives in memory only while
 * the operation runs (same rule as the SSH password in the injector)
 * — it is never saved to DataStore, Room, or any file.
 *
 * The crypto itself lives in `:core:data`
 * ([LockedExport] / [QrCodeEncoder]); this ViewModel only wires the
 * UI state to it. No network, no permissions, no Room here.
 */
data class LockedExportUiState(
    val profiles: List<Profile> = emptyList(),
    /** All profiles are selected by default. */
    val selectedIds: Set<Long> = emptySet(),
    val password: String = "",
    val confirm: String = "",
    val error: String? = null,
    /** The `aytun://v1.…` text of the last successful lock, if any. */
    val lockedText: String? = null,
    /** ARGB pixels of the generated QR code, if any. */
    val qrPixels: IntArray? = null,
    /** Width/height of [qrPixels] in pixels (0 when no QR). */
    val qrSize: Int = 0,
    val busy: Boolean = false,
)

@HiltViewModel
class LockedExportViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LockedExportUiState())
    val uiState: StateFlow<LockedExportUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            profileRepository.profiles.collect { profiles ->
                _uiState.update { state ->
                    // First load: select everything. Later emissions keep
                    // the existing selection, dropping ids that are gone
                    // and selecting any brand-new profile.
                    val selected = if (state.profiles.isEmpty() &&
                        state.selectedIds.isEmpty()
                    ) {
                        profiles.map { it.id }.toSet()
                    } else {
                        val known = profiles.map { it.id }.toSet()
                        val kept = state.selectedIds.intersect(known)
                        val added = known - state.profiles.map { it.id }.toSet()
                        kept + added
                    }
                    state.copy(profiles = profiles, selectedIds = selected)
                }
            }
        }
    }

    fun toggleSelect(id: Long) {
        _uiState.update { state ->
            val selected = if (id in state.selectedIds) {
                state.selectedIds - id
            } else {
                state.selectedIds + id
            }
            state.copy(selectedIds = selected, lockedText = null, qrPixels = null, qrSize = 0)
        }
    }

    fun selectAll() {
        _uiState.update { state ->
            state.copy(
                selectedIds = state.profiles.map { it.id }.toSet(),
                lockedText = null,
                qrPixels = null,
                qrSize = 0,
            )
        }
    }

    fun clearSelection() {
        _uiState.update { state ->
            state.copy(selectedIds = emptySet(), lockedText = null, qrPixels = null, qrSize = 0)
        }
    }

    fun setPassword(pw: String) {
        _uiState.update { it.copy(password = pw, lockedText = null, qrPixels = null, qrSize = 0) }
    }

    fun setConfirm(pw: String) {
        _uiState.update { it.copy(confirm = pw, lockedText = null, qrPixels = null, qrSize = 0) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Locks the selected profiles with the entered password and puts
     * the `aytun://v1.…` text in [LockedExportUiState.lockedText].
     * The password chars are wiped from memory right after use.
     */
    fun lockSelected() {
        val state = _uiState.value
        val selected = state.profiles.filter { it.id in state.selectedIds }
        when {
            selected.isEmpty() -> {
                _uiState.update { it.copy(error = "اختر بروفايلًا واحدًا على الأقل أولًا.") }
                return
            }
            state.password.isEmpty() -> {
                _uiState.update { it.copy(error = "اكتب كلمة سر للقفل أولًا.") }
                return
            }
            state.password != state.confirm -> {
                _uiState.update { it.copy(error = "كلمتا السر غير متطابقتين.") }
                return
            }
        }
        val passwordChars = state.password.toCharArray()
        _uiState.update { it.copy(busy = true, error = null, lockedText = null) }
        viewModelScope.launch {
            val locked = runCatching {
                withContext(Dispatchers.Default) {
                    val json = exportProfilesToJson(selected)
                    LockedExport.lock(json, passwordChars)
                }
            }
            passwordChars.fill('\u0000')
            locked.fold(
                onSuccess = { text ->
                    _uiState.update { it.copy(busy = false, lockedText = text) }
                },
                onFailure = {
                    _uiState.update {
                        it.copy(busy = false, error = "تعذّر قفل التصدير — حاول مجددًا.")
                    }
                },
            )
        }
    }

    /**
     * Builds a QR code for exactly one selected profile: locks that
     * profile alone with the password, then encodes the locked text.
     * On a wrong condition or an oversized payload puts an Arabic
     * message in [LockedExportUiState.error] — never a broken code.
     */
    fun buildQr() {
        val state = _uiState.value
        val selected = state.profiles.filter { it.id in state.selectedIds }
        when {
            selected.size != 1 -> {
                _uiState.update {
                    it.copy(error = "رمز QR يحمل بروفايلًا واحدًا فقط — اختر بروفايلًا واحدًا.")
                }
                return
            }
            state.password.isEmpty() -> {
                _uiState.update { it.copy(error = "اكتب كلمة سر للقفل أولًا.") }
                return
            }
            state.password != state.confirm -> {
                _uiState.update { it.copy(error = "كلمتا السر غير متطابقتين.") }
                return
            }
        }
        val passwordChars = state.password.toCharArray()
        val single = selected.single()
        _uiState.update { it.copy(busy = true, error = null, qrPixels = null, qrSize = 0) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    val json = exportProfilesToJson(listOf(single))
                    val locked = LockedExport.lock(json, passwordChars)
                    QrCodeEncoder.encode(locked)
                }
            }
            passwordChars.fill('\u0000')
            result.fold(
                onSuccess = { image ->
                    _uiState.update {
                        it.copy(busy = false, qrPixels = image.pixels, qrSize = image.size)
                    }
                },
                onFailure = { e ->
                    val message = if (e is IllegalArgumentException) {
                        "البروفايل كبير جدًا على رمز QR — شارك الملف بدلًا منه."
                    } else {
                        "تعذّر توليد رمز QR — حاول مجددًا."
                    }
                    _uiState.update { it.copy(busy = false, error = message) }
                },
            )
        }
    }
}
