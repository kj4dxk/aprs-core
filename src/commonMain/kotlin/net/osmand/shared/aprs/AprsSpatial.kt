// SPDX-License-Identifier: AGPL-3.0-only
package net.osmand.shared.aprs

import kotlin.math.*

data class RadioCoverage(val radiusMeters:Double,val bearing:Double?,val strength:Int?=null)
data class DirectionReport(val bearing:Double,val rangeMeters:Double,val accuracyDegrees:Double,val hits:Int)
data class AprsShape(val points:List<AprsFix>,val closed:Boolean,val color:String,val filled:Boolean=false,val dashed:Boolean=false)
data class StationDetails(val course:Double?,val speedKnots:Double?,val altitudeMeters:Double?,val coverage:RadioCoverage?,val direction:DirectionReport?)

/** Protocol geometry has no dependency on a map renderer, desktop API or device clock. */
object AprsSpatial {
    private val motion=Regex("^(\\d{3})/(\\d{3})")
    private val altitude=Regex("/A=(-\\d{5}|\\d{6})(?!\\d)")
    private val phg=Regex("^(PHG|DFS)([0-9])([0-9:;<=>?@A-Z])([0-9])([0-8])")
    private val df=Regex("^(?:\\d{3}/\\d{3}|\\.\\.\\./\\.\\.\\.|VVV/VVV)/(\\d{3})/([0-9])([0-9])([0-9])")
    fun details(s:AprsStation):StationDetails {
        // Weather's ddd/sss is wind, never the motion of the reporting station.
        val m=if(s.symbol=='_' || s.symbolTable=='\\' && s.symbol=='l') null else motion.find(s.comment)
        val course=s.courseDegrees ?: m?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf {it in 1.0..360.0}?.let {it%360}
        val speed=s.speedKnots ?: m?.groupValues?.get(2)?.toDoubleOrNull()
        val height=s.altitudeMeters ?: altitude.find(s.comment)?.groupValues?.get(1)?.toDoubleOrNull()?.times(0.3048)
        val p=phg.find(s.comment)
        val coverage=if(p!=null) {
            val strength=p.groupValues[2].toInt();val haat=10.0*2.0.pow(p.groupValues[3][0].code-'0'.code)
            val gain=10.0.pow(p.groupValues[4].toInt()/10.0);val direction=p.groupValues[5].toInt()
            RadioCoverage(sqrt(2*haat*sqrt((strength*strength/10.0)*(gain/2)))*1609.344,
                (direction*45.0).takeIf {direction!=0},strength.takeIf {p.groupValues[1]=="DFS"})
        } else Regex("^RNG([0-9]{4})").find(s.comment)?.let {RadioCoverage(it.groupValues[1].toDouble()*1609.344,null)}
        val direction=df.find(s.comment)?.let {
            val b=it.groupValues[1].toDouble();val n=it.groupValues[2].toInt();val q=it.groupValues[4].toInt()
            if(b>360 || n==0 || q==0) null else DirectionReport(b%360,2.0.pow(it.groupValues[3].toInt())*1609.344,
                listOf(360.0,240.0,120.0,64.0,32.0,16.0,8.0,4.0,2.0,1.0)[q],n)
        }
        return StationDetails(course,speed,height,coverage,direction)
    }
    fun fix(s:AprsStation)=AprsFix(s.latitude,s.longitude,s.heardAtMillis)
    fun project(f:AprsFix,bearing:Double,meters:Double)=AdaptiveBeaconPolicy.project(f,bearing,meters)
    fun circle(center:AprsFix,radius:Double,segments:Int=72):List<AprsFix> {
        require(radius.isFinite() && radius>=0 && segments in 12..360)
        return (0..segments).map {project(center,it*360.0/segments,radius)}
    }
    fun predicted(s:AprsStation,now:Long,maximumAgeSeconds:Int):AprsFix? {
        val d=details(s);val age=now-(s.positionTimeMillis ?: s.heardAtMillis)
        if(age !in 1..maximumAgeSeconds*1000L || d.course==null || d.speedKnots==null || d.speedKnots<=0) return null
        return project(fix(s),d.course,d.speedKnots*0.514444444*age/1000).copy(timeMillis=now)
    }
    /** Standard 4/3-Earth radio horizon; user supplies terrain elevation separately. */
    fun horizonMeters(altitudeMsl:Double,groundMsl:Double,receiverHeight:Double):Double =
        4120*(sqrt((altitudeMsl-groundMsl).coerceAtLeast(0.0))+sqrt(receiverHeight.coerceAtLeast(0.0)))

