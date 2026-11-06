package xyz.thewhish.substratum.rift

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction

class PortalLink(
    val rift: BlockPos,
    val mouth: Direction,
    val target: BlockPos,
    val out: Direction
) {
    val forward: Direction get() = mouth.opposite
}
