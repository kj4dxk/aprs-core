// SPDX-License-Identifier: AGPL-3.0-only
package org.aprsfoundation.core

/** javAPRSSrvr filter composition. Custom terms remain server-interpreted. */
object AprsFilters {
    const val MAX_CUSTOM_LENGTH = 420
    const val MAX_FILTER_LENGTH = 440

    fun compose(call: String, latitude: Double, longitude: Double, radiusKm: Int,
                mode: String = "range", custom: String = ""): String {
        require(AprsProtocol.validCallsign(call))
        require(mode in listOf("range", "moving", "custom"))
        require(custom.length <= MAX_CUSTOM_LENGTH && custom.all {it.code in 32..126})
        val range=AprsProtocol.rangeFilter(latitude,longitude,radiusKm)
        val base=when(mode) {"range"->range; "moving"->"m/$radiusKm"; else->""}
        val filter=listOf(base,custom.trim(),"g/$call").filter {it.isNotBlank()}.joinToString(" ")
        require(filter.length <= MAX_FILTER_LENGTH)
        return filter
    }
}
