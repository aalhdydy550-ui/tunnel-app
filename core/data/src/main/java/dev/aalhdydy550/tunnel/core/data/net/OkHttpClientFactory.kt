package dev.aalhdydy550.tunnel.core.data.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The app's single OkHttp configuration: 15s connect / 30s read.
 *
 * Used by the Hilt graph ([DataModule][dev.aalhdydy550.tunnel.core.data.di.DataModule])
 * and by the background subscription worker, so a worker refresh behaves
 * exactly like a manual refresh instead of drifting to OkHttp's own
 * defaults (10s connect, no read timeout).
 */
fun defaultOkHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
