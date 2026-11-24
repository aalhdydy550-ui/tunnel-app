package dev.aalhdydy550.tunnel.core.data.repository

import dev.aalhdydy550.tunnel.core.data.model.Subscription
import dev.aalhdydy550.tunnel.core.data.profile.DefaultSubscription
import dev.aalhdydy550.tunnel.core.data.profile.SubscriptionFetcher
import dev.aalhdydy550.tunnel.core.database.ProfileDao
import dev.aalhdydy550.tunnel.core.database.SubscriptionDao
import dev.aalhdydy550.tunnel.core.database.SubscriptionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Subscriptions: user-provided URLs that serve a list of proxy links.
 *
 * A refresh fetches the URL, parses it with the same rules as a manual
 * paste, and *replaces* only the profiles that came from that
 * subscription — manual profiles are never touched, and a failed fetch
 * keeps the previous profiles (the error is recorded on the
 * subscription so the UI can show it). The same rule protects the
 * user from a subscription that goes stale or hostile: the old
 * working servers stay until a fetch succeeds.
 *
 * The app ships one built-in subscription — the default public free
 * list ([DefaultSubscription]) — added automatically at first run by
 * [DefaultSubscriptionSeeder][dev.aalhdydy550.tunnel.core.data.repository.DefaultSubscriptionSeeder]
 * and marked `isDefault`. Its URL is a user setting, not a constant:
 * the user can replace it from the import screen. Every other
 * subscription URL is the user's own (their provider's link), added
 * explicitly from the import screen. No subscription URL is ever
 * seeded with servers: the servers only arrive when the URL is
 * fetched on the user's own phone, on their real network.
 */
@Singleton
class SubscriptionRepository @Inject constructor(
    private val subscriptionDao: SubscriptionDao,
    private val profileDao: ProfileDao,
    private val client: OkHttpClient,
) {
    val subscriptions: Flow<List<Subscription>> = subscriptionDao.getAll()
        .map { list -> list.map { it.toModel() } }

    data class RefreshOutcome(val imported: Int, val errors: List<String>)

    /** Adds a subscription (no fetch yet — call [refreshSubscription]). */
    suspend fun addSubscription(url: String, name: String = ""): Long {
        val cleanUrl = url.trim()
        require(SubscriptionFetcher.isAllowedSubscriptionUrl(cleanUrl)) {
            "subscription URL must use https:// (plain http:// is allowed only for loopback hosts)"
        }
        val displayName = name.trim().ifBlank { hostOf(cleanUrl) }
        return subscriptionDao.insert(
            SubscriptionEntity(url = cleanUrl, name = displayName),
        )
    }

    /** One subscription by id, or null. */
    suspend fun getSubscription(id: Long): Subscription? =
        subscriptionDao.getById(id)?.toModel()

    /**
     * Ensures the built-in default subscription exists, and returns
     * its row id.
     *
     * - No row yet (first run, or the URL was changed to one never
     *   seen): inserts it named [DefaultSubscription.DEFAULT_NAME],
     *   marked `isDefault`, and fetches it once.
     * - The known default row exists ([existingDefaultId], or a row
     *   with this URL the user added by hand): adopts it (marks
     *   `isDefault`), updates its URL when it changed, and fetches it
     *   once when the URL changed or it was never fetched. When
     *   nothing changed and it was already fetched, no fetch happens
     *   here — the daily [SubscriptionWorker][dev.aalhdydy550.tunnel.core.data.backup.SubscriptionWorker]
     *   owns the regular refresh, so seeding costs one row + one
     *   first fetch and nothing recurring.
     *
     * A failed fetch never throws here: [refreshSubscription] keeps
     * the old profiles and records the error on the subscription, and
     * the daily worker retries it.
     */
    suspend fun ensureDefaultSubscription(url: String, existingDefaultId: Long?): Long {
        val cleanUrl = url.trim()
        require(SubscriptionFetcher.isAllowedSubscriptionUrl(cleanUrl)) {
            "subscription URL must use https:// (plain http:// is allowed only for loopback hosts)"
        }
        val known = existingDefaultId?.takeIf { it > 0 }
            ?.let { subscriptionDao.getById(it) }
            ?: subscriptionDao.getAllOnce().firstOrNull { it.url == cleanUrl }
        if (known != null) {
            val urlChanged = known.url != cleanUrl
            val needsFetch = urlChanged || known.lastFetchedAt <= 0
            if (urlChanged || !known.isDefault) {
                subscriptionDao.update(
                    known.copy(
                        url = cleanUrl,
                        isDefault = true,
                        lastFetchedAt = if (urlChanged) 0 else known.lastFetchedAt,
                    ),
                )
            }
            if (needsFetch) refreshSubscription(known.id)
            return known.id
        }
        val id = subscriptionDao.insert(
            SubscriptionEntity(
                url = cleanUrl,
                name = DefaultSubscription.DEFAULT_NAME,
                isDefault = true,
            ),
        )
        refreshSubscription(id)
        return id
    }

    /** Fetches one subscription and replaces its profiles. */
    suspend fun refreshSubscription(id: Long): RefreshOutcome {
        val subscription = subscriptionDao.getById(id)
            ?: return RefreshOutcome(0, listOf("subscription not found"))
        return try {
            val result = SubscriptionFetcher.fetchAndParse(client, subscription.url)
            // One transaction: a failure halfway through the replace
            // rolls back, so the old profiles survive intact.
            profileDao.replaceBySubscriptionId(
                id,
                result.profiles.map { profileToEntity(it.copy(subscriptionId = id)) },
            )
            subscriptionDao.update(
                subscription.copy(
                    lastFetchedAt = System.currentTimeMillis(),
                    lastProfileCount = result.profiles.size,
                    lastError = "",
                ),
            )
            RefreshOutcome(result.profiles.size, result.errors)
        } catch (e: Exception) {
            // Keep the old profiles: a failed fetch must not delete
            // working servers. Record the error for the UI.
            subscriptionDao.update(
                subscription.copy(lastError = e.message ?: "refresh failed"),
            )
            RefreshOutcome(0, listOf(e.message ?: "refresh failed"))
        }
    }

    /** Refreshes every subscription; one failure never stops the rest. */
    suspend fun refreshAll(): List<Pair<Long, RefreshOutcome>> {
        return subscriptionDao.getAllOnce().map { subscription ->
            subscription.id to refreshSubscription(subscription.id)
        }
    }

    /** Deletes a subscription and the profiles that came from it. */
    suspend fun deleteSubscription(id: Long) {
        profileDao.deleteBySubscriptionId(id)
        subscriptionDao.deleteById(id)
    }

    private fun hostOf(url: String): String = try {
        java.net.URL(url).host.ifBlank { url }
    } catch (e: Exception) {
        url
    }

    private fun SubscriptionEntity.toModel() = Subscription(
        id = id,
        url = url,
        name = name,
        lastFetchedAt = lastFetchedAt,
        lastProfileCount = lastProfileCount,
        lastError = lastError,
        isDefault = isDefault,
    )
}
