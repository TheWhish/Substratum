package xyz.thewhish.substratum.rift

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import xyz.thewhish.substratum.config.ServerConfig
import xyz.thewhish.substratum.level.SubstratumLevels
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object RiftSpawner {

    private const val SCAN_INTERVAL_TICKS = 200L

    private const val PENDING_CHECK_INTERVAL_TICKS = 20L

    private const val MAX_WAIT_TICKS = 20L * 60 * 5

    private const val SEEN_RADIUS = 64.0

    private const val LOOK_COS_THRESHOLD = 0.77

    sealed interface SpawnResult {
        data object Opened : SpawnResult
        data object Elsewhere : SpawnResult
        data object NoCandidate : SpawnResult
        data object NoTarget : SpawnResult
        data object CutFailed : SpawnResult
    }

    private class Pending(
        val outer: ResourceKey<Level>,
        val lower: BlockPos,
        val mouth: Direction,
        val target: BlockPos,
        val out: Direction,
        val corridor: List<BlockPos>,
        val owner: UUID,
        val startTick: Long
    )

    private val pending = ConcurrentHashMap<UUID, Pending>()

    fun register() {
        TickEvent.SERVER_POST.register(::tick)
        LifecycleEvent.SERVER_STOPPING.register(::abandonAll)
    }

    private fun abandonAll(server: MinecraftServer) {
        val entries = pending.values.toList()
        pending.clear()
        entries.forEach { abandon(server, it.corridor) }
    }

    private fun tick(server: MinecraftServer) {
        if (server.tickCount % PENDING_CHECK_INTERVAL_TICKS == 0L) tickPending(server)
        if (server.tickCount % SCAN_INTERVAL_TICKS == 0L) tickScan(server)
    }

    private fun tickScan(server: MinecraftServer) {
        if (!ServerConfig.riftsEnabled) return
        val currentDay = server.overworld().gameTime / RiftSpawnData.DAY_TICKS
        for (player in server.playerList.players) {
            if (player.isSpectator || pending.containsKey(player.uuid)) continue
            if (RiftLifecycle.hasActiveEntrance(player.uuid)) continue
            val level = player.level()
            if (level !is ServerLevel || level.dimension() == SubstratumLevels.LEVEL_0) continue
            if (RiftSpawnData.onCooldown(player, currentDay)) continue
            if (!RiftSpawnData.rollPerScan(level.random, ServerConfig.riftChancePerDay, SCAN_INTERVAL_TICKS)) continue
            fixAttempt(server, level, player)
        }
    }

    private fun fixAttempt(server: MinecraftServer, level: ServerLevel, player: ServerPlayer) {
        val candidate = RiftCandidates.pick(level, player, level.random) ?: return
        val maze = server.getLevel(SubstratumLevels.LEVEL_0) ?: return
        val site = Crawlspace.openFresh(maze, candidate.mouth.axis, level.random, through = false) ?: return
        pending[player.uuid] = Pending(
            level.dimension(), candidate.lower, candidate.mouth, site.deadEnd, site.out, site.corridor(),
            player.uuid, server.tickCount.toLong()
        )
    }

    private fun tickPending(server: MinecraftServer) {
        if (pending.isEmpty()) return
        for (uuid in pending.keys.toList()) {
            val entry = pending[uuid] ?: continue
            val outer = server.getLevel(entry.outer)
            val owner = server.playerList.getPlayer(entry.owner)
            if (outer == null || owner == null || owner.level() !== outer ||
                RiftLifecycle.hasActiveEntrance(entry.owner) || server.tickCount - entry.startTick > MAX_WAIT_TICKS
            ) {
                pending.remove(uuid)
                abandon(server, entry.corridor)
                continue
            }
            if (outer.chunkSource.getChunkNow(entry.lower.x shr 4, entry.lower.z shr 4) == null) continue
            if (!entry.lower.closerToCenterThan(owner.position(), RiftLifecycle.PRESENCE_RADIUS)) continue
            if (isWatched(server, outer, entry.lower)) continue
            pending.remove(uuid)
            if (!Rifts.cut(outer, entry.lower, entry.mouth, through = false)) {
                abandon(server, entry.corridor)
                continue
            }
            RiftLifecycle.open(outer, PortalLink(entry.lower, entry.mouth, entry.target, entry.out), entry.corridor, entry.owner)
        }
    }

    private fun abandon(server: MinecraftServer, corridor: List<BlockPos>) {
        val maze = server.getLevel(SubstratumLevels.LEVEL_0) ?: return
        corridor.forEach { Rifts.close(maze, it) }
    }

    internal fun isWatched(server: MinecraftServer, level: ServerLevel, lower: BlockPos): Boolean {
        val upper = lower.above()
        val center = Vec3(lower.x + 0.5, lower.y + 1.0, lower.z + 0.5)
        return server.playerList.players.any { player ->
            if (player.level() !== level) return@any false
            val eye = player.eyePosition
            val toTarget = center.subtract(eye)
            val distanceSq = toTarget.lengthSqr()
            if (distanceSq > SEEN_RADIUS * SEEN_RADIUS || distanceSq < 1.0e-6) return@any false
            if (toTarget.normalize().dot(player.lookAngle) < LOOK_COS_THRESHOLD) return@any false
            val hit = level.clip(ClipContext(eye, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
            hit.type == HitResult.Type.MISS || hit.blockPos == lower || hit.blockPos == upper
        }
    }

    fun forceSpawn(server: MinecraftServer, player: ServerPlayer): SpawnResult {
        val level = player.serverLevel()
        if (level.dimension() == SubstratumLevels.LEVEL_0) return SpawnResult.Elsewhere
        pending.remove(player.uuid)?.let { abandon(server, it.corridor) }
        RiftLifecycle.closeActiveEntrance(server, player.uuid)
        val candidate = RiftCandidates.pick(level, player, level.random) ?: return SpawnResult.NoCandidate
        val maze = server.getLevel(SubstratumLevels.LEVEL_0) ?: return SpawnResult.NoTarget
        val site = Crawlspace.openFresh(maze, candidate.mouth.axis, level.random, through = false)
            ?: return SpawnResult.NoTarget
        if (!Rifts.cut(level, candidate.lower, candidate.mouth, through = false)) {
            abandon(server, site.corridor())
            return SpawnResult.CutFailed
        }
        val link = PortalLink(candidate.lower, candidate.mouth, site.deadEnd, site.out)
        RiftLifecycle.open(level, link, site.corridor(), player.uuid)
        return SpawnResult.Opened
    }
}
