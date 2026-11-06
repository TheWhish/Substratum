package xyz.thewhish.substratum.level

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import qouteall.imm_ptl.core.api.PortalAPI
import qouteall.imm_ptl.core.chunk_loading.ChunkLoader
import qouteall.imm_ptl.core.portal.Portal
import xyz.thewhish.substratum.rift.ExitSpawner
import xyz.thewhish.substratum.rift.PitPortals
import xyz.thewhish.substratum.worldgen.MazeChunkGenerator
import xyz.thewhish.substratum.worldgen.MazeLayout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object PitExitGuard {

    private const val TICK_INTERVAL = 4L

    private const val TELEPORT_MIN_DIST = 1000
    private const val TELEPORT_MAX_DIST = 2000
    private const val TELEPORT_ATTEMPTS = 8
    private const val DROP_SEARCH_CELLS = 8

    private const val PORTAL_CLEARANCE = 2
    private const val SKY_HEIGHT = 300

    private const val PRELOAD_RADIUS = 2
    private const val RACE_WINDOW_TICKS = 20 * 10L
    private const val DESCENT_TIMEOUT_TICKS = 20 * 30L

    private class Descent(val player: ServerPlayer, val portal: Portal, val loader: ChunkLoader, val deadline: Long)

    private class Race(val destination: Vec3, val until: Long)

    private val armed = ConcurrentHashMap.newKeySet<UUID>()
    private val descents = HashMap<UUID, Descent>()
    private val races = HashMap<BlockPos, Race>()

    fun register() {
        TickEvent.SERVER_POST.register(::tick)
        LifecycleEvent.SERVER_STOPPING.register { endAll() }
        PlayerEvent.PLAYER_QUIT.register { player ->
            armed.remove(player.uuid)
            descents.remove(player.uuid)?.let(::end)
        }
        PlayerEvent.PLAYER_JOIN.register { player -> armed.remove(player.uuid) }
        PlayerEvent.PLAYER_RESPAWN.register { player, _, _ -> armed.remove(player.uuid) }
    }

    fun onShaftEntered(player: ServerPlayer, pos: BlockPos) {
        if (player.isSpectator || player.uuid in armed || descents.containsKey(player.uuid)) return
        val level = player.serverLevel()
        val generator = level.chunkSource.generator as? MazeChunkGenerator ?: return
        val hole = generator.pitHoleAt(pos.x, pos.z) ?: return
        val center = BlockPos(
            hole[0] + MazeLayout.PIT_HOLE / 2,
            MazeChunkGenerator.SHAFT_BOTTOM_Y + PORTAL_CLEARANCE,
            hole[1] + MazeLayout.PIT_HOLE / 2
        )
        armed.add(player.uuid)
        val portal = PitPortals.at(level, center) ?: openPortal(level, generator, player, hole, center) ?: return
        val destination = portal.destPos
        val loader = ChunkLoader(
            portal.destDim,
            SectionPos.blockToSectionCoord(destination.x),
            SectionPos.blockToSectionCoord(destination.z),
            PRELOAD_RADIUS
        )
        PortalAPI.addChunkLoaderForPlayer(player, loader)
        descents[player.uuid] = Descent(player, portal, loader, level.server.tickCount + DESCENT_TIMEOUT_TICKS)
    }

    fun onPortalCrossed(portal: Portal, entity: Entity) {
        if (entity !is ServerPlayer || !PitPortals.isPit(portal)) return
        armed.add(entity.uuid)
        val now = entity.server.tickCount.toLong()
        val hole = PitPortals.holeCenter(portal)
        if ((races[hole]?.until ?: 0L) <= now) races[hole] = Race(portal.destPos, now + RACE_WINDOW_TICKS)
        descents.remove(entity.uuid)?.let(::end)
        discardIfIdle(portal)
    }

    fun consumeFallImmunity(player: ServerPlayer): Boolean = armed.remove(player.uuid)

    fun clearFallImmunity(player: ServerPlayer) {
        armed.remove(player.uuid)
    }

    private fun tick(server: MinecraftServer) {
        if (server.tickCount % TICK_INTERVAL != 0L) return
        armed.removeIf { uuid -> server.playerList.getPlayer(uuid)?.isFallFlying ?: true }
        val now = server.tickCount.toLong()
        races.values.removeIf { it.until <= now }
        if (descents.isEmpty()) return
        val over = descents.values.filter { !stillFalling(it, now) }
        over.forEach { descents.remove(it.player.uuid) }
        over.forEach(::end)
    }

    private fun stillFalling(descent: Descent, now: Long): Boolean {
        val player = descent.player
        return now < descent.deadline && player.isAlive && !player.onGround() &&
            player.level() === descent.portal.level() && !descent.portal.isRemoved
    }

    private fun end(descent: Descent) {
        PortalAPI.removeChunkLoaderForPlayer(descent.player, descent.loader)
        discardIfIdle(descent.portal)
    }

    private fun discardIfIdle(portal: Portal) {
        if (descents.values.none { it.portal === portal }) portal.discard()
    }

    private fun endAll() {
        val all = descents.values.toList()
        descents.clear()
        races.clear()
        all.forEach(::end)
    }

    private fun openPortal(
        level: ServerLevel,
        generator: MazeChunkGenerator,
        player: ServerPlayer,
        hole: IntArray,
        center: BlockPos
    ): Portal? {
        val exit = generator.pitHoleIsExit(hole[0], hole[1])
        val raced = races[center]?.takeIf { it.until > level.server.tickCount }?.destination
        val destination = raced ?: if (exit) exitTarget(player, level.random) else {
            teleportTarget(generator, level.random, center.x, center.z)
        }
        if (destination == null) {
            if (exit) ExitSpawner.schedule(level.server, player)
            return null
        }
        return if (exit) {
            PitPortals.open(level, center, Level.OVERWORLD, destination, PitPortals.EXIT_TAG)
        } else {
            PitPortals.open(level, center, SubstratumLevels.LEVEL_0, destination, PitPortals.TELEPORT_TAG)
        }
    }

    private fun exitTarget(player: ServerPlayer, random: RandomSource): Vec3? {
        val target = Homecoming.target(player, random) ?: return null
        return Vec3(target.x + 0.5, target.y + SKY_HEIGHT + 0.5, target.z + 0.5)
    }

    private fun teleportTarget(generator: MazeChunkGenerator, random: RandomSource, x: Int, z: Int): Vec3? {
        repeat(TELEPORT_ATTEMPTS) {
            val angle = random.nextDouble() * 2.0 * Math.PI
            val dist = TELEPORT_MIN_DIST + random.nextInt(TELEPORT_MAX_DIST - TELEPORT_MIN_DIST + 1)
            val drop = generator.corridorDrop(
                x + (Math.cos(angle) * dist).toInt(),
                z + (Math.sin(angle) * dist).toInt(),
                DROP_SEARCH_CELLS
            ) ?: return@repeat
            return Vec3(drop[0] + 0.5, MazeLayout.CEILING_LOW.toDouble(), drop[1] + 0.5)
        }
        return null
    }
}
