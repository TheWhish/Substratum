package xyz.thewhish.substratum.level

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.PlayerEvent
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.portal.DimensionTransition
import net.minecraft.world.phys.Vec3
import xyz.thewhish.substratum.rift.SafeLanding
import java.util.UUID

object Homecoming {
    const val MIN_DIST = 500
    const val MAX_DIST = 1000

    private const val NEAR_MIN_DIST = 16
    private const val ATTEMPTS = 32
    private const val LANDING_ATTEMPTS = 3
    private const val ABSENCE_MILLIS = 5 * 60 * 1000L

    private val leftAt = HashMap<UUID, Long>()

    fun register() {
        PlayerEvent.PLAYER_QUIT.register { player ->
            if (player.level().dimension() == SubstratumLevels.LEVEL_0) leftAt[player.uuid] = System.currentTimeMillis()
        }
        PlayerEvent.PLAYER_JOIN.register(::onJoin)
        LifecycleEvent.SERVER_BEFORE_START.register { leftAt.clear() }
    }

    fun target(player: ServerPlayer, random: RandomSource): BlockPos? {
        val overworld = player.server.overworld()
        val center = center(player)
        return SafeLanding.find(overworld, center, MIN_DIST, MAX_DIST, ATTEMPTS, random)
            ?: SafeLanding.find(overworld, center, NEAR_MIN_DIST, MIN_DIST, ATTEMPTS, random)
    }

    fun respawn(player: ServerPlayer, post: DimensionTransition.PostDimensionTransition): DimensionTransition {
        val overworld = player.server.overworld()
        return DimensionTransition(overworld, landing(player, overworld), Vec3.ZERO, player.yRot, 0f, post)
    }

    fun send(player: ServerPlayer) {
        val overworld = player.server.overworld()
        val at = landing(player, overworld)
        player.teleportTo(overworld, at.x, at.y, at.z, player.yRot, 0f)
        player.resetFallDistance()
    }

    private fun onJoin(player: ServerPlayer) {
        val left = leftAt.remove(player.uuid)
        if (player.level().dimension() != SubstratumLevels.LEVEL_0 || !player.isAlive) return
        if (left != null && System.currentTimeMillis() - left <= ABSENCE_MILLIS) return
        send(player)
    }

    private fun center(player: ServerPlayer): BlockPos =
        player.respawnPosition?.takeIf { player.respawnDimension == Level.OVERWORLD } ?: player.server.overworld().sharedSpawnPos

    private fun landing(player: ServerPlayer, overworld: ServerLevel): Vec3 {
        repeat(LANDING_ATTEMPTS) {
            val column = target(player, overworld.random) ?: return spawnLanding(overworld)
            SafeLanding.settle(overworld, column)?.let { return Vec3.atBottomCenterOf(it) }
        }
        return spawnLanding(overworld)
    }

    private fun spawnLanding(overworld: ServerLevel): Vec3 {
        val spawn = overworld.sharedSpawnPos
        return Vec3.atBottomCenterOf(SafeLanding.settle(overworld, spawn) ?: spawn)
    }
}
