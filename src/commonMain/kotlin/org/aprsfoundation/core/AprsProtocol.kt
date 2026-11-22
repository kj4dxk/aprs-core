package org.aprsfoundation.core

import kotlin.math.abs
import kotlin.math.roundToInt

data class AprsFix(val latitude: Double, val longitude: Double, val timeMillis: Long,
                   val accuracyMeters: Double = 0.0) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(accuracyMeters.isFinite() && accuracyMeters >= 0)
    }
}

data class AprsStation(val callsign: String, val latitude: Double, val longitude: Double,
                       val symbolTable: Char, val symbol: Char, val comment: String,
                       val heardAtMillis: Long, val courseDegrees: Double? = null,
                       val speedKnots: Double? = null, val altitudeMeters: Double? = null,
                       val ambiguityDigits: Int = 0, val micEStatus: String? = null, val positionTimeMillis: Long? = null)

/** Wire-format code independent of Android, sockets, and device clocks. */
object AprsProtocol {
    private val callPattern = Regex("[A-Z0-9]{3,6}(-[1-9A-Z][0-9A-Z]?)?")
    fun validCallsign(value: String): Boolean = value.length <= 9 && callPattern.matches(value)

    fun login(callsign: String, passcode: String, filter: String, application: String = "APRSERJa"): String {
        require(validCallsign(callsign))
        require(passcode == "-1" || passcode.matches(Regex("[0-9]{1,5}")))
        require(filter.length <= 160 && filter.all { it.code in 32..126 })
        require(application.matches(Regex("[A-Za-z0-9.-]{1,24}")))
        return "user $callsign pass $passcode vers $application 0.1 filter $filter\r\n"
    }

    fun rangeFilter(latitude: Double, longitude: Double, radiusKm: Int): String {
        AprsFix(latitude, longitude, 0)
        require(radiusKm in 1..500)
        return "r/$latitude/$longitude/$radiusKm"
    }

    fun verifiedLogin(line: String, callsign: String): Boolean =
        Regex("^#\\s*logresp\\s+${Regex.escape(callsign)}\\s+verified(?:,|\\s|$)",
            RegexOption.IGNORE_CASE).containsMatchIn(line)

    fun beacon(callsign: String, fix: AprsFix, comment: String = "APRSERJa",
               symbolTable: Char = '/', symbol: Char = '>'): String {
        require(validCallsign(callsign))
        require(symbolTable == '/' || symbolTable == '\\' || symbolTable in 'A'..'Z' || symbolTable in '0'..'9')
        require(symbol.code in 33..126)
        require(comment.length <= 80 && comment.all { it.code in 32..126 })
        return "$callsign>APRS,TCPIP*:!${coordinate(fix.latitude, true)}$symbolTable" +
            "${coordinate(fix.longitude, false)}$symbol$comment\r\n"
    }

    private fun coordinate(value: Double, latitude: Boolean): String {
        // Round before splitting so 59.995 minutes carries into the next degree.
        val hundredths = (abs(value) * 6000).roundToInt()
        val degrees = (hundredths / 6000).toString().padStart(if (latitude) 2 else 3, '0')
        val minutes = (hundredths % 6000 / 100).toString().padStart(2, '0')
        val fraction = (hundredths % 100).toString().padStart(2, '0')
        val hemisphere = if (latitude) { if (value < 0) 'S' else 'N' } else { if (value < 0) 'W' else 'E' }
        return "$degrees$minutes.$fraction$hemisphere"
    }

    /** Position reports including Mic-E and centered ambiguity cells. */
    fun parse(line: String, nowMillis: Long): AprsStation? {
        val content=RadioPacket.parse(line)?.content()?.line()?:return null
        return parseWire(content,nowMillis)?.let { it.copy(positionTimeMillis=PositionTimestamp.decode(content.substringAfter(':'),nowMillis)) }
    }
    private fun parseWire(line: String, nowMillis: Long): AprsStation? = runCatching {
        require(line.length <= 510 && line.none { it == '\r' || it == '\n' })
        val separator = line.indexOf(':')
        val headerEnd = line.indexOf('>')
        require(headerEnd > 0 && separator > headerEnd)
        var callsign = line.substring(0, headerEnd)
        require(callsign.length <= 9)
        var body = line.substring(separator + 1)
        require(body.isNotEmpty())
        if(body[0] in "`'\u001c\u001d") return MicE.decode(callsign,line.substring(headerEnd+1,separator).substringBefore(','),body,nowMillis)
        if(body.startsWith(':')) {
            val message=AprsMessages.parse(line) ?: return null
            if(!message.text.startsWith(')')) return null
            return parse("$callsign>APRS:${message.text}",nowMillis)
        }
        body = when (body[0]) {
            '!', '=' -> body.substring(1)
            '/', '@' -> { require(body.length >= 8); body.substring(8) }
            ';' -> {
                require(body.length >= 18 && body[10] == '*')
                callsign = body.substring(1, 10).trim()
                body.substring(18)
            }
            ')' -> {
                val end = body.indexOf('!')
                require(end in 4..10)
                callsign = body.substring(1, end)
                body.substring(end + 1)
            }
            else -> return null
        }
        if (body.length >= 19 && body[7] in "NS" && body[17] in "EW") {
            require(body[4] == '.' && body[14] == '.')
            var lat = uncompressed(body.substring(0, 8), 2)
            var lon = uncompressed(body.substring(9, 18), 3)
            val ambiguity=body.substring(0,8).count {it==' '}
            require(ambiguity==body.substring(9,18).count {it==' '})
            if(ambiguity==0) {
                val dao=Regex("!([Ww])(.)(.)!").find(body.substring(19).replace(Regex("\\|[!-{]{4,14}\\|"),""))
                if(dao!=null) {
                    val decimal=dao.groupValues[1]=="W"
                    fun offset(index:Int):Double? {
                        val c=dao.groupValues[index][0]
                        return if(decimal) c.digitToIntOrNull()?.div(60000.0)
                            else if(c.code in 33..123)(c.code-33)/91.0/6000.0 else null
                    }
                    val a=offset(2);val b=offset(3)
                    if(a!=null && b!=null) {
                        lat+=a*(if(body[7]=='S')-1 else 1);lon+=b*(if(body[17]=='W')-1 else 1)
                        AprsFix(lat,lon,nowMillis)
                    }
                }
            }
            AprsStation(callsign, lat, lon, body[8], body[18], body.substring(19), nowMillis,ambiguityDigits=ambiguity)
        } else {
            require(body.length >= 13 && body[0] in "/\\0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ")
            fun base91(start: Int): Long = body.substring(start, start + 4).fold(0L) { total, c ->
                require(c.code in 33..123)
                total * 91 + c.code - 33
            }
            val lat = 90.0 - base91(1) / 380926.0
            val lon = -180.0 + base91(5) / 190463.0
            AprsFix(lat, lon, nowMillis)
            val c=body[10].code-33;val speed=body[11].code-33;val type=body[12].code-33
            val altitudeMode=type in 0..63 && type and 0x18 == 0x10
            AprsStation(callsign, lat, lon, body[0], body[9], body.substring(13), nowMillis,
                if(!altitudeMode && c in 0..89 && speed in 0..89 && body[9]!='_') c*4.0 else null,
                if(!altitudeMode && c in 0..89 && speed in 0..89 && body[9]!='_') kotlin.math.exp(speed*kotlin.math.ln(1.08))-1 else null,
                if(altitudeMode && c in 0..90 && speed in 0..90) kotlin.math.exp((c*91+speed)*kotlin.math.ln(1.002))*0.3048 else null)
        }
    }.getOrNull()

