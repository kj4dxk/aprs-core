// SPDX-License-Identifier: AGPL-3.0-only
package net.osmand.shared.aprs
object EmailMessages {
 fun packets(address:String,body:String):List<String> {
  require(address.matches(Regex("[^ @{}\\r\\n]+@[^ @{}\\r\\n]+\\.[^ @{}\\r\\n]+")) && address.length<=50)
  require(AprsMessages.validLongText(body))
  val available=67-address.length-1
  if(body.length<=available)return listOf("$address $body")
  val width=available-8;require(width>=8)
  val pieces=body.chunked(width);require(pieces.size<=40)
  return pieces.mapIndexed {i,text->"$address (${i+1}/${pieces.size}) $text".also {require(AprsMessages.validText(it))}}
 }
}
