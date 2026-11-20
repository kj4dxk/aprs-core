// SPDX-License-Identifier: AGPL-3.0-only
package net.osmand.shared.aprs

import kotlin.math.*
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

data class BeaconPosition(val fix: AprsFix, val speedKnots: Double? = null, val course: Double? = null,
                          val altitudeMeters: Double? = null, val current: Boolean = true) {
    init {
        require(speedKnots == null || speedKnots.isFinite() && speedKnots >= 0)
        require(course == null || course.isFinite() && course in 0.0..360.0)
        require(altitudeMeters == null || altitudeMeters.isFinite())
    }
}
enum class BeaconTimestamp { NONE, HMS, DHM }
data class BeaconFormat(
    val table: Char = '/', val symbol: Char = '-', val timestamp: BeaconTimestamp = BeaconTimestamp.HMS,
    val compressed: Boolean = false, val courseSpeed: Boolean = true, val altitude: Boolean = true,
    val precision: Int = 0, val messaging: Boolean = true, val includeReason: Boolean = false,
    val statusTag: String = "",
) {
    init {
        require(table in "/\\" || table in 'A'..'Z' || table in '0'..'9')
        require(symbol.code in 33..126 && precision in setOf(-4,-3,-2,-1,0,1,2,4))
        require(statusTag.length <= 32 && statusTag.all { it.code in 32..126 })
    }
}
data class BeaconTiming(val minimumSeconds: Int = 10, val maximumSeconds: Int = 1800,
    val timeOnly: Boolean = false, val startStop: Boolean = true, val headingDegrees: Double = 30.0,
    val forecastErrorMeters: Double = 160.9344, val distanceMeters: Double = 1609.344,
    val movingKnots: Double = 0.5, val smart: Boolean = false,
    val slowKnots: Double = 5.0, val fastKnots: Double = 60.0, val fastSeconds: Int = 120,
    val turnSlope: Double = 255.0, val turnSeconds: Int = 30) {
    init {
        require(slowKnots.isFinite() && fastKnots.isFinite() && slowKnots>=0 && fastKnots>slowKnots)
        require(fastSeconds in 10..86400 && turnSeconds in 10..3600 && turnSlope.isFinite() && turnSlope>=0)
        require(minimumSeconds in 10..3600 && maximumSeconds in minimumSeconds..86400)
        require(headingDegrees.isFinite() && headingDegrees in 0.0..180.0)
        require(forecastErrorMeters.isFinite() && forecastErrorMeters > 0)
        require(distanceMeters.isFinite() && distanceMeters > 0 && movingKnots.isFinite() && movingKnots >= 0)
    }
}
data class BeaconDecision(val reason: String, val pressure: Double)

