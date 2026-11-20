package net.osmand.shared.aprs
import kotlin.test.*
class AprsObservationsTest {
 @Test fun weatherUnitsAndMissingFields() {
  val w=assertNotNull(AprsObservations.weather("N0CALL>APRS:_11201400c180s010g020t032r001p010h00b10132",1))
  assertEquals(0.0,w.temperatureC);assertEquals(100,w.humidity);assertEquals(1013.2,w.pressureHpa);assertEquals(0.254,w.rainHourMm)
  assertNull(AprsObservations.weather("N0CALL>APRS:>comment t032",1))
 }
 @Test fun telemetryMetadataAndBulletins() {
  val sample=assertNotNull(AprsObservations.telemetry("N0CALL>APRS:T#001,1,2,3,4,5,10101010",1))
  val d=assertNotNull(AprsObservations.definition("N0CALL>APRS::N0CALL   :EQNS.0,2,1,0,1,0,0,1,0,0,1,0,0,1,0"))
  assertEquals(3.0,AprsObservations.engineering(sample.analog[0],0,d))
  assertNull(AprsObservations.telemetry("N0CALL>APRS:T#001,999,2,3,4,5,10101010",1))
  assertNotNull(AprsObservations.bulletin("N0CALL>APRS::BLN1TEST :Meet at noon",1))
 }
 @Test fun servicesAndExportValidateWireData() {
  assertEquals("J TEST",AprsServices.group("J","test"));assertEquals("INFO 50",AprsServices.qru("INFO",50))
  assertFails {AprsServices.group("CQ","TEST","x".repeat(70))}
  assertTrue(AprsServices.gpx("A&B",listOf(AprsFix(1.0,2.0,0))).contains("A&amp;B"))
  assertTrue(AprsServices.bulletin("N0CALL","1","TEST","Hello").contains(":BLN1TEST :Hello"))
 }
}
