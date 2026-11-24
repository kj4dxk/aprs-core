// SPDX-License-Identifier: AGPL-3.0-only
package org.aprsfoundation.core

/** Stream framing for a single RF port, independent of the host transport. */
class AprsTnc(val protocol: Protocol, channel: Int = 0) {
    enum class Protocol { KISS, TNC2, AGW }

    private val channel = channel.also {
        require(it in when (protocol) {
            Protocol.KISS -> 0..15
            Protocol.TNC2 -> 0..0
            Protocol.AGW -> 0..255
        })
    }
    private val kiss = KissDecoder(channel.takeIf { protocol == Protocol.KISS } ?: 0)
    var diagnostic: ((String) -> Unit)? = null
    init {
        kiss.hardwareResponse = { data ->
            val value = data.getOrNull(1)?.toInt()?.and(255)
            val low = data.getOrNull(2)?.toInt()?.and(255)
            if (value != null) when (data.first().toInt() and 255) {
                0x0c -> if (low != null) diagnostic?.invoke("TX audio gain: " + ((value shl 8) or low))
                0x50 -> diagnostic?.invoke("PTT style: " + value)
            }
        }
    }
    fun mobilinkdSetupQueries(): List<ByteArray> {
        require(protocol == Protocol.KISS)
        return listOf(0x0c, 0x50).map { Kiss.command(channel, 6, byteArrayOf(it.toByte())) }
    }
    private val agw = AgwDecoder()
    private val line = StringBuilder()
    private var overflow = false

    /** AGW clients must toggle raw frame delivery before receiving. */
    fun openCommand(): ByteArray? = if (protocol == Protocol.AGW) Agw.encode(0, 'k') else null

    fun receive(bytes: ByteArray): List<RadioPacket> = when (protocol) {
        Protocol.KISS -> kiss.accept(bytes)
        Protocol.AGW -> agw.accept(bytes).mapNotNull {
            if (it.port == channel && it.kind == 'K' && it.data.isNotEmpty())
                Ax25Ui.decode(it.data.copyOfRange(1, it.data.size))
            else null
        }
        Protocol.TNC2 -> buildList {
            for (byte in bytes) {
                val char = (byte.toInt() and 255).toChar()
                if (char == '\r' || char == '\n') {
                    if (line.isNotEmpty() && !overflow) {
                        RadioPacket.parse(line.toString(), receiveOnly = true)?.let(::add)
                    }
                    line.clear()
                    overflow = false
                } else if (line.length < 510) {
                    line.append(char)
                } else {
                    overflow = true
                }
            }
        }
    }

    fun encode(packet: RadioPacket): ByteArray = when (protocol) {
        Protocol.KISS -> Kiss.encode(packet, channel)
        Protocol.AGW -> Agw.packet(channel, packet)
        Protocol.TNC2 -> {
            Ax25Ui.encode(packet) // Apply the same RF address and payload validation as binary transports.
            (packet.line() + "\r\n").encodeToByteArray()
        }
    }

    companion object {
        fun message(account: String, recipient: String, body: String, id: String, path: List<String>): RadioPacket =
            wireMessage(account, AprsMessages.packet(account, recipient, body, id), path)

        fun acknowledge(account: String, recipient: String, id: String, path: List<String>): RadioPacket =
            wireMessage(account, AprsMessages.ack(account, recipient, id), path)

        private fun wireMessage(account: String, line: String, path: List<String>) =
            RadioPacket(account, "APRS", path, line.substringAfter(':').removeSuffix("\r\n"))
    }
}

/** Well-known BLE UART services capable of carrying KISS as an ordered byte stream. */
enum class BleKissProfile(val service: String, val write: String, val notify: String) {
    STANDARD(
        "00000001-ba2a-46c9-ae49-01b0961f68bb",
        "00000002-ba2a-46c9-ae49-01b0961f68bb",
        "00000003-ba2a-46c9-ae49-01b0961f68bb"
    ),
    NORDIC_UART(
        "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
        "6e400002-b5a3-f393-e0a9-e50e24dcca9e",
        "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    )
}