    /** AE5PL multiline: latitude positive north, longitude offsets positive WEST. */
    fun multiline(s:AprsStation):AprsShape? = runCatching {
        val match=Regex("(?:^| )}([a-l])([01])([!-z])([!-z]{4,46})\\{([A-Za-z0-9]{5})(?:$| )").find(s.comment) ?: return null
        val pairs=match.groupValues[4];require(pairs.length%2==0)
        val step=0.0001*10.0.pow((match.groupValues[3][0].code-33)/20.0)
        val points=pairs.chunked(2).map {AprsFix(s.latitude+(it[0].code-78)*step,s.longitude-(it[1].code-78)*step,s.heardAtMillis)}
        val closed=match.groupValues[2]=="0";require(!closed || points.size>=3)
        val style=match.groupValues[1][0]-'a'
        AprsShape(if(closed && points.first()!=points.last()) points+points.first() else points,closed,
            listOf("#e53935","#e8b200","#237ee6","#249c49")[style/3],closed,style%3!=0)
    }.getOrNull()
    fun encodeMultiline(origin:AprsFix,points:List<AprsFix>,closed:Boolean,style:Char='j',sequence:String="00000"):String {
        require(points.size in (if(closed) 3 else 2)..23 && style in 'a'..'l' && sequence.matches(Regex("[A-Za-z0-9]{5}")))
        val scale=(33..122).firstOrNull {code->
            val step=0.0001*10.0.pow((code-33)/20.0)
            points.all {((it.latitude-origin.latitude)/step).roundToInt() in -45..44 && ((origin.longitude-it.longitude)/step).roundToInt() in -45..44}
        } ?: error("Shape is too large")
        val step=0.0001*10.0.pow((scale-33)/20.0)
        return " }$style${if(closed) '0' else '1'}${scale.toChar()}"+points.joinToString("") {
            "${(78+((it.latitude-origin.latitude)/step).roundToInt()).toChar()}${(78+((origin.longitude-it.longitude)/step).roundToInt()).toChar()}"
        }+"{$sequence"
    }
    fun area(s:AprsStation):AprsShape?=runCatching {
        if(s.symbolTable!='\\' || s.symbol!='l') return null
        val m=Regex("^([0-9])([0-9]{2})/([0-9A-F])([0-9]{2})").find(s.comment) ?: return null
        val type=m.groupValues[1].toInt();val dy=m.groupValues[2].toDouble().pow(2)/10000
        val dx=m.groupValues[4].toDouble().pow(2)/10000;val color=m.groupValues[3].toInt(16)
        fun point(y:Double,x:Double)=AprsFix(y,x,s.heardAtMillis)
        val a=fix(s);val b=point(s.latitude-dy,s.longitude+if(type==6) -dx else dx)
        val points=when(type) {
            1,6->listOf(a,b)
            3,8->listOf(point(s.latitude,s.longitude+dx/2),b,point(b.latitude,s.longitude),point(s.latitude,s.longitude+dx/2))
            4,9->listOf(a,point(a.latitude,b.longitude),b,point(b.latitude,a.longitude),a)
            else->(0..72).map {val angle=it*2*PI/72;point(s.latitude-dy/2+dy/2*cos(angle),s.longitude+dx/2+dx/2*sin(angle))}
        }
        AprsShape(points,type !in listOf(1,6),listOf("#222222","#237ee6","#249c49","#00aaaa","#e53935","#ad45cf","#e8b200","#888888")[color%8],type in listOf(5,7,8,9))
    }.getOrNull()
    /** Actual used RF hops only. Do not turn unrepeated aliases into map evidence. */
    fun pathNodes(packet:RadioPacket):List<String?> {
        val result=mutableListOf<String?>(packet.source)
        packet.path.take(packet.usedHops).forEach {value->
            val call=value.trimEnd('*');result.add(call.takeUnless {it.startsWith("WIDE") || it.startsWith("TRACE") || it in listOf("RELAY","TCPIP","TCPXX") || it.startsWith("q")})
        }
        val q=packet.path.indexOfFirst {it.startsWith("q")}
        if(q>=0 && q+1<packet.path.size) result.add(packet.path[q+1].trimEnd('*'))
        return result
    }
    fun directlyHeard(packet:RadioPacket)=packet.usedHops==0 && !packet.payload.startsWith('}') &&
        packet.path.none {it.startsWith("q") || it.trimEnd('*') in listOf("TCPIP","TCPXX")}
}
