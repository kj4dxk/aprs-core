package org.aprsfoundation.core
import kotlin.test.*
class AprsFiltersTest {
    @Test fun composePreservesDeliveryAndCustomTerms() {
        assertEquals("r/28.0/-81.0/100 b/N1CALL g/N0CALL-13",AprsFilters.compose("N0CALL-13",28.0,-81.0,100,custom="b/N1CALL"))
        assertEquals("m/50 g/N0CALL",AprsFilters.compose("N0CALL",0.0,0.0,50,"moving"))
        assertEquals("d/RS0ISS* g/N0CALL",AprsFilters.compose("N0CALL",0.0,0.0,50,"custom","d/RS0ISS*"))
    }
    @Test fun rejectsInjectionAndOversizedLogin() {
        assertFailsWith<IllegalArgumentException> {AprsFilters.compose("N0CALL",0.0,0.0,100,"custom","x\r\nuser OTHER")}
        assertFailsWith<IllegalArgumentException> {AprsFilters.compose("N0CALL",0.0,0.0,100,"range","x".repeat(140))}
    }
}
