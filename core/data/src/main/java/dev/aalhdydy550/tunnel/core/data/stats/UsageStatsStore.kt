package dev.aalhdydy550.tunnel.core.data.stats

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * File storage for [UsageStatsState]: one small private JSON file in
 * internal storage (`usage-stats.json`).
 *
 * Deliberately NOT Room: the task forbids touching the Room database
 * (`TunnelDatabase.kt` / `SubscriptionEntity.kt`), so statistics live
 * in their own file and can never disturb profiles or subscriptions.
 *
 * Writes are atomic (temp file + rename) and reads never throw: a
 * missing or corrupt file simply means "no statistics yet".
 * The file stays tiny — at most 30 sessions + 90 days of totals.
 */
class UsageStatsStore(private val file: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): UsageStatsState {
        if (!file.exists()) return UsageStatsState()
        return runCatching {
            json.decodeFromString<UsageStatsState>(file.readText())
        }.getOrElse { UsageStatsState() }
    }

    fun save(state: UsageStatsState) {
        runCatching {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            val tmp = File(parent, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(state))
            // Atomic on the same filesystem: readers never see a
            // half-written file, even if the process dies mid-save.
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    fun clear() {
        runCatching { file.delete() }
    }
}
