// SPDX-License-Identifier: AGPL-3.0-only
package org.aprsfoundation.core

import kotlinx.datetime.*
import kotlin.math.abs

/** Checksum-verified RMC/GGA/GLL fixes. A malformed sentence never refreshes fix age. */
class NmeaPositionDecoder {
    private var altitude: Double? = null
    private var altitudeAt = 0L
    private var speed: Double? = null
    private var heading: Double? = null
    private var motionAt = 0L
    var valid = false; private set
    var latest: BeaconPosition? = null; private set
    fun accept(line: String, receivedAt: Long): BeaconPosition? = runCatching {
        require(line.length in 10..160 && line.startsWith('$'))
        val end=line.indexOf('*'); require(end>0 && end+3==line.length)
        val checksum=line.substring(1,end).fold(0) {sum,c -> sum xor c.code}
        require(checksum==line.substring(end+1).toInt(16))
        val fields=line.substring(1,end).split(',')
        fun coordinate(text: String, hemisphere: String, lat: Boolean): Double {
            require(hemisphere in if(lat) listOf("N","S") else listOf("E","W"))
            val width=if(lat) 2 else 3
            require(text.length >= width+2)
            val minutes=text.substring(width).toDouble(); require(minutes in 0.0..<60.0)
            return (text.take(width).toDouble()+minutes/60)*(if(hemisphere in listOf("S","W")) -1 else 1)
        }
        val now=Instant.fromEpochMilliseconds(receivedAt).toLocalDateTime(TimeZone.UTC)
        fun time(text: String, date: LocalDate = now.date): Long {
            require(text.length >= 6)
            val t=LocalDateTime(date,LocalTime(text.take(2).toInt(),text.substring(2,4).toInt(),text.substring(4,6).toInt()))
            var millis=t.toInstant(TimeZone.UTC).toEpochMilliseconds()
            if(millis-receivedAt > 43_200_000) millis-=86_400_000
            if(receivedAt-millis > 43_200_000 && date==now.date) millis+=86_400_000
            require(abs(receivedAt-millis) <= 120_000) { "Stale GPS sentence" }
            return millis
        }
        val fix: AprsFix
        when(fields[0].takeLast(3)) {
            "RMC" -> {
                require(fields.size>=10)
                if(fields[2]!="A" || fields.getOrNull(12)=="N") {valid=false; return null}
                val date=fields[9]; require(date.length==6)
                val yy=date.takeLast(2).toInt()
                val utc=LocalDate(if(yy>=80) 1900+yy else 2000+yy,date.substring(2,4).toInt(),date.take(2).toInt())
                fix=AprsFix(coordinate(fields[3],fields[4],true),coordinate(fields[5],fields[6],false),time(fields[1],utc))
                speed=fields[7].toDoubleOrNull(); heading=fields[8].toDoubleOrNull(); motionAt=receivedAt
            }
            "GGA" -> {
                require(fields.size>=11)
                if((fields[6].toIntOrNull() ?: 0)==0) {valid=false; return null}
                val hdop=fields[8].toDoubleOrNull()
                fix=AprsFix(coordinate(fields[2],fields[3],true),coordinate(fields[4],fields[5],false),time(fields[1]),(hdop?:1.0)*5)
                altitude=fields[9].toDoubleOrNull()?.takeIf {fields[10]=="M"}; altitudeAt=receivedAt
            }
            "GLL" -> {
                require(fields.size>=7)
                if(fields[6]!="A" || fields.getOrNull(7)=="N") {valid=false; return null}
                fix=AprsFix(coordinate(fields[1],fields[2],true),coordinate(fields[3],fields[4],false),time(fields[5]))
            }
            else -> return null
        }
        val position=BeaconPosition(fix, speed.takeIf {receivedAt-motionAt<=5000},heading.takeIf {receivedAt-motionAt<=5000},
            altitude.takeIf {receivedAt-altitudeAt<=5000})
        valid=true; latest=position; position
    }.getOrNull()
}
