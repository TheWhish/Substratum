package xyz.thewhish.substratum.rift

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.TicketType
import net.minecraft.util.RandomSource
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import xyz.thewhish.substratum.config.ServerConfig
import xyz.thewhish.substratum.level.Homecoming
import xyz.thewhish.substratum.level.SubstratumLevels
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ExitSpawner {

    private const val SCAN_INTERVAL_TICKS = 200L
    private const val PENDING_CHECK_INTERVAL_TICKS = 20L
    private const val MAX_WAIT_TICKS = 20L * 60 * 5

    private val LANDING_TICKET: TicketType<ChunkPos> =
        TicketType.create("substratum_landing", Comparator.comparingLong(ChunkPos::toLong), MAX_WAIT_TICKS.toInt())

    private class Pending(
        val plan: Crawlspace.Plan,
        val target: BlockPos,
        val owner: UUID,
        val startTick: Long
    )

    private val pending = ConcurrentHashMap<UUID, Pending>()

    fun register() {
        TickEvent.SERVER_POST.register(::tick)
        LifecycleEvent.SERVER_BEFORE_START.register { pending.clear() }
    }

    private fun tick(server: MinecraftServer) {
        if (server.tickCount % PENDING_CHECK_INTERVAL_TICKS == 0L) tickPending(server)
        if (server.tickCount % SCAN_INTERVAL_TICKS == 0L) tickScan(server)
    }

    private fun tickScan(server: MinecraftServer) {
        for (player in server.playerList.players) {
            if (player.isSpectator || !RiftSpawnData.rollPerScan(player.serverLevel().random, ServerConfig.exitChancePerDay, SCAN_INTERVAL_TICKS)) continue
            schedule(server, player)
        }
    }

    fun schedule(server: MinecraftServer, player: ServerPlayer) {
        if (pending.containsKey(player.uuid) || RiftLifecycle.hasActiveExit(player.uuid)) return
        val level = player.level()
        if (level !is ServerLevel || level.dimension() != SubstratumLevels.LEVEL_0) return
        fixAttempt(server, level, player)
    }

    private fun fixAttempt(server: MinecraftServer, maze: ServerLevel, player: ServerPlayer) {
        val plan = Crawlspace.plan(maze, player.blockX, player.blockZ, null) ?: return
        val target = Homecoming.target(player, maze.random) ?: return
        val chunk = ChunkPos(target)
        server.overworld().chunkSource.addRegionTicket(LANDING_TICKET, chunk, 1, chunk)
        pending[player.uuid] = Pending(plan, target, player.uuid, server.tickCount.toLong())
    }

    private fun doorway(overworld: ServerLevel, column: BlockPos, random: RandomSource): Pair<BlockPos, Direction>? {
        val landing = SafeLanding.settle(overworld, column) ?: return null
        val start = random.nextInt(4)
        val sides = List(4) { Direction.from2DDataValue(start + it) }
        val out = sides.firstOrNull { SafeLanding.isClear(overworld, landing.relative(it.opposite)) } ?: sides.first()
        return landing.relative(out.opposite) to out
    }

    private fun tickPending(server: MinecraftServer) {
        if (pending.isEmpty()) return
        val maze = server.getLevel(SubstratumLevels.LEVEL_0) ?: return
        for (uuid in pending.keys.toList()) {
            val entry = pending[uuid] ?: continue
            val owner = server.playerList.getPlayer(entry.owner)
            if (owner == null || owner.level() !== maze || RiftLifecycle.hasActiveExit(entry.owner) ||
                server.tickCount - entry.startTick > MAX_WAIT_TICKS
            ) {
                pending.remove(uuid)
                continue
            }
            if (RiftSpawner.isWatched(server, maze, entry.plan.mouth)) continue
            val overworld = server.overworld()
            if (overworld.chunkSource.getChunkNow(entry.target.x shr 4, entry.target.z shr 4) == null) continue
            pending.remove(uuid)
            val (target, out) = doorway(overworld, entry.target, maze.random) ?: continue
            val site = entry.plan.carve(maze, through = false) ?: continue
            val link = PortalLink(site.deadEnd, site.out, target, out)
            RiftLifecycle.open(maze, link, site.corridor(), entry.owner, Level.OVERWORLD, ServerConfig.exitSeenDelayTicks)
        }
    }
}
