package dev.aalhdydy550.tunnel.feature.profiles

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.aalhdydy550.tunnel.core.data.model.Subscription
import dev.aalhdydy550.tunnel.core.data.profile.ProfileLinkParser
import dev.aalhdydy550.tunnel.core.data.repository.ProfileRepository
import dev.aalhdydy550.tunnel.core.data.repository.SubscriptionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Import screen logic: pasted links, clipboard, or a file
 * (link list, WireGuard `.conf`, sing-box JSON, or a JSON backup),
 * plus subscriptions — user-provided URLs that serve a link list
 * and refresh daily in the background.
 *
 * An optional 2-letter country code is applied to every imported
 * profile, so its flag emoji shows next to the server.
 */
@HiltViewModel
class ImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileRepository: ProfileRepository,
    private val subscriptionRepository: SubscriptionRepository,
) : ViewModel() {

    data class ImportOutcome(val imported: Int, val errors: List<String>)

    private val _outcome = MutableStateFlow<ImportOutcome?>(null)
    val outcome: StateFlow<ImportOutcome?> = _outcome.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** Saved subscriptions (URL, name, last fetch). */
    val subscriptions: StateFlow<List<Subscription>> =
        subscriptionRepository.subscriptions.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )

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

    /**
     * Adds a subscription URL and fetches it immediately. A failed
     * first fetch still saves the subscription (the daily worker
     * retries it) and reports the error.
     */
    fun importSubscription(url: String) {
        if (_importing.value) return
        viewModelScope.launch {
            _importing.value = true
            try {
                val id = subscriptionRepository.addSubscription(url)
                val result = subscriptionRepository.refreshSubscription(id)
                _outcome.value = ImportOutcome(result.imported, result.errors)
            } catch (e: Exception) {
                _outcome.value = ImportOutcome(0, listOf(e.message ?: "bad URL"))
            } finally {
                _importing.value = false
            }
        }
    }

    /** Re-fetches one subscription now. */
    fun refreshSubscription(id: Long) {
        if (_importing.value) return
        viewModelScope.launch {
            _importing.value = true
            val result = subscriptionRepository.refreshSubscription(id)
            _outcome.value = ImportOutcome(result.imported, result.errors)
            _importing.value = false
        }
    }

    /** Deletes a subscription and the profiles that came from it. */
    fun deleteSubscription(id: Long) {
        viewModelScope.launch {
            subscriptionRepository.deleteSubscription(id)
        }
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
            // Same server twice must update the stored row, never
            // add a second identical row (see addOrReplace).
            profileRepository.addOrReplace(
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
