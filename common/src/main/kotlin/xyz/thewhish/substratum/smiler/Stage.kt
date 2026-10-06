package xyz.thewhish.substratum.smiler

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import xyz.thewhish.substratum.gaze.Gaze
import xyz.thewhish.substratum.gaze.Watch
import xyz.thewhish.substratum.network.VhsSync
import xyz.thewhish.substratum.registry.ModEntities
import xyz.thewhish.substratum.worldgen.MazeLayout
import java.util.UUID

class Stage(val level: ServerLevel, val layout: MazeLayout) {

    val now: Long = level.gameTime
    val players: List<ServerPlayer> = level.players()
    val smilers: List<Smiler> by lazy { level.getEntities(ModEntities.SMILER.get()) { it.isAlive } }
    val gaze: Gaze = Watch.gaze(level)

    private fun seers(spot: Vec3, anyTurn: Boolean): List<ServerPlayer> =
        gaze.viewers.filter { Spots.sees(gaze, it, spot, anyTurn) }.mapNotNull { level.server.playerList.getPlayer(it.uuid) }.distinct()

    fun noticed(spot: Vec3): Boolean =
        gaze.viewers.any { viewer -> Spots.face(spot, viewer.eye).any(viewer::onScreen) && Spots.sees(gaze, viewer, spot) }

    fun hidden(spot: Vec3, gone: Set<UUID>): Boolean = gaze.viewers.none { it.uuid !in gone && Spots.sees(gaze, it, spot, anyTurn = true) }

    fun scene(victim: ServerPlayer): Spots.Scene? {
        val viewer = gaze.viewer(victim.uuid) ?: return null
        return Spots.Scene(
            gaze,
            victim.position(),
            viewer,
            players.filter { it !== victim && !it.isSpectator && it.distanceToSqr(victim) <= SCENE * SCENE }.map { it.position() },
            smilers.map { it.position() },
        )
    }

    fun weigh(x: Int, z: Int): Double {
        val spot = Spots.at(x, z)
        if (!level.isPositionEntityTicking(BlockPos.containing(spot))) return 0.0
        if (!level.noCollision(bodyAt(spot))) return 0.0
        val lit = gaze.lit(spot.add(0.0, Smiler.FACE_CENTRE, 0.0))
        val dark = layout.columnAt(x, z) and MazeLayout.DARK_ZONE != 0
        return 1.0 / (1.0 + GLOOM * lit.light) * (if (lit.sick) SICK else 1.0) * (if (dark) DARK else 1.0)
    }

    fun cue(spots: List<Vec3>, anyTurn: Boolean, spared: Set<UUID> = emptySet()): Cue {
        val targets = spots.flatMap { seers(it, anyTurn) }.distinct().filter { it.uuid !in spared }
        targets.forEach(VhsSync::send)
        return Cue(spots, anyTurn, now + Cue.DELAY, targets.mapTo(HashSet()) { it.uuid }, spared.toSet(), 0)
    }

    fun hold(cue: Cue): Cue? {
        if (now < cue.at) return cue
        val fresh = cue.spots.flatMap { seers(it, cue.anyTurn) }.distinct().filter { it.uuid !in cue.covered && it.uuid !in cue.spared }
        if (fresh.isEmpty() || cue.retries >= Cue.RETRIES) return null
        val covered = cue.covered + fresh.map { it.uuid }
        covered.mapNotNull(level.server.playerList::getPlayer).forEach(VhsSync::send)
        return Cue(cue.spots, cue.anyTurn, now + Cue.DELAY, covered, cue.spared, cue.retries + 1)
    }

    private fun bodyAt(spot: Vec3): AABB = AABB(
        spot.x - Smiler.FACE_HALF, spot.y, spot.z - Smiler.FACE_HALF,
        spot.x + Smiler.FACE_HALF, spot.y + Smiler.FACE_CENTRE + Smiler.FACE_HALF, spot.z + Smiler.FACE_HALF,
    )

    private companion object {
        const val SCENE = Gaze.REACH + 64.0
        const val GLOOM = 2.5
        const val SICK = 2.5
        const val DARK = 1.5
    }
}
