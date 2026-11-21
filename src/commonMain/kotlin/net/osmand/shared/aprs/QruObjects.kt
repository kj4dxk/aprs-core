// SPDX-License-Identifier: AGPL-3.0-only
package net.osmand.shared.aprs

data class QruQuery(val group:String,val radiusKm:Int)
data class QueryObject(val name:String,val group:String,val position:AprsFix)
object QruObjects {
 fun query(text:String,defaultRadius:Int,maximumRadius:Int):QruQuery?=runCatching {
  val parts=text.trim().uppercase().split(Regex("\\s+"));require(parts.size in 1..2)
  require(parts[0].matches(Regex("[A-Z0-9_-]{1,20}")))
  val radius=parts.getOrNull(1)?.toInt() ?: defaultRadius;require(radius in 1..maximumRadius)
  QruQuery(parts[0],radius)
 }.getOrNull()
 fun nearby(objects:List<QueryObject>,origin:AprsFix,query:QruQuery,maximum:Int):List<QueryObject> =
  objects.filter {query.group=="INFO" || it.group==query.group}.map {it to AdaptiveBeaconPolicy.distance(origin,it.position)}
   .filter {it.second<=query.radiusKm*1000.0}.sortedWith(compareBy({it.second},{it.first.name})).take(maximum).map {it.first}
 /** Item-in-message proposal, plain uncompressed point; no truncation of coordinates. */
 fun item(owner:String,name:String,position:BeaconPosition,format:BeaconFormat,comment:String):String {
  val f=format.copy(compressed=false,courseSpeed=false,altitude=false,includeReason=false,statusTag="",precision=0)
  val base=BeaconPackets.entity(owner,name,true,true,position,f,"").substringAfter(':').trimEnd('\r','\n')
  require(base.length<=67)
  return base+comment.filter {it.code in 32..126 && it !in "|~{"}.take(67-base.length)
 }
}
