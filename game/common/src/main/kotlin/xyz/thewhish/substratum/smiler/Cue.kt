package xyz.thewhish.substratum.smiler

import net.minecraft.world.phys.Vec3
import xyz.thewhish.substratum.network.VhsSync
import java.util.UUID

class Cue(
    val spots: List<Vec3>,
    val anyTurn: Boolean,
    val at: Long,
    val covered: Set<UUID>,
    val spared: Set<UUID>,
    val retries: Int,
) {

    companion object {
        const val DELAY = VhsSync.TICKS - 2L
        const val RETRIES = 2
    }
}
