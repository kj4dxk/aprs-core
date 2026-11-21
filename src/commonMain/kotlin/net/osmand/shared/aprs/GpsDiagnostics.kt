// SPDX-License-Identifier: AGPL-3.0-only
package net.osmand.shared.aprs

data class GpsSatellite(val constellation:String,val id:String,val elevation:Double?,val azimuth:Double?,val snr:Double?,val used:Boolean,val time:Long)
data class GpsSky(val satellites:List<GpsSatellite> = emptyList(),val fixType:Int=1,val pdop:Double?=null,val hdop:Double?=null,val vdop:Double?=null,val time:Long=0)
class GpsDiagnostics {
 private val visible=linkedMapOf<String,GpsSatellite>();private val used=mutableMapOf<String,Set<String>>()
 var sky=GpsSky();private set
 fun accept(raw:String,now:Long):GpsSky?=runCatching {
  // Some Kenwood local echo lines prefix the NMEA sentence. Never feed RF packet text here.
  val line=raw.substringAfter('$',"").let {"$"+it}.trim()
  val end=line.indexOf('*');require(end>0 && end+3==line.length)
  require(line.substring(1,end).fold(0){a,c->a xor c.code}==line.substring(end+1).toInt(16))
  val f=line.substring(1,end).split(',');val system=f[0].take(2);val kind=f[0].takeLast(3)
  fun number(index:Int)=f.getOrNull(index)?.toDoubleOrNull()?.takeIf {it.isFinite()}
  when(kind) {
   "GSV"->{require(f.size>=4);if(f[2]=="1")visible.keys.filter {it.startsWith("$system:")}.forEach {visible.remove(it)}
    var i=4;while(i+3<f.size){val id=f[i];if(id.isNotBlank()) visible["$system:$id"]=GpsSatellite(system,id,number(i+1)?.takeIf {it in 0.0..90.0},number(i+2)?.takeIf {it in 0.0..360.0},number(i+3),false,now);i+=4}}
   "GSA"->{require(f.size>=18);used[system]=f.subList(3,15).filter {it.isNotEmpty()}.toSet();sky=sky.copy(fixType=f[2].toInt().coerceIn(1,3),pdop=number(15),hdop=number(16),vdop=number(17))}
   else->return null
  }
  visible.entries.removeAll {now-it.value.time>30000}
  sky=sky.copy(satellites=visible.values.take(256).map {it.copy(used=it.id in used[it.constellation].orEmpty() || it.id in used["GN"].orEmpty())},time=now);sky
 }.getOrNull()
}
