package dev.aalhdydy550.tunnel.core.data.model

/**
 * A saved subscription: a URL serving a list of proxy links.
 *
 * Pure Kotlin (no Android dependency) so the subscription logic can
 * be unit-tested on the JVM. The stored form lives in
 * [SubscriptionEntity][dev.aalhdydy550.tunnel.core.database.SubscriptionEntity].
 */
data class Subscription(
    val id: Long = 0,
    val url: String,
    val name: String,
    /** Epoch millis of the last fetch attempt, 0 when never fetched. */
    val lastFetchedAt: Long = 0,
    /** Profiles the last successful fetch produced. */
    val lastProfileCount: Int = 0,
    /** "" when the last fetch succeeded; the error message otherwise. */
    val lastError: String = "",
)
