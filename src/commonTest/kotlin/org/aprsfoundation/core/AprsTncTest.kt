// SPDX-License-Identifier: AGPL-3.0-only
package org.aprsfoundation.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AprsTncTest {
    @Test fun streamFramingAndRfMessages() {
        for (protocol in AprsTnc.Protocol.entries) {
            val codec = AprsTnc(protocol, if (protocol == AprsTnc.Protocol.TNC2) 0 else 3)
            val packet = AprsTnc.message("N0ABC-4", "N0XYZ", "radio check", "42", listOf("WIDE1-1"))
            val bytes = codec.encode(packet)
            val received = bytes.asList().chunked(7).flatMap { codec.receive(it.toByteArray()) }
            assertEquals(listOf(packet), received)
            assertEquals("42", AprsMessages.parse(received.single().line())?.id)
            assertFalse("TCPIP" in received.single().line())
            assertEquals("ack42", AprsTnc.acknowledge("N0ABC-4", "N0XYZ", "42", emptyList()).payload.substringAfterLast(':'))
        }
    }

    @Test fun filtersOtherRadioChannelsAndCommands() {
        val packet = RadioPacket("N0ABC", "APRS", emptyList(), "test")
        val kiss = AprsTnc(AprsTnc.Protocol.KISS, 3)
        assertTrue(kiss.receive(Kiss.encode(packet, 0) + Kiss.command(3, 1, byteArrayOf(20))).isEmpty())
        assertEquals(listOf(packet), kiss.receive(Kiss.encode(packet, 3)))
        val agw = AprsTnc(AprsTnc.Protocol.AGW, 3)
        assertEquals('k', AgwDecoder().accept(checkNotNull(agw.openCommand())).single().kind)
        assertTrue(agw.receive(Agw.packet(0, packet)).isEmpty())
        assertEquals(0x30.toByte(), AgwDecoder().accept(agw.encode(packet)).single().data.first())
        assertEquals(listOf(packet), agw.receive(Agw.encode(3, 'K', byteArrayOf(0) + Ax25Ui.encode(packet))))
        assertFailsWith<IllegalArgumentException> { AprsTnc(AprsTnc.Protocol.KISS, 16) }
    }

    @Test fun textDecoderRecoversWithoutChangingPayload() {
        val codec = AprsTnc(AprsTnc.Protocol.TNC2)
        val packet = "N0ABC>APRS:test [keep brackets]"
        val bytes = ("N0ABC>APRS:" + "x".repeat(550) + "\r\n" + packet + "\r\n").encodeToByteArray()
        assertEquals(packet, codec.receive(bytes).single().line())
    }

    @Test fun kissReceivesEscapedEightBitPayloads() {
        val bytes = Ax25Ui.encode(RadioPacket("N0ABC", "APRS", emptyList(), "ab"))
        bytes[bytes.lastIndex - 1] = 0xc0.toByte()
        bytes[bytes.lastIndex] = 0xdb.toByte()
        val frame = Kiss.command(0, 0, bytes)
        val codec = AprsTnc(AprsTnc.Protocol.KISS)
        assertTrue(codec.receive(frame.copyOfRange(0, frame.size - 2)).isEmpty())
        assertEquals("ÀÛ", codec.receive(frame.copyOfRange(frame.size - 2, frame.size)).single().payload)
    }
}
