package xyz.thewhish.substratum.level

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.PlayerEvent
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.portal.DimensionTransition
import net.minecraft.world.phys.Vec3
import xyz.thewhish.substratum.worldgen.MazeChunkGenerator
import xyz.thewhish.substratum.worldgen.MazeLayout

object Arrival {
    private const val FOOTING_RADIUS = 128

    fun register() {
        LifecycleEvent.SERVER_STARTED.register { server ->
            server.gameRules.getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0, server)
        }
        PlayerEvent.PLAYER_JOIN.register { player ->
            if (player.level().dimension() != SubstratumLevels.LEVEL_0) send(player)
        }
    }

    fun send(player: ServerPlayer) {
        val level = level0(player.server)
        val at = point(level)
        player.teleportTo(level, at.x, at.y, at.z, player.yRot, 0f)
        player.resetFallDistance()
    }

    fun respawn(player: ServerPlayer, post: DimensionTransition.PostDimensionTransition): DimensionTransition {
        val level = level0(player.server)
        return DimensionTransition(level, point(level), Vec3.ZERO, player.yRot, 0f, post)
    }

    private fun level0(server: MinecraftServer): ServerLevel =
        checkNotNull(server.getLevel(SubstratumLevels.LEVEL_0)) { "Level 0 is not loaded" }

    private fun point(level: ServerLevel): Vec3 {
        val layout = (level.chunkSource.generator as? MazeChunkGenerator)?.layout
        val footing = layout?.findFooting(MazeLayout.CENTRE, MazeLayout.CENTRE, FOOTING_RADIUS, lit = true)
        val x = footing?.get(0) ?: MazeLayout.CENTRE
        val z = footing?.get(1) ?: MazeLayout.CENTRE
        return Vec3(x + 0.5, (MazeChunkGenerator.FLOOR_Y + 1).toDouble(), z + 0.5)
    }
}
