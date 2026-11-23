package dev.aalhdydy550.tunnel.feature.connection

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * In-memory ring buffer of tunnel events (connect, disconnect, reconnect
 * attempt, heartbeat failure, network change, …) with a Logcat mirror.
 *
 * Every disconnection records its reason here, so a dropped tunnel is
 * never silent: the UI shows the last reason, and the log survives in
 * Logcat for debugging. Kept in memory only (last 100 events) — no
 * file is written in v0.1.
 */
object TunnelEventLog {
    private const val TAG = "TunnelEventLog"
    private const val MAX_EVENTS = 100

    data class Event(val timeMs: Long, val message: String) {
        val formattedTime: String
            get() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timeMs))
    }

    private val events = ConcurrentLinkedQueue<Event>()

    fun log(message: String) {
        Log.i(TAG, message)
        events += Event(System.currentTimeMillis(), message)
        while (events.size > MAX_EVENTS) events.poll()
    }

    fun logDisconnect(reason: String) = log("disconnected: $reason")

    fun snapshot(): List<Event> = events.toList()
}
