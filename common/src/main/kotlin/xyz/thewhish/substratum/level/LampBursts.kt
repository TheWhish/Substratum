package xyz.thewhish.substratum.level

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import xyz.thewhish.substratum.registry.ModBlocks
import xyz.thewhish.substratum.registry.ModBlocks.LampCondition
import xyz.thewhish.substratum.sanity.SanityTracker
import xyz.thewhish.substratum.worldgen.MazeChunkGenerator
import xyz.thewhish.substratum.worldgen.MazeLayout
import java.util.UUID

object LampBursts {
    const val EVENT = 1
    const val FLASH_TICKS = 5

    internal const val CHECK_INTERVAL = 20
    internal const val PLAYER_COOLDOWN = 1200L
    private const val QUIET_MEAN = 10800.0
    private const val HUNT_MEAN = 1500.0
    private const val RADIUS = 24
    private const val FAULTY_WEIGHT = 3

    private val lastHeard = HashMap<UUID, Long>()

    fun register() {
        TickEvent.SERVER_POST.register(::tick)
        PlayerEvent.PLAYER_QUIT.register { lastHeard.remove(it.uuid) }
        LifecycleEvent.SERVER_STOPPING.register { lastHeard.clear() }
    }

    internal fun rate(pressure: Double): Double = 1 / Mth.lerp(pressure, QUIET_MEAN, HUNT_MEAN)

    fun shatter(level: ServerLevel, pos: BlockPos, state: BlockState) {
        if (lit(state)) level.setBlockAndUpdate(pos, ModBlocks.lamp(LampCondition.DEAD))
    }

    fun burstNear(player: ServerPlayer): BlockPos? {
        val level = player.serverLevel().takeIf { it.dimension() == SubstratumLevels.LEVEL_0 } ?: return null
        val layout = (level.chunkSource.generator as? MazeChunkGenerator)?.layout ?: return null
        return burstNear(level, layout, player)
    }

    private fun tick(server: MinecraftServer) {
        if (server.tickCount % CHECK_INTERVAL != 0) return
        val level = server.getLevel(SubstratumLevels.LEVEL_0) ?: return
        val layout = (level.chunkSource.generator as? MazeChunkGenerator)?.layout ?: return
        for (player in level.players()) {
            if (player.isSpectator || !player.isAlive || resting(player, level.gameTime)) continue
            if (player.random.nextDouble() < CHECK_INTERVAL * rate(SanityTracker.pressure(player))) burstNear(level, layout, player)
        }
    }

    private fun burstNear(level: ServerLevel, layout: MazeLayout, player: ServerPlayer): BlockPos? {
        val pos = pick(level, layout, player) ?: return null
        level.blockEvent(pos, ModBlocks.lampBlock, EVENT, 0)
        level.scheduleTick(pos, ModBlocks.lampBlock, FLASH_TICKS)
        for (listener in level.players()) if (near(listener, pos)) lastHeard[listener.uuid] = level.gameTime
        return pos
    }

    private fun resting(player: ServerPlayer, now: Long): Boolean = lastHeard[player.uuid]?.let { now - it < PLAYER_COOLDOWN } == true

    private fun pick(level: ServerLevel, layout: MazeLayout, player: ServerPlayer): BlockPos? {
        val eye = player.eyePosition
        val lamps = ArrayList<BlockPos>()
        val weights = ArrayList<Int>()
        for (cellX in cell(player.blockX - RADIUS)..cell(player.blockX + RADIUS)) {
            for (cellZ in cell(player.blockZ - RADIUS)..cell(player.blockZ + RADIUS)) {
                val x = cellX * MazeLayout.CELL + MazeLayout.CENTRE
                val z = cellZ * MazeLayout.CELL + MazeLayout.CENTRE
                val column = layout.columnAt(x, z)
                if (column and MazeLayout.LAMP == 0) continue
                val pos = BlockPos(x, MazeLayout.ceilingOf(column), z)
                if (!near(player, pos) || !level.isLoaded(pos)) continue
                val state = level.getBlockState(pos)
                if (!lit(state) || !visible(level, player, eye, pos)) continue
                lamps.add(pos)
                weights.add(if (ModBlocks.isLamp(state, LampCondition.ON)) 1 else FAULTY_WEIGHT)
            }
        }
        if (lamps.isEmpty()) return null
        var roll = player.random.nextInt(weights.sum())
        for (i in lamps.indices) {
            roll -= weights[i]
            if (roll < 0) return lamps[i]
        }
        return null
    }

    private fun cell(block: Int): Int = Math.floorDiv(block, MazeLayout.CELL)

    private fun near(player: ServerPlayer, pos: BlockPos): Boolean {
        val dx = pos.x + 0.5 - player.x
        val dz = pos.z + 0.5 - player.z
        return dx * dx + dz * dz <= RADIUS * RADIUS
    }

    private fun lit(state: BlockState): Boolean = state.`is`(ModBlocks.lampBlock) && state.getValue(ModBlocks.LAMP_CONDITION).lit

    private fun visible(level: ServerLevel, player: ServerPlayer, eye: Vec3, pos: BlockPos): Boolean {
        val hit = level.clip(ClipContext(eye, Vec3.atCenterOf(pos), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        return hit.type == HitResult.Type.MISS || (hit as BlockHitResult).blockPos == pos
    }
}
