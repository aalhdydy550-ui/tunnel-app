package dev.aalhdydy550.tunnel.core.data.net

/**
 * Reconnect backoff for the automatic reconnect loop.
 *
 * Delays grow gradually (2s → 5s → 10s → 20s → 30s) and then stay at the
 * 30s cap: the tunnel keeps trying forever without hammering the server
 * or draining the battery, and the user always sees the current attempt
 * number in the UI (`جارٍ إعادة الاتصال…`).
 *
 * Pure Kotlin: unit-tested on the JVM.
 */
object ReconnectPolicy {

    /** Backoff steps in milliseconds, indexed by attempt (1-based). */
    val DELAYS_MS = longArrayOf(2_000L, 5_000L, 10_000L, 20_000L, 30_000L)

    /** Delay before attempt [attempt] (1-based). Later attempts use the cap. */
    fun delayForAttempt(attempt: Int): Long {
        val index = (attempt - 1).coerceIn(0, DELAYS_MS.lastIndex)
        return DELAYS_MS[index]
    }

    fun maxDelayMs(): Long = DELAYS_MS.last()
}
