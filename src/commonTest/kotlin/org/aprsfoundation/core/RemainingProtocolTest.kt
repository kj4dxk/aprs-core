// SPDX-License-Identifier: AGPL-3.0-only
package org.aprsfoundation.core
import kotlin.test.*

class RemainingProtocolTest {
 @Test fun micESpecificationVector() {
  // APRS 1.0.1 chapter 10, p53: 112 degrees 7.74 W, 20 knots, 251 degrees.
  val s=assertNotNull(AprsProtocol.parse("N0CALL>S32UPT:`(_fn\"Oj/\"4T}",0))
  assertEquals(33+25.04/60,s.latitude,0.00001)
  assertEquals(-112-7.74/60,s.longitude,0.00001)
  assertEquals(20.0,s.speedKnots);assertEquals(251.0,s.courseDegrees)
  assertEquals(61.0,s.altitudeMeters);assertEquals("Returning",s.micEStatus)
 }
 @Test fun micEAllLongitudeBandsAndStatusBits() {
  for(lon in listOf(0.0,9.999,10.0,99.999,100.0,109.999,110.0,179.999))for(sign in listOf(-1,1))for(speed in listOf(0.0,19.0,86.0,199.0,200.0,799.0)) {
   val fix=AprsFix(sign*33.428,sign*lon,0)
   val wire=BeaconPackets.position("N0CALL",BeaconPosition(fix,speed,194.0),BeaconFormat(micE=true,statusTag="!ENROUTE!"),"Test")
   val s=assertNotNull(AprsProtocol.parse(wire.trimEnd('\r','\n'),0),wire)
   assertEquals(fix.latitude,s.latitude,0.00009);assertEquals(fix.longitude,s.longitude,0.00009)
   assertEquals(speed,s.speedKnots);assertEquals(194.0,s.courseDegrees);assertEquals("En Route",s.micEStatus)
   val packet=assertNotNull(RadioPacket.parse(wire.trimEnd('\r','\n')))
   assertEquals(packet.line(),Ax25Ui.decode(Ax25Ui.encode(packet))?.line())
  }
 }
 @Test fun centeredAmbiguityMatchesMicEAndUncompressed() {
  for(ambiguity in 1..4) {
   val p=BeaconPosition(AprsFix(49.058333333,-72.0291666667,0))
   val f=BeaconFormat(precision=-ambiguity,timestamp=BeaconTimestamp.NONE)
   val plain=assertNotNull(AprsProtocol.parse(BeaconPackets.position("N0CALL",p,f).trimEnd(),0))
   val compact=assertNotNull(AprsProtocol.parse(BeaconPackets.position("N0CALL",p,f.copy(micE=true)).trimEnd('\r','\n'),0))
   assertEquals(ambiguity,plain.ambiguityDigits);assertEquals(plain.latitude,compact.latitude,0.00001);assertEquals(plain.longitude,compact.longitude,0.00001)
  }
  assertNull(AprsProtocol.parse("N0CALL>APRS:!490 .50N/07201.  W>",0))
 }
 @Test fun malformedMicEIsRejected() {
  for(body in listOf("`","`12345","`(_fn\"Oj!")) assertNull(MicE.decode("N0CALL","S32UPT",body,0))
  assertNull(MicE.decode("N0CALL","SHORT","`(_fn\"Oj/",0))
  val edge=BeaconPackets.position("N0CALL",BeaconPosition(AprsFix(0.0,180.0,0)),BeaconFormat(micE=true))
  assertEquals(180.0,assertNotNull(AprsProtocol.parse(edge.trimEnd(),0)).longitude)
 }
 @Test fun longMessagesRetainTextAndWireLimits() {
  for(size in listOf(1,67,68,122,299,300)) {
   val text="x".repeat(size);val parts=AprsMessages.split(text)
   assertTrue(parts.all {AprsMessages.validText(it)})
   assertEquals(text,parts.joinToString("") {if(parts.size==1)it else it.substringAfter(") ")})
  }
  assertFails {AprsMessages.split("x".repeat(301))};assertFails {AprsMessages.split("hello\nworld")}
 }
 @Test fun repeaterFrequencyToneOffsetAndRange() {
  val f=assertNotNull(StationCapabilities.frequency("146.940MHz T123 -060 R25 Repeater"))
  assertEquals(146.94,f.mhz);assertEquals(-0.6,f.offsetMhz);assertEquals("T123",f.tone);assertEquals(25,f.rangeMiles)
  assertNull(StationCapabilities.frequency("unrelated 146.94"))
 }
}
