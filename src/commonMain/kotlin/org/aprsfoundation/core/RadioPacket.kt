// SPDX-License-Identifier: AGPL-3.0-only
package org.aprsfoundation.core

/** TNC2 envelope; RF addresses have numeric AX.25 SSIDs, unlike Internet identities. */
data class RadioPacket(val source: String, val destination: String, val path: List<String>, val payload: String) {
    fun line() = "$source>$destination${if(path.isEmpty()) "" else ","+path.joinToString(",")}:$payload"
    /** Unwrap only for application decoding; routing retains the original envelope. */
    fun content(maxDepth:Int=3, receiveOnly:Boolean=false):RadioPacket? {
        var value=this
        repeat(maxDepth) {
            if(!value.payload.startsWith('}'))return value
            value=parse(value.payload.drop(1),receiveOnly)?:return null
        }
        return value.takeUnless {it.payload.startsWith('}')}
    }
    val key get() = "$source>$destination:$payload" // Deliberately ignore the changing digipeater path.
    val usedHops get() = path.indexOfLast {it.endsWith('*')}+1
    companion object {
        fun address(call: String): Boolean = Regex("[A-Z0-9]{1,6}(-(?:[0-9]|1[0-5]))?").matches(call)
        fun parse(line: String, receiveOnly:Boolean=false): RadioPacket? = runCatching {
            require(line.length in 5..510 && line.none {it=='\r' || it=='\n' || (!receiveOnly && it.code>127) || it.code==0})
            val colon=line.indexOf(':'); val arrow=line.indexOf('>')
            require(arrow>0 && colon>arrow)
            val parts=line.substring(arrow+1,colon).split(',').mapIndexed {index,value->if(receiveOnly && index==0)value.uppercase() else value}
            val source=line.substring(0,arrow).let {if(receiveOnly)it.uppercase() else it}
            fun internetAddress(value:String)=value.length in 1..9 && Regex("[A-Z0-9]+(-[A-Z0-9]{1,2})?").matches(value)
            require(internetAddress(source) && internetAddress(parts.first()))
            require(parts.size<=18 && parts.drop(1).all {it.isNotEmpty() && it.length<=10 && it.all {c->c.isLetterOrDigit() || c in "-*"}})
            RadioPacket(source,parts.first(),parts.drop(1),line.substring(colon+1)).also {require(it.payload.isNotEmpty())}
        }.getOrNull()
    }
}

/** AX.25 UI frames without HDLC flags or FCS. Non-UI/non-APRS frames are rejected. */
object Ax25Ui {
    fun encode(packet: RadioPacket): ByteArray {
        require(packet.path.size<=8 && packet.payload.length<=256)
        val addresses=listOf(packet.destination,packet.source)+packet.path
        val bytes=mutableListOf<Byte>()
        addresses.forEachIndexed {index, raw ->
            val call=raw.trimEnd('*'); require(RadioPacket.address(call))
            call.substringBefore('-').padEnd(6).forEach {bytes.add((it.code shl 1).toByte())}
            val ssid=call.substringAfter('-',"0").toInt()
            val repeated=index>=2 && index-2<packet.usedHops
            bytes.add((0x60 or (ssid shl 1) or (if(index==addresses.lastIndex) 1 else 0) or
                (if(index==0 || repeated) 0x80 else 0)).toByte())
        }
        bytes.add(3);bytes.add(0xf0.toByte());packet.payload.forEach {require(it.code in 1..127 && it !in "\r\n");bytes.add(it.code.toByte())}
        return bytes.toByteArray()
    }
    fun decode(bytes: ByteArray): RadioPacket? = runCatching {
        require(bytes.size in 17..330)
        val addresses=mutableListOf<String>();var offset=0;var last=false
        while(!last) {
            require(addresses.size<10 && offset+7<=bytes.size)
            val call=(0..5).map { val b=bytes[offset+it].toInt() and 255;require(b and 1==0);(b shr 1).toChar() }.joinToString("").trimEnd()
            val flags=bytes[offset+6].toInt() and 255;val ssid=(flags shr 1) and 15
            val name=call+(if(ssid==0) "" else "-$ssid"); require(RadioPacket.address(name))
            addresses.add(name+if(addresses.size>=2 && flags and 128!=0) "*" else "")
            last=flags and 1!=0;offset+=7
        }
        require(addresses.size>=2 && offset+2<bytes.size && bytes[offset].toInt()==3 && bytes[offset+1].toInt() and 255==240)
        val payload=bytes.drop(offset+2).map {(it.toInt() and 255).toChar()}.joinToString("")
        require(payload.length<=256)
        RadioPacket.parse(RadioPacket(addresses[1],addresses[0],addresses.drop(2),payload).line(), receiveOnly = true) ?: error("Invalid payload")
    }.getOrNull()
}

