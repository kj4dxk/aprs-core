// SPDX-License-Identifier: AGPL-3.0-only
package org.aprsfoundation.core

/** Local APRS subscription filters. Includes are ORed; exclusions always win.
 * The caller still applies RF loop prevention, eligibility and rate limits. */
class PacketFilter(expression:String) {
 private val terms=run {
  val strict=Regex("(?:^|\\s)([-+]?os/)").find(expression)
  val head=if(strict==null)expression else expression.substring(0,strict.range.first)
  head.trim().split(Regex("\\s+")).filter {it.isNotEmpty()}+
   if(strict==null)emptyList() else listOf(expression.substring(strict.range.first).trimStart())
 }
 init {
  require(expression.length<=2048 && terms.size<=64)
  terms.forEach {term->
   val p=term.removePrefix("-").removePrefix("+").split('/');require(p[0] in setOf("r","a","b","p","o","os","d","D","e","g","u","t","s","q","m","f")) {"Unknown filter: ${p[0]}"}
   require(p.size>=2)
   when(p[0]) {
    "r"->{require(p.size==4);AprsFix(p[1].toDouble(),p[2].toDouble(),0);require(p[3].toDouble() in 0.0..40075.0)}
    "a"->{require(p.size==5);AprsFix(p[1].toDouble(),p[2].toDouble(),0);AprsFix(p[3].toDouble(),p[4].toDouble(),0);require(p[1].toDouble()>=p[3].toDouble())}
    "m"->require(p.size==2 && p[1].toDouble() in 0.0..40075.0)
    "f"->require(p.size==3 && p[2].toDouble() in 0.0..40075.0)
    "t"->{require(p.size==2 || p.size==4);require(p[1].all {it in "poimqstunw"});if(p.size==4)require(p[3].toDouble() in 0.0..40075.0)}
    "s"->require(p.size in 2..4)
    "q"->require(p.size in 2..3 && (p.size==2 || p[2]=="I"))
    else->require(p.drop(1).all {it.isNotEmpty() && it.length<=32})
   }
  }
 }
 fun matches(packet:RadioPacket,own:AprsFix?,positions:Map<String,AprsFix>,igates:Set<String> = emptySet()):Boolean {
  if(terms.isEmpty())return false
  val station=AprsProtocol.parse(packet.line(),0)
  val recipient=packet.payload.takeIf {it.length>=11 && it[0]==':' && it[10]==':'}?.substring(1,10)?.trimEnd()
  val position=station?.let {AprsSpatial.fix(it)} ?: positions[packet.source]
  fun wildcard(pattern:String,value:String):Boolean {
   var p=0;var v=0;var star=-1;var retry=0
   while(v<value.length) {
    if(p<pattern.length && pattern[p]==value[v]){p++;v++}
    else if(p<pattern.length && pattern[p]=='*'){star=p++;retry=v}
    else if(star>=0){p=star+1;v=++retry}
    else return false
   }
   while(p<pattern.length && pattern[p]=='*')p++
   return p==pattern.length
  }
  fun match(term:String):Boolean {
   val p=term.removePrefix("-").removePrefix("+").split('/');val args=p.drop(1)
   fun any(value:String)=args.any {wildcard(it,value)}
   fun near(center:AprsFix?,km:Double):Boolean {
    val target=if(recipient!=null)positions[recipient] ?: position else position
    return center!=null && target!=null && AdaptiveBeaconPolicy.distance(center,target)<=km*1000
   }
   return when(p[0]) {
    "b"->any(packet.source);"p"->args.any {packet.source.startsWith(it)};"u"->any(packet.destination)
    "o","os"->packet.payload.firstOrNull() in listOf(';',')') && station!=null && args.any {wildcard(it.replace('|','/').replace('~','*'),station.callsign)}
    "D"->packet.path.any {any(it.trimEnd('*'))}
    "d"->packet.path.take(packet.usedHops).any {any(it.trimEnd('*'))}
    "e"->packet.path.indexOfFirst {it.startsWith('q')}.let {it>=0 && any(packet.path.getOrNull(it+1).orEmpty())}
    "g"->recipient!=null && any(recipient)
    "r"->near(AprsFix(p[1].toDouble(),p[2].toDouble(),0),p[3].toDouble())
    "m"->near(own,p[1].toDouble());"f"->near(positions[p[1]],p[2].toDouble())
    "a"->{val v=recipient?.let {positions[it]} ?: position;v!=null && v.latitude in p[3].toDouble()..p[1].toDouble() && if(p[2].toDouble()<=p[4].toDouble()) v.longitude in p[2].toDouble()..p[4].toDouble() else v.longitude>=p[2].toDouble() || v.longitude<=p[4].toDouble()}
    "q"->packet.path.any {it.length==3 && it.startsWith("qA") && it[2] in p[1]} || (p.getOrNull(2)=="I" && station!=null && packet.source in igates)
    "s"->station!=null && if(station.symbolTable=='/')station.symbol in p[1].replace('|','/') else station.symbol in p.getOrElse(2){""}.replace('|','/') && (p.getOrNull(3).isNullOrEmpty() || station.symbolTable in p[3])
    "t"->{val type=when(packet.payload.firstOrNull()) {';',')'->if(packet.payload[0]==';')'o' else 'i';':'->'m';'?'->'q';'>'->'s';'T'->'t';'{'->'u';'_'->'w';else->if(station!=null)'p' else ' '}
     (type in p[1] || 'w' in p[1] && (station?.symbol=='_' || packet.payload.startsWith('_')) || 't' in p[1] && AprsObservations.telemetry(packet.line(),0)!=null || 'n' in p[1] && (recipient?.startsWith("NWS")==true || packet.source.startsWith("NWS") || type=='o' && station?.symbolTable=='\\' && station.symbol in "nh")) && (p.size==2 || near(positions[p[2]],p[3].toDouble()))}
    else->false
   }
  }
  return terms.none {it.startsWith('-') && match(it)} && terms.filter {it.startsWith('+')}.all {match(it)} && terms.any {!it.startsWith('-') && !it.startsWith('+') && match(it)}
 }
}