    private fun uncompressed(value: String, degreesWidth: Int): Double {
        require(value.substring(0, degreesWidth).all { it in '0'..'9' })
        val raw=value.substring(degreesWidth,value.lastIndex)
        require(raw.length==5 && raw[2]=='.')
        val digits=raw.replace(".","")
        val ambiguity=digits.count {it==' '}
        require(ambiguity in 0..4 && digits.take(4-ambiguity).all {it in '0'..'9'} && digits.takeLast(ambiguity).all {it==' '})
        val centered=if(ambiguity==0)digits else digits.take(4-ambiguity)+(if(ambiguity==4)"3000" else "5"+"0".repeat(ambiguity-1))
        val degrees = value.substring(0, degreesWidth).toInt()
        val minutes = centered.toInt()/100.0
        require(minutes >= 0 && minutes < 60)
        val max = if (degreesWidth == 2) 90 else 180
        require(degrees <= max && (degrees < max || minutes == 0.0))
        val sign = if (value.last() in "SW") -1 else 1
        return sign * (degrees + minutes / 60)
    }
}

/** Bounded station memory; callers serialize access and publish immutable snapshots. */
class AprsStationStore(private val maxStations: Int = 2000, private val ttlMillis: Long = 1_800_000) {
    private val stations = linkedMapOf<String, AprsStation>()
    private val owners = mutableMapOf<String,String>()
    init { require(maxStations > 0 && ttlMillis > 0) }
    fun receive(line: String, nowMillis: Long) {
        expire(nowMillis)
        val packet=RadioPacket.parse(line)?.content() ?: return
        val body=if(packet.payload.startsWith(':')) AprsMessages.parse(line)?.text?.takeIf {it.startsWith(')')} ?: packet.payload else packet.payload
        val killed=when {
            body.startsWith(';') && body.length>=11 && body[10]=='_'->body.substring(1,10).trim()
            body.startsWith(')') && body.indexOf('_') in 4..10->body.substring(1,body.indexOf('_'))
            else->null
        }
        if(killed!=null) {
            if(owners[killed]==packet.source) {stations.remove(killed);owners.remove(killed)}
            return
        }
        val station = AprsProtocol.parse(packet.line(), nowMillis) ?: return
        if(body.firstOrNull() in listOf(';',')')) owners[station.callsign]=packet.source else owners.remove(station.callsign)
        stations.remove(station.callsign)
        stations[station.callsign] = station
        while (stations.size > maxStations) {val key=stations.keys.first();stations.remove(key);owners.remove(key)}
    }
    fun snapshot(nowMillis: Long): List<AprsStation> {
        expire(nowMillis)
        return stations.values.toList()
    }
    private fun expire(nowMillis: Long) {
        stations.entries.removeAll { nowMillis - it.value.heardAtMillis >= ttlMillis }
        owners.keys.retainAll(stations.keys)
    }
}

class AprsBeaconPolicy(val intervalMillis: Long = 120_000) {
    private var lastSentMillis: Long? = null
    init { require(intervalMillis >= 60_000) }
    fun due(fix: AprsFix?, nowMillis: Long, enabled: Boolean, verified: Boolean): Boolean =
        enabled && verified && fix != null && nowMillis - fix.timeMillis in 0..30_000 &&
            fix.accuracyMeters <= 100 && (lastSentMillis == null || nowMillis - lastSentMillis!! >= intervalMillis)
    fun sent(nowMillis: Long) { lastSentMillis = nowMillis }
}
