// SPDX-License-Identifier: AGPL-3.0-only
package org.aprsfoundation.core

data class RadioRules(val heardSeconds: Int=1800, val maximumHops: Int=2, val duplicateSeconds: Int=30,
                      val internetSeconds: Int=1800, val maximumEntries: Int=4096) {
    init {require(heardSeconds in 60..86400 && maximumHops in 0..8 && duplicateSeconds in 1..3600 &&
        internetSeconds in 60..86400 && maximumEntries in 100..100000)}
}

/** Per-radio heard tables and global loop checks; caller serializes operations. */
class RadioRouter(private val ownCall: String, private val rules: RadioRules) {
    private data class Heard(val at: Long, val hops: Int)
    private val heard=linkedMapOf<Pair<String,String>,Heard>()
    private val internet=linkedMapOf<String,Long>()
    private val associated=linkedMapOf<Pair<String,String>,Long>()
    private val sent=linkedMapOf<String,Long>()
    private fun blocked(packet: RadioPacket, internetToo: Boolean)=packet.path.any {
        it.trimEnd('*') in (if(internetToo) setOf("TCPIP","TCPXX","NOGATE","RFONLY") else setOf("TCPXX","NOGATE","RFONLY")) || it.startsWith("q")
    }
    private fun <K,V> trim(map: MutableMap<K,V>) {while(map.size>rules.maximumEntries) map.remove(map.keys.first())}
    private fun onInternet(packet: RadioPacket)=packet.path.any {it.trimEnd('*') in setOf("TCPIP","TCPXX")}
    fun observeRf(port: String, packet: RadioPacket, now: Long) {
        if(packet.payload.startsWith('}')) {
            RadioPacket.parse(packet.payload.drop(1))?.takeIf {onInternet(it)}?.let {internet[packet.source]=now;internet[it.source]=now}
            trim(internet);return
        }
        if(onInternet(packet)) return
        val key=port to packet.source;val old=heard[key]
        // Retain a recent direct hearing when a duplicate arrives through distant digipeaters.
        if(old==null || now-old.at>=rules.heardSeconds*1000L || packet.usedHops<=rules.maximumHops)
            heard[key]=Heard(now,packet.usedHops)
        trim(heard)
    }
    fun observeInternet(packet: RadioPacket, now: Long) {
        if(onInternet(packet)) {internet[packet.source]=now;trim(internet)}
    }
    private fun recentInternet(call: String, now: Long)=internet[call]?.let {now-it in 0..rules.internetSeconds*1000L} ?: false
    private fun local(port: String, call: String, now: Long)=heard[port to call]?.let {
        it.hops<=rules.maximumHops && now-it.at in 0..rules.heardSeconds*1000L
    } ?: false
    private fun heardAnywhere(call: String, now: Long)=heard.any {(key,value)->key.second==call && now-value.at in 0..rules.heardSeconds*1000L}
    fun messageReachable(port: String, call: String, now: Long)=local(port,call,now) && !recentInternet(call,now)
    fun duplicate(action: String, packet: RadioPacket, now: Long)=sent["$action:${packet.key}"]?.let {now-it in 0..rules.duplicateSeconds*1000L} ?: false
    fun transmitted(action: String, packet: RadioPacket, now: Long) {sent["$action:${packet.key}"]=now;trim(sent)}
    fun toInternet(original: RadioPacket, canReply: Boolean): String? {
        var packet=original
        repeat(3) {
            if(blocked(packet,true) || packet.payload.startsWith('?')) return null
            if(!packet.payload.startsWith('}')) return packet.copy(path=packet.path+listOf(if(canReply) "qAR" else "qAO",ownCall)).line().takeIf {it.length<=510}
            packet=RadioPacket.parse(packet.payload.drop(1)) ?: return null
        }
        return null
    }
    fun digipeat(packet: RadioPacket, transforms: List<String>): RadioPacket? {
        if(transforms.isEmpty()) return null
        if(packet.source==ownCall || packet.payload.startsWith('}') || blocked(packet,true) || packet.path.any {it.trimEnd('*')==ownCall}) return null
        val index=packet.usedHops;val alias=packet.path.getOrNull(index) ?: return null
        val transform=if(alias==ownCall) "" else transforms.firstOrNull {it.substringBefore('=')==alias}?.substringAfter('=') ?: return null
        val flags=transform.takeWhile {it in "!@"};val replacement=transform.drop(flags.length)
        val path=(if('@' in flags) emptyList() else packet.path.take(index))+
            (if('!' in flags) emptyList() else listOf("$ownCall*"))+
            (if(replacement.isBlank()) emptyList() else replacement.split(','))+packet.path.drop(index+1)
        if(path.size>8 || path.any {!RadioPacket.address(it.trimEnd('*'))}) return null
        return packet.copy(path=path)
    }
    fun toRadio(port: String, packet: RadioPacket, now: Long, path: List<String>, passCalls: List<String>, passObjects: List<String>, filterAllowed:Boolean=false): RadioPacket? {
        if(packet.source==ownCall || packet.payload.startsWith('}') || packet.payload.startsWith('?') ||
            packet.path.any {it.trimEnd('*') in setOf("TCPXX","NOGATE","RFONLY","qAX")}) return null
        if(heardAnywhere(packet.source,now)) return null
        val message=AprsMessages.parse(packet.line())
        val objectName=when(packet.payload.firstOrNull()) {
            ';'->packet.payload.drop(1).take(9).trimEnd()
            ')'->packet.payload.drop(1).takeWhile {it !in "!_"}
            else->""
        }
        val explicitlyAllowed=filterAllowed || packet.source in passCalls || objectName.isNotEmpty() && objectName in passObjects
        val position=packet.payload.firstOrNull() in listOf('!','=','/','@','`','\'')
        val associatedPosition=position && associated[port to packet.source]?.let {now-it in 0..rules.heardSeconds*1000L}==true
        val messageAllowed=message!=null && local(port,message.recipient,now) && !recentInternet(message.recipient,now)
        if(!explicitlyAllowed && !messageAllowed && !associatedPosition) return null
        val inner=packet.copy(path=listOf("TCPIP","$ownCall*"))
        return RadioPacket(ownCall,"APRS",path,"}"+inner.line()).takeIf {it.payload.length<=256}
    }
    fun gatedToRadio(port: String, packet: RadioPacket, now: Long) {
        if(AprsMessages.parse(packet.line())!=null) associated[port to packet.source]=now
        else associated.remove(port to packet.source)
        trim(associated)
    }
}
