package org.aprsfoundation.core
import kotlin.test.*
class OperatingFeaturesTest {
    @Test fun smartSpeedAndTurnSchedules() {
        val policy=AdaptiveBeaconPolicy(BeaconTiming(smart=true))
        val p=BeaconPosition(AprsFix(28.0,-81.0,0),60.0,0.0)
        policy.sent(p,0)
        assertNull(policy.evaluate(p,119000,true))
        assertEquals("Smart interval",policy.evaluate(p,120000,true)?.reason)
        assertEquals("Smart turn",policy.evaluate(p.copy(course=90.0),30000,true)?.reason)
        assertNull(policy.evaluate(p.copy(course=90.0),29000,true))
        assertNull(policy.evaluate(p.copy(speedKnots=0.0),120000,true))
        assertEquals("Smart interval",policy.evaluate(p.copy(speedKnots=0.0),1800000,true)?.reason)
    }
    @Test fun objectKillsAreScopedToLastOwner() {
        val store=AprsStationStore()
        val p=BeaconPosition(AprsFix(28.0,-81.0,0))
        for(item in listOf(false,true)) {
            val name=if(item) "TESTITEM" else "TESTOBJ"
            store.receive(BeaconPackets.entity("N1CALL",name,item,true,p,BeaconFormat(),"").trimEnd(),0)
            store.receive(BeaconPackets.entity("N2CALL",name,item,false,p,BeaconFormat(),"").trimEnd(),1)
            assertTrue(store.snapshot(1).any {it.callsign==name})
            store.receive(BeaconPackets.entity("N1CALL",name,item,false,p,BeaconFormat(),"").trimEnd(),2)
            assertFalse(store.snapshot(2).any {it.callsign==name})
        }
    }
}
