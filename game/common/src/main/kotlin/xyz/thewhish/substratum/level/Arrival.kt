package xyz.thewhish.substratum.level

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.portal.DimensionTransition
import net.minecraft.world.phys.Vec3
import xyz.thewhish.substratum.worldgen.MazeChunkGenerator
import xyz.thewhish.substratum.worldgen.MazeLayout
import java.util.Random

object Arrival {
    private const val FOOTING_RADIUS = 128
    private const val FLOOR = (MazeChunkGenerator.FLOOR_Y + 1).toDouble()

    fun spread(server: MinecraftServer, players: List<ServerPlayer>, hintX: Int, hintZ: Int) {
        val level = level0(server)
        val layout = layout(level) ?: return players.forEach(::send)
        val points = SpawnPlan.plan(layout, hintX, hintZ, Random(Epoch.salt(hintX.toLong() * 31 + hintZ)))
        for ((index, player) in players.withIndex()) {
            val point = points[index % points.size]
            player.teleportTo(level, point.x + 0.5, FLOOR, point.z + 0.5, SpawnPlan.facing(layout, point), 0f)
            player.resetFallDistance()
        }
    }

    fun send(player: ServerPlayer) {
        val level = level0(player.server)
        val at = fallback(level)
        player.teleportTo(level, at.x, at.y, at.z, player.yRot, 0f)
        player.resetFallDistance()
    }

    fun respawn(player: ServerPlayer, post: DimensionTransition.PostDimensionTransition): DimensionTransition {
        val level = level0(player.server)
        return DimensionTransition(level, fallback(level), Vec3.ZERO, player.yRot, 0f, post)
    }

    private fun level0(server: MinecraftServer): ServerLevel =
        checkNotNull(server.getLevel(SubstratumLevels.LEVEL_0)) { "Level 0 is not loaded" }

    private fun layout(level: ServerLevel): MazeLayout? = (level.chunkSource.generator as? MazeChunkGenerator)?.layout

    private fun fallback(level: ServerLevel): Vec3 {
        val footing = layout(level)?.findFooting(MazeLayout.CENTRE, MazeLayout.CENTRE, FOOTING_RADIUS, lit = true)
        val x = footing?.get(0) ?: MazeLayout.CENTRE
        val z = footing?.get(1) ?: MazeLayout.CENTRE
        return Vec3(x + 0.5, FLOOR, z + 0.5)
    }
}