/** State changes only after a successful transport write. Fixed positions never masquerade as GPS. */
class AdaptiveBeaconPolicy(private val timing: BeaconTiming, initialDistanceMeters: Double = 0.0) {
    var lastPosition: BeaconPosition? = null; private set
    var lastSent: Long? = null; private set
    var distanceMeters = initialDistanceMeters; private set
    init {require(initialDistanceMeters.isFinite() && initialDistanceMeters>=0)}
    fun resetDistance() {distanceMeters=0.0}
    fun evaluate(position: BeaconPosition, now: Long, movingSource: Boolean, forced: Boolean = false,
                 reconnected: Boolean = false, gpsTransition: String? = null): BeaconDecision? {
        val previous = lastPosition
        val elapsed = lastSent?.let { (now - it).coerceAtLeast(0) } ?: Long.MAX_VALUE
        if(!position.current && gpsTransition != "GPS lost") return null
        if(forced) return if(elapsed >= 2000) BeaconDecision("Manual",100.0) else null
        if(elapsed < timing.minimumSeconds * 1000L) return null
        if(previous == null) return BeaconDecision("Initial",100.0)
        if(reconnected) return BeaconDecision("Connected",100.0)
        if(gpsTransition != null) return BeaconDecision(gpsTransition,100.0)
        if(timing.smart && movingSource && !timing.timeOnly) {
            val speed=position.speedKnots ?: 0.0
            val interval=when {
                speed<=timing.slowKnots->timing.maximumSeconds.toDouble()
                speed>=timing.fastKnots->timing.fastSeconds.toDouble()
                else->timing.fastSeconds*timing.fastKnots/speed
            }.coerceIn(timing.minimumSeconds.toDouble(),timing.maximumSeconds.toDouble())
            if(elapsed>=interval*1000) return BeaconDecision("Smart interval",100.0)
            if(speed>timing.slowKnots && elapsed>=timing.turnSeconds*1000L && previous.course!=null && position.course!=null) {
                val turn=abs((position.course-previous.course+540)%360-180)
                val threshold=(timing.headingDegrees+timing.turnSlope/speed).coerceAtMost(180.0)
                if(turn>=threshold) return BeaconDecision("Smart turn",100.0)
            }
            return null
        }
        val candidates = mutableListOf(BeaconDecision("Interval", elapsed / (timing.maximumSeconds * 10.0)))
        if(movingSource && !timing.timeOnly) {
            val movedMeters = distance(previous.fix, position.fix)
            val moved = movedMeters > 2.0
            if(timing.startStop && moved && previous.speedKnots != null && position.speedKnots != null &&
                ((previous.speedKnots == 0.0 && position.speedKnots > timing.movingKnots) ||
                 (previous.speedKnots > timing.movingKnots && position.speedKnots == 0.0)))
                candidates += BeaconDecision(if(position.speedKnots == 0.0) "Stopped" else "Moving",100.0)
            candidates += BeaconDecision("Distance",movedMeters / timing.distanceMeters * 100)
            if(previous.speedKnots != null && previous.speedKnots > timing.movingKnots && previous.course != null) {
                val forecast = previous.speedKnots * 1852 / 3600 * (elapsed / 1000.0)
                candidates += BeaconDecision("Forecast distance",forecast / timing.distanceMeters * 100)
                val projected = project(previous.fix, previous.course, forecast)
                candidates += BeaconDecision("Forecast error",distance(projected,position.fix) / timing.forecastErrorMeters * 100)
                if(moved && position.course != null && timing.headingDegrees > 0) {
                    val turn = abs((position.course - previous.course + 540) % 360 - 180)
                    candidates += BeaconDecision("Heading",turn / timing.headingDegrees * 100)
                }
            }
        }
        return candidates.maxByOrNull { it.pressure }?.takeIf { it.pressure >= 100 }
    }
    fun sent(position: BeaconPosition, now: Long) {
        lastPosition?.let { distanceMeters += distance(it.fix, position.fix) }
        lastPosition=position; lastSent=now
    }
    companion object {
        fun distance(a: AprsFix, b: AprsFix): Double {
            val x=(b.latitude-a.latitude)*PI/180; val y=(b.longitude-a.longitude)*PI/180
            val h=sin(x/2).pow(2)+cos(a.latitude*PI/180)*cos(b.latitude*PI/180)*sin(y/2).pow(2)
            return 12742000.0 * asin(sqrt(h.coerceIn(0.0,1.0)))
        }
        fun project(fix: AprsFix, bearing: Double, meters: Double): AprsFix {
            val lat=fix.latitude*PI/180; val lon=fix.longitude*PI/180; val angle=meters/6371000
            val heading=bearing*PI/180
            val newLat=asin((sin(lat)*cos(angle)+cos(lat)*sin(angle)*cos(heading)).coerceIn(-1.0,1.0))
            val newLon=lon+atan2(sin(heading)*sin(angle)*cos(lat),cos(angle)-sin(lat)*sin(newLat))
            return AprsFix(newLat*180/PI, ((newLon*180/PI+540)%360)-180,fix.timeMillis,fix.accuracyMeters)
        }
    }
}

