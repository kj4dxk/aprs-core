// SPDX-License-Identifier: AGPL-3.0-only
package org.aprsfoundation.core

/** Peet Bros packet/data-logger records. Units follow the manufacturer's serial specification. */
object UltimeterWeather {
 fun decode(call:String,payload:String,time:Long):AprsWeather?=runCatching {
  val packet=payload.startsWith("\$ULTW")
  val hex=when {packet->payload.drop(5);payload.startsWith("!!") || payload.startsWith("||")->payload.drop(2);else->return null}
  require(hex.length in (if(packet)44 else 40)..52 && hex.length%4==0 && hex.all {it in "0123456789abcdefABCDEF-"})
  val words=hex.chunked(4).map {it.toIntOrNull(16)}
  fun word(n:Int)=words.getOrNull(n)
  fun signed(n:Int)=word(n)?.let {if(it>=32768)it-65536 else it}
  val speed=word(if(packet)12 else 11)?.div(10.0) ?: if(!packet)word(0)?.div(10.0) else null
  val direction=word(1)?.and(255)?.let {(it*360.0/256).toInt()}
  val temperature=signed(2)?.let {(it/10.0-32)*5/9}
  val humidity=word(if(packet)8 else 6)?.div(10.0)?.toInt()?.takeIf {it in 0..100}
  val pressure=word(4)?.div(10.0)?.takeIf {it in 100.0..1200.0}
  AprsWeather(call,time,temperature,direction,speed,if(packet)word(0)?.div(10.0) else null,humidity,pressure,null,null,
   word(if(packet)11 else 10)?.times(0.254))
 }.getOrNull()
}
