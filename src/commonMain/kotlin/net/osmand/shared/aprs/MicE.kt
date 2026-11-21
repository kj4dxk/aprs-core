// SPDX-License-Identifier: AGPL-3.0-only
package net.osmand.shared.aprs

import kotlin.math.*

/** APRS 1.0.1 chapter 10. Destination is data, not a software identifier. */
object MicE {
    val statuses=listOf("Off Duty","En Route","In Service","Returning","Committed","Special","Priority","Emergency")
    fun decode(call:String,destination:String,body:String,now:Long):AprsStation?=runCatching {
        val dest=destination.substringBefore('-')
        require(dest.length==6 && body.length>=9 && body[0] in "`'\u001c\u001d")
        val digits=dest.mapIndexed {i,c->when(c) {
            in '0'..'9'->c; in 'A'..'J'->{require(i<3);'0'+(c-'A')}
            in 'P'..'Y'->'0'+(c-'P'); 'K'->{require(i<3);' '};'L','Z'->' '
            else->error("Invalid Mic-E latitude")
        }}.joinToString("")
        val ambiguity=digits.count {it==' '}
        require(ambiguity in 0..4 && digits.take(6-ambiguity).all {it.isDigit()} && digits.takeLast(ambiguity).all {it==' '})
        val centered=if(ambiguity==0) digits else digits.take(6-ambiguity)+(if(ambiguity==4) "3000" else "5"+"0".repeat(ambiguity-1))
        val minutes=centered.substring(2).toInt()/100.0
        require(minutes<60)
        val lat=(centered.take(2).toInt()+minutes/60)*(if(dest[3]>='P') 1 else -1)
        require(body[1].code in 38..127 && body[2].code in 38..97 && body.substring(3,7).all {it.code in 28..127})
        var degrees=body[1].code-28+(if(dest[4]>='P')100 else 0)
        if(degrees in 180..189)degrees-=80 else if(degrees in 190..199)degrees-=190
        var mins=body[2].code-28;if(mins>=60)mins-=60
        var hundredths=mins*100+body[3].code-28
        hundredths=when(ambiguity) {1->hundredths/10*10+5;2->hundredths/100*100+50;3->hundredths/1000*1000+500;4->3000;else->hundredths}
        require(degrees in 0..179 && hundredths in 0..5999)
        val lon=(degrees+hundredths/6000.0)*(if(dest[5]>='P')-1 else 1)
        var speed=(body[4].code-28)*10+(body[5].code-28)/10
        var course=((body[5].code-28)%10)*100+body[6].code-28
        if(speed>=800)speed-=800;if(course>=400)course-=400
        require(speed in 0..799 && course in 0..360 && body[7].code in 33..126 && body[8] in "/\\0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        AprsFix(lat,lon,now)
        val bits=dest.take(3).fold(0) {v,c->v*2+if(c in 'A'..'K' || c in 'P'..'Z')1 else 0}
        val custom=dest.take(3).any {it in 'A'..'K'}
        val status=if(custom && dest.take(3).any {it in 'P'..'Z'}) "Unknown" else if(custom) "Custom ${7-bits}" else statuses[7-bits]
        val text=body.drop(9)
        val altitude=Regex("^[>\\]]?([!-{]{3})}").find(text)?.groupValues?.get(1)?.fold(0) {v,c->v*91+c.code-33}?.minus(10000)?.toDouble()
        AprsStation(call,lat,lon,body[8],body[7],text,now,course.toDouble().takeIf {it!=0.0},speed.toDouble(),altitude,ambiguityDigits=ambiguity,micEStatus=status)
    }.getOrNull()

    fun encode(call:String,p:BeaconPosition,f:BeaconFormat,comment:String=""):String {
        require(AprsProtocol.validCallsign(call) && comment.length<=120 && comment.all {it.code in 32..126})
        val latitude=(abs(p.fix.latitude)*6000).roundToInt()
        val longitude=(abs(p.fix.longitude)*6000).roundToInt()
        val degrees=longitude/6000;require(degrees<180)
        val digits=(latitude/6000).toString().padStart(2,'0')+(latitude%6000).toString().padStart(4,'0')
        val tags=listOf("!OFF-DUTY!","!ENROUTE!","!INSERVICE!","!RETURNING!","!COMMITTED!","!SPECIAL!","!PRIORITY!","!EMERGENCY!")
        val bits=7-tags.indexOf(f.statusTag).coerceAtLeast(0)
        val offset=degrees<10 || degrees>=100
        val ambiguity=(-f.precision).coerceAtLeast(0)
        val dest=digits.mapIndexed {i,c->
            val high=when(i){0,1,2->bits and (4 shr i)!=0;3->p.fix.latitude>=0;4->offset;else->p.fix.longitude<0}
            if(i>=6-ambiguity) {if(high)'Z' else 'L'} else c+(if(high)32 else 0)
        }.joinToString("")
        val d=when(degrees){in 0..9->degrees+90;in 100..109->degrees-20;in 110..179->degrees-100;else->degrees}
        val m=longitude%6000/100;val h=longitude%100
        val speed=if(f.courseSpeed)(p.speedKnots?:0.0).roundToInt().coerceIn(0,799) else 0
        val course=if(f.courseSpeed)p.course?.roundToInt()?.let {if(it==0)360 else it}?:0 else 0
        val altitude=if(f.altitude && p.altitudeMeters!=null) {
            val meters=(p.altitudeMeters.roundToInt()+10000).coerceIn(0,753570)
            listOf(meters/8281,meters/91%91,meters%91).map {(it+33).toChar()}.joinToString("")+"}"
        }else ""
        val payload="`"+listOf(d,if(m<10)m+60 else m,h,speed/10+(if(speed<200)80 else 0),(speed%10)*10+course/100+4,course%100).map {(it+28).toChar()}.joinToString("")+f.symbol+f.table+altitude+comment
        return "$call>$dest,TCPIP*:$payload\r\n"
    }
}
