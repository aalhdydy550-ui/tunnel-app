package dev.aalhdydy550.tunnel.core.data.profile

/**
 * The built-in default free subscription.
 *
 * The app ships NO server of its own and NO verified live node (no
 * node could be verified from the build environment — every liveness
 * check there is fake, so the candidate list was deliberately never
 * seeded). Instead it ships one public subscription URL: a community
 * list that is fetched on the user's own phone, on their real
 * network, at first run and refreshed daily by the existing
 * [SubscriptionWorker][dev.aalhdydy550.tunnel.core.data.backup.SubscriptionWorker].
 * The servers it yields are public and free — slow or unstable ones
 * are expected — and the UI says so wherever they appear.
 *
 * The URL is a *setting*, not a constant burned into the behaviour:
 * the user can replace it from the import screen
 * ([SettingsRepository][dev.aalhdydy550.tunnel.core.data.repository.SettingsRepository]).
 * This object only holds the factory value used when the user has
 * never chosen one.
 *
 * Current default: the ShadowsocksAggregator `Eternity.txt` list
 * (mahdibland/ShadowsocksAggregator) — the freshest public list found
 * in the 2026-11-24 survey (updated that same day, ~199 nodes,
 * refreshed by its maintainer about every 12 hours). It serves plain
 * links, which [SubscriptionFetcher] already parses; no allow-list
 * change was needed (`https://` is always allowed).
 */
object DefaultSubscription {

    /** The factory default subscription URL (changeable in Settings). */
    const val DEFAULT_URL =
        "https://raw.githubusercontent.com/mahdibland/ShadowsocksAggregator/master/Eternity.txt"

    /** Display name of the default subscription in the UI. */
    const val DEFAULT_NAME = "سيرفرات مجانية عامة"

    /**
     * Warning shown wherever default-subscription servers appear:
     * public and free means slow/unstable, and never for logins or
     * banking.
     */
    const val PUBLIC_WARNING =
        "خوادم عامة مجانية — قد تكون بطيئة أو غير مستقرة، " +
            "ولا تستخدمها لتسجيل الدخول أو في البنوك."

    /**
     * The URL to seed: the user's configured one, or [DEFAULT_URL]
     * when they never set one (blank counts as never set).
     */
    fun resolveUrl(configured: String?): String {
        val trimmed = configured?.trim().orEmpty()
        return trimmed.ifBlank { DEFAULT_URL }
    }
}
