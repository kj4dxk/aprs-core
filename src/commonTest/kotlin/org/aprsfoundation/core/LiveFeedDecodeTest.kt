package org.aprsfoundation.core
import kotlin.test.*
class LiveFeedDecodeTest {
 @Test fun compressedCardinalBytesDoNotSelectUncompressedFormat() {
  val p=assertNotNull(AprsProtocol.parse("N0CALL>APRS:=L9:K:YwNj# !G4L5EE LoRa",0))
  assertEquals(41.9681617952,p.latitude,0.000001);assertEquals(45.3262313415,p.longitude,0.000001)
 }
 @Test fun lowercaseHemisphereIsNotCompressed() {
  val p=assertNotNull(AprsProtocol.parse("N0CALL>APRS:!3827.76s/17752.26eyTest",0))
  assertEquals(-38.4626666667,p.latitude,0.000001);assertEquals(177.871,p.longitude,0.000001)
  assertEquals('/',p.symbolTable);assertEquals('y',p.symbol)
 }
 @Test fun internationalCommentsAndLowercaseReceiveIdentity() {
  val line="n0call-1>APRS:!3137.23N/12021.96E>Station café 无线"
  val p=assertNotNull(AprsProtocol.parse(line,0));assertEquals("N0CALL-1",p.callsign)
  assertEquals(31.6205,p.latitude,0.000001);assertTrue(p.comment.contains("无线"))
  assertNull(RadioPacket.parse(line)) // RF/gating callers retain strict validation.
  val store=AprsStationStore();store.receive(line,0);assertEquals(1,store.snapshot(0).size)
 }
}
