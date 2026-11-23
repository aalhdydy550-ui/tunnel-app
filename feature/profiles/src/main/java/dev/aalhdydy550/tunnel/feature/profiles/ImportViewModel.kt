package dev.aalhdydy550.tunnel.feature.profiles

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.aalhdydy550.tunnel.core.data.profile.ProfileLinkParser
import dev.aalhdydy550.tunnel.core.data.repository.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Import screen logic: pasted links, clipboard, or a file
 * (link list, WireGuard `.conf`, sing-box JSON, or a JSON backup).
 *
 * An optional 2-letter country code is applied to every imported
 * profile, so its flag emoji shows next to the server.
 */
@HiltViewModel
class ImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    data class ImportOutcome(val imported: Int, val errors: List<String>)

    private val _outcome = MutableStateFlow<ImportOutcome?>(null)
    val outcome: StateFlow<ImportOutcome?> = _outcome.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    fun importText(text: String, countryCode: String) {
        if (_importing.value) return
        viewModelScope.launch {
            _importing.value = true
            val result = profileRepository.importFromText(text)
            store(result, countryCode)
            _importing.value = false
        }
    }

    fun importUri(uri: Uri) {
        if (_importing.value) return
        viewModelScope.launch {
            _importing.value = true
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                _outcome.value = ImportOutcome(
                    0,
                    listOf("تعذّرت قراءة الملف"),
                )
            } else {
                val result = profileRepository.importFromText(text, uri.lastPathSegment ?: "")
                store(result, "")
            }
            _importing.value = false
        }
    }

    /** Returns the clipboard text, or null when it is empty. */
    fun pasteFromClipboard(): String? {
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            ?.takeIf { it.isNotBlank() }
    }

    fun clearOutcome() {
        _outcome.value = null
    }

    private suspend fun store(
        result: ProfileLinkParser.ImportResult,
        countryCode: String,
    ) {
        val code = countryCode.trim().uppercase()
            .takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
            ?: ""
        for (profile in result.profiles) {
            profileRepository.add(
                if (code.isNotEmpty() && profile.countryCode.isEmpty()) {
                    profile.copy(countryCode = code)
                } else {
                    profile
                },
            )
        }
        _outcome.value = ImportOutcome(result.profiles.size, result.errors)
    }
}
