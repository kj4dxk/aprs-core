// SPDX-License-Identifier: AGPL-3.0-only
package org.aprsfoundation.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class StationMarker(val callsign: String, val latitude: Double, val longitude: Double)

/** Small Swift-facing API for the receive-only milestone. */
class StationFeed(private val application: String) {
    private var client: AprsIsClient? = null
    private var messageDrain: Job? = null

    val connectionState: String get() = client?.state?.value?.name ?: "STOPPED"
    val stations: List<StationMarker>
        get() = client?.stations?.value.orEmpty().map {
            StationMarker(it.callsign, it.latitude, it.longitude)
        }

    fun start(callsign: String, host: String, port: Int, latitude: Double, longitude: Double): Boolean {
        val config = try {
            AprsIsConfig(callsign = callsign, host = host, port = port, latitude = latitude,
                longitude = longitude, application = application)
        } catch (_: IllegalArgumentException) {
            return false
        }
        stop()
        val next = AprsIsClient()
        client = next
        // The core's incoming message channel is bounded. Keep it flowing until chat is wired up.
        messageDrain = CoroutineScope(Dispatchers.Default).launch { next.messages.collect {} }
        next.start(config)
        return true
    }

    fun stop() {
        client?.stop()
        client = null
        messageDrain?.cancel()
        messageDrain = null
    }
}
