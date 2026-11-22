// SPDX-License-Identifier: AGPL-3.0-only
package net.osmand.shared.aprs
import kotlin.test.*
class ParityProtocolTest {
 @Test fun thirdPartyPositionAndOwnedKill() {
  val store=AprsStationStore()
  store.receive("IGATE>APRS:}N0CALL>APRS,TCPIP,IGATE*:!2830.00N/08112.00W>",1000)
  assertEquals("N0CALL",store.snapshot(1000).single().callsign)
  store.receive("IGATE>APRS:}N0CALL>APRS:;FIRE HQ  *211200z2830.00N/08112.00W>",1100)
  store.receive("IGATE>APRS:}OTHER>APRS:;FIRE HQ  _211200z2830.00N/08112.00W>",1200)
  assertEquals(2,store.snapshot(1200).size)
  store.receive("IGATE>APRS:}N0CALL>APRS:;FIRE HQ  _211200z2830.00N/08112.00W>",1300)
  assertEquals(listOf("N0CALL"),store.snapshot(1300).map {it.callsign})
  assertNull(AprsProtocol.parse("IGATE>APRS:}IGATE>APRS:}IGATE>APRS:}IGATE>APRS:}N0CALL>APRS:!2830.00N/08112.00W>",0))
 }
 @Test fun strictObjectAndNwsFilters() {
  val packet=RadioPacket.parse("N0CALL>APRS:;FIRE HQ  *211200z2830.00N/08112.00W>")!!
  assertTrue(PacketFilter("b/OTHER os/FIRE HQ").matches(packet,null,emptyMap()))
  assertFalse(PacketFilter("os/FIRE STN").matches(packet,null,emptyMap()))
  val nws=RadioPacket.parse("MLBFFW>APRS::NWS_WARN :211200z,FLASH_FLOOD,FLZ041{12345")!!
  assertTrue(PacketFilter("g/NWS_WARN").matches(nws,null,emptyMap()))
  assertTrue(PacketFilter("t/n").matches(nws,null,emptyMap()))
 }
 @Test fun independentDaoCoordinatesAndHemispheres() {
  for((ns,ew,sign) in listOf(Triple('N','E',1),Triple('S','W',-1))) {
   val p=AprsProtocol.parse("N0CALL>APRS:!2830.00$ns/08112.00$ew>!W23!",0)!!
   assertEquals(sign*(28.5+0.002/60),p.latitude,1e-10)
   assertEquals(sign*(81.2+0.003/60),p.longitude,1e-10)
   val b=AprsProtocol.parse("N0CALL>APRS:!2830.00$ns/08112.00$ew>!wAb!",0)!!
   assertEquals(sign*(28.5+32.0/91/6000),b.latitude,1e-10)
   assertEquals(sign*(81.2+65.0/91/6000),b.longitude,1e-10)
  }
 }
 @Test fun routingProtectionStillRejectsInternetEnvelope() {
  val p=RadioPacket.parse("IGATE>APRS:}N0CALL>APRS,TCPIP,IGATE*:!2830.00N/08112.00W>")!!
  assertNull(RadioRouter("N0TEST",RadioRules()).toInternet(p,true))
 }
 @Test fun weatherObjectIdentityAndMidnightOnly() {
  val w=AprsObservations.weather("N0CALL>APRS:;WEATHER  *211200z2830.00N/08112.00W_P010",0)!!
  assertEquals("WEATHER",w.call);assertEquals(2.54,w.rainMidnightMm!!,1e-6)
 }

 @Test fun telemetryUsesPublishedBase91Vector() {
  // OH7LZB extension section 3.10; independent fixed expected values.
  val value=AprsObservations.telemetry("N0CALL>APRS:!2830.00N/08112.00W>|ss1122334455!\"|",0)!!
  assertEquals("7544",value.sequence)
  assertEquals(listOf(1472.0,1564.0,1656.0,1748.0,1840.0),value.analog)
  assertEquals("10000000",value.bits)
  val p=AprsProtocol.parse("N0CALL>APRS:!2830.00N/08112.00W>|!W23!!|",0)!!
  assertEquals(28.5,p.latitude);assertEquals(-81.2,p.longitude)
 }
 @Test fun multiSignalGsvPreservesOtherSignalsAndGsaSystems() {
  fun sentence(text:String)="$"+text+"*"+text.fold(0){v,c->v xor c.code}.toString(16).padStart(2,'0')
  val gps=GpsDiagnostics()
  gps.accept(sentence("GPGSV,1,1,01,01,40,100,25,1"),1000)
  gps.accept(sentence("GPGSV,2,1,05,01,40,100,35,02,30,120,20,03,50,160,30,04,20,180,25,2"),2000)
  assertEquals(1,gps.sky.satellites.size)
  gps.accept(sentence("GPGSV,2,2,05,05,20,200,30,2"),2100)
  assertEquals(5,gps.sky.satellites.size);assertEquals(35.0,gps.sky.satellites.first {it.id=="1"}.snr)
  gps.accept(sentence("GNGSA,A,3,01,,,,,,,,,,,,1.0,0.8,0.6,1"),2200)
  assertTrue(gps.sky.satellites.first {it.id=="1"}.used)
  gps.accept(sentence("GNGSA,A,3,02,,,,,,,,,,,,1.0,0.8,0.6,3"),2300)
  assertTrue(gps.sky.satellites.first {it.id=="1"}.used)
 }

 @Test fun micETelemetryFlagAndChannelMappingFollowChapterTen() {
  val prefix="N0CALL>S32UPT:`(_fn\"Oj/"
  assertEquals(listOf(114.0,0.0,0.0,113.0,0.0),AprsObservations.telemetry(prefix+"'7200007100",0)!!.analog)
  assertEquals(listOf(114.0,null,113.0,null,null),AprsObservations.telemetry(prefix+"`7271",0)!!.analog)
  assertNull(AprsObservations.telemetry(prefix+",7200007100",0))
 }
}