/** APRS 1.0.1 position, object, item and status formats; no radio routing in APRS-IS packets. */
object BeaconPackets {
    fun timestamp(time: Long, kind: BeaconTimestamp): String {
        val t=Instant.fromEpochMilliseconds(time).toLocalDateTime(TimeZone.UTC)
        fun two(n: Int)=n.toString().padStart(2,'0')
        return when(kind) {
            BeaconTimestamp.NONE -> ""
            BeaconTimestamp.HMS -> two(t.hour)+two(t.minute)+two(t.second)+"h"
            BeaconTimestamp.DHM -> two(t.dayOfMonth)+two(t.hour)+two(t.minute)+"z"
        }
    }
    private fun frame(call: String, body: String): String {
        require(AprsProtocol.validCallsign(call))
        require(body.all { it.code in 32..126 })
        val packet="$call>APRS,TCPIP*:$body"
        require(packet.length <= 510)
        return packet+"\r\n"
    }
    fun position(call: String, position: BeaconPosition, format: BeaconFormat, comment: String = "", reason: String = ""): String {
        val prefix=if(format.timestamp == BeaconTimestamp.NONE) {if(format.messaging) "=" else "!"}
            else {if(format.messaging) "@" else "/"}
        return frame(call,prefix+timestamp(position.fix.timeMillis,format.timestamp)+body(position,format,comment,reason))
    }
    fun entity(owner: String, name: String, item: Boolean, alive: Boolean, position: BeaconPosition,
               format: BeaconFormat, comment: String = "", reason: String = ""): String {
        require(name.length in 3..9 && name.isNotBlank() && name==name.trim() && name.all { it.code in (if(item) 33 else 32)..126 && it !in "!_" })
        val prefix=if(item) ")$name${if(alive) '!' else '_'}" else
            ";${name.padEnd(9,' ')}${if(alive) '*' else '_'}${timestamp(position.fix.timeMillis,BeaconTimestamp.DHM)}"
        return frame(owner,prefix+body(position,format,comment,reason))
    }
    fun status(call: String, text: String, time: Long, includeTimestamp: Boolean = true, grid: String = ""): String {
        require(text.length <= 200)
        return frame(call,">"+(if(grid.isNotBlank()) "$grid " else if(includeTimestamp) timestamp(time,BeaconTimestamp.DHM) else "")+text)
    }
    fun grid(fix: AprsFix): String {
        val lon=(fix.longitude+180).coerceIn(0.0,359.999999); val lat=(fix.latitude+90).coerceIn(0.0,179.999999)
        return "${('A'.code+(lon/20).toInt()).toChar()}${('A'.code+(lat/10).toInt()).toChar()}"+
            "${(lon%20/2).toInt()}${(lat%10).toInt()}${('a'.code+(lon%2*12).toInt()).toChar()}${('a'.code+(lat%1*24).toInt()).toChar()}"
    }
    private fun body(p: BeaconPosition, f: BeaconFormat, comment: String, reason: String): String {
        require(comment.length <= 120 && comment.all { it.code in 32..126 })
        require(reason.length <= 60 && reason.all { it.code in 32..126 })
        val motion=f.courseSpeed && p.course != null && p.speedKnots != null && p.speedKnots < 1100
        val altitude=f.altitude && p.altitudeMeters != null && p.altitudeMeters > 0
        // Preserve both extensions by falling back to uncompressed, as APRSIS32 does.
        val useCompressed=f.compressed && f.precision >= 0 && !(motion && altitude)
        val data=if(useCompressed) compressed(p,f,motion,altitude) else {
            val lat=coordinate(p.fix.latitude,true,f.precision)
            val lon=coordinate(p.fix.longitude,false,f.precision)
            lat+f.table+lon+f.symbol+
                (if(motion) "${(p.course!!.roundToInt().takeIf {it>0} ?: 360).toString().padStart(3,'0')}/${p.speedKnots!!.roundToInt().coerceIn(0,999).toString().padStart(3,'0')}" else "")+
                (if(altitude) "/A=${(p.altitudeMeters!!/0.3048).roundToInt().coerceIn(0,999999).toString().padStart(6,'0')} " else "")
        }
        return data+(if(f.includeReason && reason.isNotEmpty()) "($reason)" else "")+f.statusTag+comment+
            (if(!useCompressed && f.precision > 0) dao(p.fix,f.precision) else "")
    }
    private fun coordinate(value: Double, latitude: Boolean, precision: Int): String {
        // Extra precision uses truncation so DAO's positive offset cannot cross the base cell.
        val units=if(precision > 0) floor(abs(value)*6000+1e-8).toInt() else (abs(value)*6000).roundToInt()
        val text=(units/6000).toString().padStart(if(latitude) 2 else 3,'0')+
            (units%6000/100).toString().padStart(2,'0')+"."+(units%100).toString().padStart(2,'0')
        val chars=text.toCharArray()
        if(precision<0) {
            val digits=chars.indices.filter { chars[it].isDigit() }.reversed()
            repeat(-precision) { chars[digits[it]]=' ' }
        }
        return chars.concatToString()+if(latitude) {if(value<0) 'S' else 'N'} else {if(value<0) 'W' else 'E'}
    }
    private fun dao(fix: AprsFix, digits: Int): String {
        fun remainder(value: Double)=((abs(value)*6000-floor(abs(value)*6000+1e-8))*10000).roundToInt().coerceIn(0,9999)
        val a=remainder(fix.latitude); val b=remainder(fix.longitude)
        fun base(value: Int)=(33+(value/1.1).roundToInt().coerceIn(0,90)).toChar()
        return when(digits) {
            1 -> "!W${a/1000}${b/1000}!"
            2 -> "!w${base(a/100)}${base(b/100)}!"
            else -> "!w${base(a/100)}${base(b/100)}!${base(a%100)}${base(b%100)}."
        }
    }
    private fun base91(value: Long, width: Int): String {
        var v=value
        return CharArray(width).also { chars -> for(i in width-1 downTo 0) {chars[i]=(33+v%91).toInt().toChar();v/=91} }.concatToString()
    }
    private fun compressed(p: BeaconPosition, f: BeaconFormat, motion: Boolean, altitude: Boolean): String {
        val table=if(f.table in '0'..'9') ('a'.code+(f.table-'0')).toChar() else f.table
        val coords="$table"+base91((380926*(90-p.fix.latitude)).toLong(),4)+base91((190463*(180+p.fix.longitude)).toLong(),4)+f.symbol
        val type=2+(if(p.current) 32 else 0)
        val ext=when {
            motion -> (33+(p.course!!/4).roundToInt().coerceIn(0,89)).toChar().toString()+
                (33+(ln(p.speedKnots!!+1)/ln(1.08)).roundToInt().coerceIn(0,89)).toChar()+(33+type).toChar()
            altitude -> base91((ln((p.altitudeMeters!!/0.3048).coerceAtLeast(1.0))/ln(1.002)).roundToLong().coerceIn(0,8280),2)+(33+type+16).toChar()
            else -> "  "+(33+type).toChar()
        }
        return coords+ext
    }
}
