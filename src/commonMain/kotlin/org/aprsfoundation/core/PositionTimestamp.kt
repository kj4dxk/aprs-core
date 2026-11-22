// SPDX-License-Identifier: AGPL-3.0-only
package org.aprsfoundation.core
import kotlinx.datetime.*
import kotlin.math.abs

object PositionTimestamp {
 /** UTC timestamps only. A local-time timestamp has no reliable zone on APRS-IS. */
 fun decode(payload:String,received:Long):Long?=runCatching {
  val stamp=when(payload.firstOrNull()) {'/','@'->payload.drop(1).take(7);';'->payload.drop(11).take(7);else->return null}
  require(stamp.length==7 && stamp.take(6).all {it.isDigit()})
  val now=Instant.fromEpochMilliseconds(received).toLocalDateTime(TimeZone.UTC)
  val candidates=when(stamp.last()) {
   'h'->(-1..1).mapNotNull {offset->runCatching {LocalDateTime(now.date.plus(offset,DateTimeUnit.DAY),LocalTime(stamp.take(2).toInt(),stamp.substring(2,4).toInt(),stamp.substring(4,6).toInt())).toInstant(TimeZone.UTC).toEpochMilliseconds()}.getOrNull()}
   'z'->(-1..1).mapNotNull {offset->runCatching {val month=now.date.plus(offset,DateTimeUnit.MONTH);LocalDateTime(LocalDate(month.year,month.monthNumber,stamp.take(2).toInt()),LocalTime(stamp.substring(2,4).toInt(),stamp.substring(4,6).toInt())).toInstant(TimeZone.UTC).toEpochMilliseconds()}.getOrNull()}
   else->return null
  }
  candidates.minByOrNull {abs(it-received)}?.takeIf {it<=received+60000}
 }.getOrNull()
}
