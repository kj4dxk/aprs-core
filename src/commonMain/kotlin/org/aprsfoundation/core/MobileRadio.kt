package org.aprsfoundation.core

import kotlinx.datetime.Instant

object MobileRadio {
    const val kissService = "_kiss-tnc._tcp."
    fun retrySeconds(attempt: Int) = minOf(60, 1 shl attempt.coerceIn(0, 6))
    const val gpxStart = """<?xml version="1.0" encoding="UTF-8"?><gpx version="1.1" creator="APRSERJa" xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>
"""
    const val gpxEnd = "</trkseg></trk></gpx>"
    fun gpxPoint(p: BeaconPosition): String {
        val altitude = p.altitudeMeters?.let { "<ele>$it</ele>" }.orEmpty()
        return """<trkpt lat="${p.fix.latitude}" lon="${p.fix.longitude}">$altitude<time>${Instant.fromEpochMilliseconds(p.fix.timeMillis)}</time></trkpt>""" + "\n"
    }
}
