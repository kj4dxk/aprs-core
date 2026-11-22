// SPDX-License-Identifier: AGPL-3.0-only
package org.aprsfoundation.core

import kotlin.test.*

/** Prepared for the next authorized test run; no live APRS traffic. */
class BeaconEngineTest {
    private val point=BeaconPosition(AprsFix(28.536725,-81.242932,0))
    @Test fun fixedInitialPeriodicAndManual() {
        val policy=AdaptiveBeaconPolicy(BeaconTiming())
        assertEquals("Initial",policy.evaluate(point,0,false)?.reason)
        policy.sent(point,0)
        assertNull(policy.evaluate(point,1_799_000,false))
        assertEquals("Interval",policy.evaluate(point,1_800_000,false)?.reason)
        assertNull(policy.evaluate(point,1000,false,forced=true))
        assertEquals("Manual",policy.evaluate(point,2000,false,forced=true)?.reason)
    }
    @Test fun headingAndReconnectHonorMinimum() {
        val policy=AdaptiveBeaconPolicy(BeaconTiming())
        policy.sent(point.copy(speedKnots=30.0,course=350.0),0)
        val moved=point.copy(fix=AprsFix(28.537,-81.243,10_000),speedKnots=30.0,course=30.0)
        assertNull(policy.evaluate(moved,9000,true,reconnected=true))
        assertEquals("Connected",policy.evaluate(moved,10_000,true,reconnected=true)?.reason)
        assertNotNull(policy.evaluate(moved,10_000,true))
    }
    @Test fun oldFixRequiresExplicitLossTransition() {
        val policy=AdaptiveBeaconPolicy(BeaconTiming())
        policy.sent(point,0)
        assertNull(policy.evaluate(point.copy(current=false),2_000_000,true))
        assertEquals("GPS lost",policy.evaluate(point.copy(current=false),10_000,true,gpsTransition="GPS lost")?.reason)
    }
    @Test fun positionFormatsAndAmbiguity() {
        val plain=BeaconPackets.position("N0CALL-13",point,BeaconFormat(),"test")
        assertTrue(plain.startsWith("N0CALL-13>APRS,TCPIP*:@000000h"))
        val parsed=assertNotNull(AprsProtocol.parse(plain.trimEnd(),0))
        assertEquals(point.fix.latitude,parsed.latitude,0.0001)
        val compressed=BeaconPackets.position("N0CALL-13",point,BeaconFormat(compressed=true))
        assertEquals(point.fix.longitude,assertNotNull(AprsProtocol.parse(compressed.trimEnd(),0)).longitude,0.00001)
        val ambiguous=BeaconPackets.position("N0CALL-13",point,BeaconFormat(precision=-4))
        assertTrue(ambiguous.contains("28  .  N/081  .  W"))
        assertTrue(BeaconPackets.position("N0CALL-13",point,BeaconFormat(precision=1)).contains("!W"))
        assertTrue(BeaconPackets.position("N0CALL-13",point,BeaconFormat(precision=4)).contains("!w"))
    }
    @Test fun objectsItemsStatusAndPacketInjection() {
        assertTrue(BeaconPackets.entity("N0CALL","TEST",false,false,point,BeaconFormat()).contains(":;TEST     _010000z"))
        assertTrue(BeaconPackets.entity("N0CALL","TEST",true,true,point,BeaconFormat()).contains(":)TEST!"))
        assertTrue(BeaconPackets.status("N0CALL","Available",0).contains(":>010000zAvailable"))
        assertFailsWith<IllegalArgumentException> {BeaconPackets.position("N0CALL",point,BeaconFormat(),"bad\r\npacket")}
    }
}
