// SPDX-License-Identifier: AGPL-3.0-only
package net.osmand.shared.aprs

data class GpsSatellite(val constellation:String,val id:String,val elevation:Double?,val azimuth:Double?,val snr:Double?,val used:Boolean,val time:Long)
data class GpsSky(val satellites:List<GpsSatellite> = emptyList(),val fixType:Int=1,val pdop:Double?=null,val hdop:Double?=null,val vdop:Double?=null,val time:Long=0)
class GpsDiagnostics {
 private data class Cycle(val total:Int,val started:Long,val parts:MutableMap<Int,List<GpsSatellite>> = mutableMapOf())
 private val cycles=mutableMapOf<String,Cycle>()
 private val visible=mutableMapOf<String,List<GpsSatellite>>()
 private val used=mutableMapOf<String,Pair<Long,Set<String>>>()
 var sky=GpsSky();private set
 fun accept(raw:String,now:Long):GpsSky?=runCatching {
  val line=raw.substringAfter('$',"").let {"$"+it}.trim();val end=line.indexOf('*')
  require(end>0 && end+3==line.length)
  require(line.substring(1,end).fold(0){a,c->a xor c.code}==line.substring(end+1).toInt(16))
  val f=line.substring(1,end).split(',');val system=f[0].take(2);val kind=f[0].takeLast(3)
  fun number(index:Int)=f.getOrNull(index)?.toDoubleOrNull()?.takeIf {it.isFinite()}
  when(kind) {
   "GSV"->{require(f.size>=4);val total=f[1].toInt();val part=f[2].toInt();require(total in 1..32 && part in 1..total)
    val signal=if((f.size-4)%4==1)f.last() else "";val key="$system:$signal"
    if(part==1 || cycles[key]?.total!=total || now-(cycles[key]?.started?:0)>10000)cycles[key]=Cycle(total,now)
    val cycle=cycles.getValue(key);val satellites=mutableListOf<GpsSatellite>();var i=4
    while(i+3<f.size){val id=f[i].toIntOrNull()?.toString();if(id!=null)satellites.add(GpsSatellite(system,id,number(i+1)?.takeIf {it in 0.0..90.0},number(i+2)?.takeIf {it in 0.0..360.0},number(i+3)?.takeIf {it in 0.0..99.0},false,now));i+=4}
    cycle.parts[part]=satellites
    if(cycle.parts.size==total){visible[key]=(1..total).flatMap {cycle.parts[it].orEmpty()};cycles.remove(key)}
   }
   "GSA"->{require(f.size>=18)
    val constellation=if(system=="GN")when(f.getOrNull(18)){"1"->"GP";"2"->"GL";"3"->"GA";"4"->"GB";"5"->"GQ";"6"->"GI";else->system}else if(system=="BD")"GB" else system
    used[constellation]=now to f.subList(3,15).mapNotNull {it.toIntOrNull()?.toString()}.toSet()
    sky=sky.copy(fixType=f[2].toInt().also {require(it in 1..3)},pdop=number(15),hdop=number(16),vdop=number(17))
   }
   else->return null
  }
  cycles.entries.removeAll {now-it.value.started>10000};used.entries.removeAll {now-it.value.first>30000}
  visible.entries.removeAll {it.value.all {s->now-s.time>30000}}
  val satellites=visible.values.flatten().filter {now-it.time<=30000}.map {if(it.constellation=="BD")it.copy(constellation="GB")else it}
   .groupBy {it.constellation to it.id}.values.map {signals->signals.maxBy {it.snr?:-1.0}}
  sky=sky.copy(satellites=satellites.take(256).map {it.copy(used=it.id in used[it.constellation]?.second.orEmpty() || it.id in used["GN"]?.second.orEmpty())},time=now);sky
 }.getOrNull()
}
