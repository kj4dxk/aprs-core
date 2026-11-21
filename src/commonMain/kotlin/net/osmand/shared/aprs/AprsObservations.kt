// SPDX-License-Identifier: AGPL-3.0-only
package net.osmand.shared.aprs

/** Standard APRS weather fields, converted to SI; missing fields remain null. */
data class AprsWeather(val call: String,val time: Long,val temperatureC: Double?,val windDegrees: Int?,
    val windKph: Double?,val gustKph: Double?,val humidity: Int?,val pressureHpa: Double?,val rainHourMm: Double?,val rainDayMm: Double?,val rainMidnightMm:Double?=null)
data class TelemetrySample(val call: String,val time: Long,val sequence: String,val analog: List<Double?>,val bits: String)
data class TelemetryDefinition(val call: String,val kind: String,val values: List<String>)
data class AprsBulletin(val sender: String,val destination: String,val text: String,val time: Long)

object AprsObservations {
    fun weather(line: String,time: Long): AprsWeather? {
        val p=RadioPacket.parse(line) ?: return null
        UltimeterWeather.decode(p.source,p.payload,time)?.let {return it}
        val station=AprsProtocol.parse(line,time)
        if(!p.payload.startsWith('_') && station?.symbol!='_') return null
        val text=if(p.payload.startsWith('_')) p.payload.drop(9) else station?.comment.orEmpty()
        fun n(tag: Char,digits: Int)=Regex("$tag(-?\\d{${digits-1},$digits})(?!\\d)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        val wind=Regex("^(\\d{3})/(\\d{3})").find(text)
        val direction=wind?.groupValues?.get(1)?.toIntOrNull() ?: n('c',3)?.toInt()
        val speed=wind?.groupValues?.get(2)?.toDoubleOrNull() ?: n('s',3)
        val f=n('t',3);val h=n('h',2)?.toInt()?.let {if(it==0) 100 else it}
        if(listOf(f,h,speed,n('b',5),n('r',3),n('p',3),n('g',3)).all {it==null}) return null
        return AprsWeather(p.source,time,f?.let {(it-32)*5/9},direction?.takeIf {it in 0..360},speed?.times(1.609344),
            n('g',3)?.times(1.609344),h?.takeIf {it in 1..100},n('b',5)?.div(10),n('r',3)?.times(0.254),n('p',3)?.times(0.254),n('P',3)?.times(0.254))
    }
    fun telemetry(line: String,time: Long): TelemetrySample? = runCatching {
        val p=RadioPacket.parse(line) ?: return null
        if(!p.payload.startsWith("T#")) {
            if(p.payload.firstOrNull() in listOf('`','\'')) {
                val suffix=p.payload.drop(9);val count=when(suffix.firstOrNull()){','->5;'`'->2;else->0}
                if(count>0 && suffix.length>=1+count*2 && suffix.substring(1,1+count*2).all {it in "0123456789abcdefABCDEF"}) {
                    val analog=suffix.substring(1,1+count*2).chunked(2).map {it.toInt(16).toDouble()}
                    return TelemetrySample(p.source,time,"MIC",(0..4).map {analog.getOrNull(it)},"00000000")
                }
            }
            val encoded=Regex("\\|([!-{]{4,14})\\|").find(p.payload)?.groupValues?.get(1) ?: return null
            require(encoded.length%2==0)
            val pairs=encoded.chunked(2).map {(it[0].code-33)*91+it[1].code-33}
            val analog=(1..5).map {pairs.getOrNull(it)?.toDouble()}
            val digital=pairs.getOrNull(6)?.also {require(it in 0..255)} ?: 0
            return TelemetrySample(p.source,time,pairs[0].toString(),analog,(0..7).joinToString("") {if(digital and (1 shl it)!=0) "1" else "0"})
        }
        val v=p.payload.drop(2).split(',');require(v.size>=7 && v[0].matches(Regex("(?:[0-9]{3}|MIC)")))
        val a=v.subList(1,6).map {s->if(s.isBlank()) null else s.toDouble().also {require(it.isFinite() && it in 0.0..255.0)}}
        val bits=v[6].take(8);require(bits.matches(Regex("[01]{8}")))
        TelemetrySample(p.source,time,v[0],a,bits)
    }.getOrNull()
    fun definition(line: String): TelemetryDefinition? = runCatching {
        val p=RadioPacket.parse(line) ?: return null;val body=p.payload
        require(body.length>=16 && body[0]==':' && body[10]==':' && body.substring(1,10).trim()==p.source)
        val text=body.substring(11).substringBefore('{');val kind=text.substringBefore('.')
        require(kind in listOf("PARM","UNIT","EQNS","BITS"))
        val values=text.substringAfter('.').split(',');require(values.size<=15)
        if(kind=="EQNS") require(values.size==15 && values.all {it.toDoubleOrNull()?.isFinite()==true})
        if(kind=="BITS") require(values.first().matches(Regex("[01]{8}")))
        TelemetryDefinition(p.source,kind,values)
    }.getOrNull()
    fun bulletin(line: String,time: Long): AprsBulletin? {
        val p=RadioPacket.parse(line) ?: return null;val body=p.payload
        if(body.length<12 || body[0]!=':' || body[10]!=':') return null
        val to=body.substring(1,10).trimEnd()
        if(!to.startsWith("BLN") && !to.startsWith("NWS-") && !to.startsWith("NWS_")) return null
        return AprsBulletin(p.source,to,body.substring(11).substringBefore('{'),time)
    }
    fun engineering(raw: Double?,channel: Int,definition: TelemetryDefinition?): Double? {
        if(raw==null) return null
        val v=definition?.takeIf {it.kind=="EQNS"}?.values ?: return raw
        if(channel !in 0..4 || v.size!=15) return raw
        return (v[channel*3].toDouble()*raw*raw+v[channel*3+1].toDouble()*raw+v[channel*3+2].toDouble()).takeIf {it.isFinite()}
    }
}

object AprsServices {
    fun group(action: String,name: String="",text: String=""): String {
        require(action in listOf("J","U","K","CQ","D","?","L"))
        if(action !in listOf("?","L")) require(name.matches(Regex("[A-Za-z0-9_-]{1,20}")))
        val result=listOf(action,name.trim().uppercase(),text.trim()).filter {it.isNotEmpty()}.joinToString(" ")
        require(AprsMessages.validText(result));return result
    }
    fun qru(group: String,radiusKm: Int): String {
        require(group.matches(Regex("[A-Za-z0-9_-]{1,20}")) && radiusKm in 1..500)
        return "${group.uppercase()} $radiusKm"
    }
    fun bulletin(sender: String,slot: String,group: String,text: String): String {
        require(slot.matches(Regex("[0-9A-Z]")) && group.matches(Regex("[A-Z0-9]{0,5}")) && AprsMessages.validText(text))
        require(AprsProtocol.validCallsign(sender))
        return "$sender>APRS,TCPIP*::${("BLN"+slot+group).padEnd(9)}:$text\r\n"
    }
    fun gpx(call: String,points: List<AprsFix>): String {
        fun xml(s: String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><gpx version=\"1.1\" creator=\"APRSIS64\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>${xml(call)}</name><trkseg>"+
            points.joinToString("") {"<trkpt lat=\"${it.latitude}\" lon=\"${it.longitude}\"><time>${kotlinx.datetime.Instant.fromEpochMilliseconds(it.timeMillis)}</time></trkpt>"}+"</trkseg></trk></gpx>"
    }
}
