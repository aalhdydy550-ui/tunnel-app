package dev.aalhdydy550.tunnel.core.data.repository

import dev.aalhdydy550.tunnel.core.data.profile.DefaultSubscription
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * First-run seeding of the built-in default free subscription.
 *
 * Runs once per app start (see `TunnelApp`) on a background thread:
 * when the default subscription is missing it is created and fetched
 * once, on the user's own phone and real network, through the normal
 * subscription path ([SubscriptionRepository.ensureDefaultSubscription]).
 * Afterwards the existing daily [SubscriptionWorker][dev.aalhdydy550.tunnel.core.data.backup.SubscriptionWorker]
 * owns the refresh — seeding adds no recurring work, no extra wakeup,
 * and no change to the tunnel/connection path.
 *
 * The rules, in order:
 * 1. Disabled (the user deleted the default subscription) → nothing.
 *    Deleting is respected: the seeder never silently re-adds a
 *    subscription the user removed. Saving a URL in the import
 *    screen re-enables it.
 * 2. A stored default id whose row is gone (deleted outside the UI)
 *    → treated as a deletion: disabled, nothing re-added.
 * 3. Otherwise ensure the row exists for the configured URL
 *    ([DefaultSubscription.resolveUrl] over the setting) and remember
 *    its id, so later starts find it instead of inserting a duplicate.
 */
@Singleton
class DefaultSubscriptionSeeder @Inject constructor(
    private val subscriptionRepository: SubscriptionRepository,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun ensureSeeded() {
        if (!settingsRepository.defaultSubscriptionEnabled.first()) return
        val url = DefaultSubscription.resolveUrl(
            settingsRepository.defaultSubscriptionUrl.first(),
        )
        val storedId = settingsRepository.defaultSubscriptionId.first()
        if (storedId > 0 && subscriptionRepository.getSubscription(storedId) == null) {
            // Row gone without going through the UI delete path:
            // treat it as a user deletion, not as a reason to re-add.
            settingsRepository.setDefaultSubscriptionEnabled(false)
            return
        }
        val id = subscriptionRepository.ensureDefaultSubscription(
            url,
            storedId.takeIf { it > 0 },
        )
        if (id != storedId) settingsRepository.setDefaultSubscriptionId(id)
    }
}
