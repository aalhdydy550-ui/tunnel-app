package dev.aalhdydy550.tunnel.core.data.repository

import dev.aalhdydy550.tunnel.core.data.model.Subscription
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
 * The app ships no subscription URL. The URL is the user's own
 * (their provider's link), added explicitly from the import screen.
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
        require(
            cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://"),
        ) { "subscription URL must start with http:// or https://" }
        val displayName = name.trim().ifBlank { hostOf(cleanUrl) }
        return subscriptionDao.insert(
            SubscriptionEntity(url = cleanUrl, name = displayName),
        )
    }

    /** Fetches one subscription and replaces its profiles. */
    suspend fun refreshSubscription(id: Long): RefreshOutcome {
        val subscription = subscriptionDao.getById(id)
            ?: return RefreshOutcome(0, listOf("subscription not found"))
        return try {
            val result = SubscriptionFetcher.fetchAndParse(client, subscription.url)
            profileDao.deleteBySubscriptionId(id)
            for (profile in result.profiles) {
                profileDao.insert(
                    profileToEntity(profile.copy(subscriptionId = id)),
                )
            }
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
    )
}