/** Stateful stream decoder tolerates split frames and discards oversized or malformed frames. */
class KissDecoder(private val channel: Int = 0) {
    var hardwareResponse: ((ByteArray) -> Unit)? = null
    private val buffer=mutableListOf<Byte>();private var escaped=false;private var discard=true
    init {require(channel in 0..15)}
    fun accept(bytes: ByteArray): List<RadioPacket> = buildList {
        bytes.forEach {byte -> val value=byte.toInt() and 255
            if(value==192) {
                if(!discard && !escaped && buffer.isNotEmpty() && (buffer[0].toInt() and 255)==(channel shl 4))
                    Ax25Ui.decode(buffer.drop(1).toByteArray())?.let {add(it)}
                if (!discard && !escaped && buffer.firstOrNull()?.toInt()?.and(255) == ((channel shl 4) or 6))
                    hardwareResponse?.invoke(buffer.drop(1).toByteArray())
                buffer.clear();escaped=false;discard=false
            } else if(!discard) {
                if(escaped) {when(value) {220->buffer.add(192.toByte());221->buffer.add(219.toByte());else->discard=true};escaped=false}
                else if(value==219) escaped=true else buffer.add(byte)
                if(buffer.size>331) discard=true
            }
        }
    }
}

object Kiss {
    fun command(channel: Int, command: Int, data: ByteArray): ByteArray {
        require(channel in 0..15 && command in 0..15)
        val result=mutableListOf(192.toByte())
        (byteArrayOf(((channel shl 4) or command).toByte())+data).forEach {
            when(it.toInt() and 255) {192->result.addAll(listOf(219.toByte(),220.toByte()));219->result.addAll(listOf(219.toByte(),221.toByte()));else->result.add(it)}
        }
        result.add(192.toByte());return result.toByteArray()
    }
    fun encode(packet: RadioPacket, channel: Int)=command(channel,0,Ax25Ui.encode(packet))
}

data class AgwFrame(val port: Int, val kind: Char, val data: ByteArray)
object Agw {
    fun encode(port: Int, kind: Char, data: ByteArray=byteArrayOf()): ByteArray {
        require(port in 0..255 && data.size<=2048)
        val header=ByteArray(36);header[0]=port.toByte();header[4]=kind.code.toByte()
        for(i in 0..3) header[28+i]=(data.size ushr (i*8)).toByte()
        return header+data
    }
    fun packet(port: Int, packet: RadioPacket)=encode(port,'K',byteArrayOf((port shl 4).toByte())+Ax25Ui.encode(packet))
}
class AgwDecoder {
    private var pending=byteArrayOf()
    fun accept(bytes: ByteArray): List<AgwFrame> = buildList {
        pending+=bytes;require(pending.size<=8192)
        while(pending.size>=36) {
            var size=0L;for(i in 0..3) size=size or ((pending[28+i].toLong() and 255) shl (8*i))
            require(size in 0..2048);val end=36+size.toInt();if(pending.size<end) break
            add(AgwFrame(pending[0].toInt() and 255,(pending[4].toInt() and 255).toChar(),pending.copyOfRange(36,end)))
            pending=pending.copyOfRange(end,pending.size)
        }
    }
}

/** Single/two-line TNC monitor formats, including Kenwood UI and Yaesu timestamp headers. */
class TncTextDecoder {
    private var header: String?=null
    fun accept(raw: String): RadioPacket? {
        val line=raw.removePrefix("cmd:").replace(Regex(" \\[.*?]"),"").replace(Regex("\\s+<UI[^>]*>"),"")
        val previous=header;header=null
        if(previous!=null) RadioPacket.parse(previous+line)?.let {return it}
        if(line.endsWith(':') && '>' in line) {header=line;return null}
        return RadioPacket.parse(line)
    }
}
