// SPDX-License-Identifier: AGPL-3.0-only
package org.aprsfoundation.core

import kotlin.test.*

/** Source-only regression cases, pending the next authorized test run. */
class RadioRoutingTest {
    private fun packet(line: String)=requireNotNull(RadioPacket.parse(line))
    @Test fun ax25AndFragmentedKissRoundTrip() {
        val p=packet("N0CALL-1>APRS,DIGI-2*,WIDE2-1:>test")
        assertEquals(p,Ax25Ui.decode(Ax25Ui.encode(p)))
        val frame=Kiss.encode(p,3);val decoder=KissDecoder(3)
        assertTrue(decoder.accept(frame.copyOfRange(0,8)).isEmpty())
        assertEquals(listOf(p),decoder.accept(frame.copyOfRange(8,frame.size)))
        assertTrue(KissDecoder(0).accept(frame).isEmpty())
        val bad=Ax25Ui.encode(p);bad[28]=0 // Reject connected-mode / non-UI control.
        assertNull(Ax25Ui.decode(bad))
    }
    @Test fun agwSplitHeaderAndPayload() {
        val p=packet("N0CALL>APRS:>test");val bytes=Agw.packet(2,p);val decoder=AgwDecoder()
        assertTrue(decoder.accept(bytes.copyOfRange(0,20)).isEmpty())
        val frame=decoder.accept(bytes.copyOfRange(20,bytes.size)).single()
        assertEquals('K',frame.kind);assertEquals(2,frame.port)
        assertEquals(p,Ax25Ui.decode(frame.data.drop(1).toByteArray()))
    }
    @Test fun textMonitorsAndTwoLineYaesu() {
        val decoder=TncTextDecoder()
        assertEquals("N0CALL>APRS:>test",decoder.accept("N0CALL>APRS <UI R>:>test")?.line())
        assertNull(decoder.accept("N0CALL>APRS [11/20/26 12:00:00] <UI>:"))
        assertEquals("N0CALL>APRS:>test",decoder.accept(">test")?.line())
    }
    @Test fun rfToInternetRejectsLoopsAndQueries() {
        val router=RadioRouter("N0GATE",RadioRules())
        assertEquals("N0CALL>APRS,WIDE1-1,qAR,N0GATE:>test",router.toInternet(packet("N0CALL>APRS,WIDE1-1:>test"),true))
        for(path in listOf("TCPIP*","TCPXX*","NOGATE","RFONLY","qAR,OTHER"))
            assertNull(router.toInternet(packet("N0CALL>APRS,$path:>test"),true))
        assertNull(router.toInternet(packet("N0CALL>APRS:?APRS?"),true))
        assertNull(router.toInternet(packet("OTHER>APRS:}N0CALL>APRS,TCPIP,OTHER*:>test"),true))
        assertEquals("N0CALL>APRS,qAO,N0GATE:>test",router.toInternet(packet("OTHER>APRS:}N0CALL>APRS:>test"),false))
    }
    @Test fun digiTransformsAndDupePathIndependence() {
        val router=RadioRouter("N0DIGI",RadioRules());val p=packet("N0CALL>APRS,WIDE2-2:>test")
        assertNull(router.digipeat(packet("N0CALL>APRS,N0DIGI:>test"),emptyList()))
        val result=router.digipeat(p,listOf("WIDE2-2=WIDE2-1"))!!
        assertEquals(listOf("N0DIGI*","WIDE2-1"),result.path)
        assertNull(router.digipeat(result,listOf("WIDE2-1=")))
        router.transmitted("digi",p,1000)
        assertTrue(router.duplicate("digi",result,2000))
        assertFalse(router.duplicate("digi",result,32000))
    }
    @Test fun igateNeedsLocalRecipientAndStripsInternetPath() {
        val router=RadioRouter("N0GATE",RadioRules());val message=packet("N0SRC>APRS,TCPIP*,qAC,SERVER::N0DEST   :hello{1")
        assertNull(router.toRadio("VHF",message,1000,listOf("WIDE1-1"),emptyList(),emptyList()))
        router.observeRf("VHF",packet("N0DEST>APRS:>here"),1000)
        val output=router.toRadio("VHF",message,2000,listOf("WIDE1-1"),emptyList(),emptyList())!!
        assertEquals("N0GATE>APRS,WIDE1-1:}N0SRC>APRS,TCPIP,N0GATE*::N0DEST   :hello{1",output.line())
        router.gatedToRadio("VHF",message,2000)
        assertNotNull(router.toRadio("VHF",packet("N0SRC>APRS,TCPIP*:!2832.20N/08114.58W-"),3000,emptyList(),emptyList(),emptyList()))
        router.observeInternet(packet("N0DEST>APRS,TCPIP*:>online"),3000)
        assertNull(router.toRadio("VHF",message,4000,emptyList(),emptyList(),emptyList()))
    }
}
