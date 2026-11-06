package xyz.thewhish.substratum.network

import io.netty.buffer.Unpooled

private val CUT = "400c163a8ab27f6cc04f000000000000401d33333400000040340000c03400000001"
private val RECORDED = "400c000000000000405b000000000000401a00000000000000000000000000000001136d696e6563726166743a6f766572776f726c64"

private fun buffer(hex: String) = Unpooled.wrappedBuffer(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())

fun main() {
    check(ReplayPosition.lacksDimension(buffer(CUT))) { "Replay Mod cut packet must be detected as vanilla layout" }
    check(!ReplayPosition.lacksDimension(buffer(RECORDED))) { "recorded packet carries its dimension" }
    check(!ReplayPosition.lacksDimension(buffer(CUT.dropLast(2) + "80"))) { "unterminated teleport id is not vanilla layout" }
    check(ReplayPosition.lacksDimension(buffer(CUT.dropLast(2) + "8001"))) { "multi-byte teleport id is vanilla layout" }
    println("replay position: cut packet detected, recorded packet untouched")
}
