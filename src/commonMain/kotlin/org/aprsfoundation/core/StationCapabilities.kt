// SPDX-License-Identifier: AGPL-3.0-only
package org.aprsfoundation.core

data class AprsFrequency(val mhz:Double,val offsetMhz:Double?,val tone:String?,val rangeMiles:Int?)
object StationCapabilities {
 fun frequency(comment:String):AprsFrequency? {
  val frequency=Regex("(?:^|\\s)([0-9]{3}\\.[0-9]{3})MHz(?:\\s|$)").find(comment)?.groupValues?.get(1)?.toDoubleOrNull()?:return null
  val offset=Regex("(?:^|\\s)([+-][0-9]{3})(?:\\s|$)").find(comment)?.groupValues?.get(1)?.toIntOrNull()?.div(100.0)
  val tone=Regex("(?:^|\\s)([TCD][0-9]{3}|Toff)(?:\\s|$)").find(comment)?.groupValues?.get(1)
  val range=Regex("(?:^|\\s)R([0-9]{2})(?:\\s|$)").find(comment)?.groupValues?.get(1)?.toIntOrNull()
  return AprsFrequency(frequency,offset,tone,range)
 }
 fun messageCapable(payload:String)=payload.firstOrNull() in listOf('=','@',':')
 fun platform(destination:String):String = when {
  destination.startsWith("APWW")->"APRSIS32"
  destination.startsWith("APMI")->"APRSISCE";destination.startsWith("APRS")->"APRS (unspecified)"
  destination.startsWith("APDR")->"APRSdroid";destination.startsWith("APDW")->"Dire Wolf"
  destination.startsWith("APXS")->"Xastir";destination.startsWith("APK")->"Kenwood"
  destination.startsWith("APY")->"Yaesu";else->destination
 }
}
