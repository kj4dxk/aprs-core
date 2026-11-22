// SPDX-License-Identifier: AGPL-3.0-only
package org.aprsfoundation.core
import kotlin.test.*

/** Source fixtures added with the feature; execution intentionally deferred by the user. */
class AprsSpatialTest {
 @Test fun phgReferenceAndWeatherIsNotMotion() {
  val station=AprsStation("N1CALL",35.0,-77.5,'/','#',"PHG5132",1000)
  val range=assertNotNull(AprsSpatial.details(station).coverage)
  assertTrue(range.radiusMeters in 12500.0..13000.0);assertEquals(90.0,range.bearing)
  assertNull(AprsSpatial.predicted(station.copy(symbol='_',comment="270/040g050"),2000,300))
 }
 @Test fun predictionExpiresAndCrossesDateline() {
  val moving=AprsStation("N1CALL",0.0,179.999,'/','>',"090/060",1000)
  val projected=assertNotNull(AprsSpatial.predicted(moving,61000,300))
  assertTrue(projected.longitude< -179.9)
  assertEquals(1852.0,AdaptiveBeaconPolicy.distance(AprsSpatial.fix(moving),projected),1.0)
  assertNull(AprsSpatial.predicted(moving,301001,300))
 }
 @Test fun publishedMultilineExampleUsesWestPositiveOffsets() {
  val station=AprsStation("SPCS1528z",35.0,-77.5,'S','W',"Svr TStormWatch #174 }e0]FgcBS6:W{QFSAA",0)
  val shape=assertNotNull(AprsSpatial.multiline(station))
  assertEquals(34.2,shape.points[0].latitude,0.00001);assertEquals(-80.0,shape.points[0].longitude,0.00001)
  assertEquals(37.1,shape.points[1].latitude,0.00001);assertEquals(-76.3,shape.points[1].longitude,0.00001)
  assertTrue(shape.closed);assertTrue(shape.dashed);assertEquals(shape.points.first(),shape.points.last())
 }
 @Test fun dfBearingAndQuality() {
  val d=assertNotNull(AprsSpatial.details(AprsStation("N1CALL",35.0,-77.5,'/','\\',"088/036/270/729",0)).direction)
  assertEquals(270.0,d.bearing);assertEquals(6437.376,d.rangeMeters,0.001);assertEquals(1.0,d.accuracyDegrees)
 }
 @Test fun pathGapsAndDirectHeardClassification() {
  val p=assertNotNull(RadioPacket.parse("N1CALL>APRS,WIDE1-1,DIGI*,qAR,IGATE:!3500.00N/07730.00W-"))
  assertEquals(listOf("N1CALL",null,"DIGI","IGATE"),AprsSpatial.pathNodes(p));assertFalse(AprsSpatial.directlyHeard(p))
  assertTrue(AprsSpatial.directlyHeard(p.copy(path=listOf("WIDE1-1"))))
  assertFalse(AprsSpatial.directlyHeard(p.copy(path=emptyList(),payload="}N2CALL>APRS:>test")))
 }
 @Test fun directedItemAndOwnerScopedKill() {
  val line="N1CALL>APRS::N0CALL   :)HOSP!3500.00N/07730.00WhHospital"
  val s=assertNotNull(AprsProtocol.parse(line,1000));assertEquals("HOSP",s.callsign);assertEquals(35.0,s.latitude)
  val store=AprsStationStore();store.receive(line,1000)
  store.receive("N2CALL>APRS::N0CALL   :)HOSP_",1001);assertEquals(1,store.snapshot(1001).size)
  store.receive("N1CALL>APRS::N0CALL   :)HOSP_",1002);assertTrue(store.snapshot(1002).isEmpty())
 }
 @Test fun qruUsesRequesterPositionAndCapsReplies() {
  val objects=listOf(QueryObject("NEAR","HOSP",AprsFix(0.01,0.0,0)),QueryObject("FAR","HOSP",AprsFix(30.0,0.0,0)),QueryObject("FUEL","FUEL",AprsFix(0.0,0.0,0)))
  assertEquals(listOf("NEAR"),QruObjects.nearby(objects,AprsFix(0.0,0.0,0),QruQuery("HOSP",50),5).map {it.name})
  assertNull(QruObjects.query("HOSP 99999",50,500));assertEquals(25,QruObjects.query("INFO 25",50,500)?.radiusKm)
 }
 @Test fun areaBoxExtentAndHorizon() {
  val shape=assertNotNull(AprsSpatial.area(AprsStation("AREA",35.0,-77.5,'\\','l',"410/410",0)))
  assertEquals(34.99,shape.points[2].latitude,0.00001);assertEquals(-77.49,shape.points[2].longitude,0.00001)
  assertEquals(41200.0,AprsSpatial.horizonMeters(100.0,0.0,0.0),0.01)
 }
}
