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
import xyz.thewhish.substratum.sanity.SanityPhase
import xyz.thewhish.substratum.sanity.SanityTracker
import xyz.thewhish.substratum.worldgen.MazeChunkGenerator
import xyz.thewhish.substratum.worldgen.MazeLayout
import java.util.UUID

object LampBursts {
    const val EVENT = 1
    const val FLASH_TICKS = 5

    internal const val CHECK_INTERVAL = 20
    private const val QUIET_MEAN = 10800.0
    private const val DISORIENTATION_END_MEAN = 3600.0
    private const val HUNT_MEAN = 1500.0
    internal const val PLAYER_COOLDOWN = 1200L
    private const val AREA_COOLDOWN = 900L
    private const val AREA_RADIUS = 24
    private const val REACH = 7
    private const val NEAR = 4
    private const val NEAR_WEIGHT = 2
    private const val HEIGHT = MazeLayout.CEILING_HIGH - MazeChunkGenerator.FLOOR_Y
    private const val VIEW_COS = 0.5
    private const val VIEW_WEIGHT = 4
    private const val SICK_WEIGHT = 3

    private val BURSTABLE = setOf(LampCondition.ON, LampCondition.FLICKERING, LampCondition.DIM)

    private class Burst(val pos: BlockPos, val time: Long)

    private class Candidate(val pos: BlockPos, val weight: Int)

    private val lastBurst = HashMap<UUID, Long>()
    private val armed = HashSet<UUID>()
    private val recent = ArrayDeque<Burst>()

    fun register() {
        TickEvent.SERVER_POST.register(::tick)
        PlayerEvent.PLAYER_QUIT.register { forget(it.uuid) }
        LifecycleEvent.SERVER_STOPPING.register {
            lastBurst.clear()
            armed.clear()
            recent.clear()
        }
    }

    private fun canBurst(state: BlockState): Boolean =
        state.`is`(ModBlocks.lampBlock) && state.getValue(ModBlocks.LAMP_CONDITION) in BURSTABLE

    fun burst(level: ServerLevel, pos: BlockPos) {
        val at = pos.immutable()
        level.blockEvent(at, ModBlocks.lampBlock, EVENT, 0)
        level.scheduleTick(at, ModBlocks.lampBlock, FLASH_TICKS)
        recent.addLast(Burst(at, level.gameTime))
    }

    fun shatter(level: ServerLevel, pos: BlockPos, state: BlockState) {
        if (canBurst(state)) level.setBlockAndUpdate(pos, ModBlocks.lamp(LampCondition.BROKEN))
    }

    private fun tick(server: MinecraftServer) {
        if (server.tickCount % CHECK_INTERVAL != 0) return
        val level = server.getLevel(SubstratumLevels.LEVEL_0) ?: return
        val now = level.gameTime
        while (recent.isNotEmpty() && now - recent.first().time >= AREA_COOLDOWN) recent.removeFirst()
        for (player in server.playerList.players) {
            if (player.level() !== level || player.isSpectator || !player.isAlive) {
                armed.remove(player.uuid)
                continue
            }
            if (!arm(player, now)) continue
            val pos = pick(level, player, spaced = true) ?: continue
            burst(level, pos)
            armed.remove(player.uuid)
            lastBurst[player.uuid] = now
        }
    }

    fun rest(player: ServerPlayer, now: Long) {
        armed.remove(player.uuid)
        lastBurst[player.uuid] = now
    }

    private fun arm(player: ServerPlayer, now: Long): Boolean {
        if (Blackouts.isVictim(player)) return false
        if (player.uuid in armed) return true
        val last = lastBurst[player.uuid]
        if (last != null && now - last < PLAYER_COOLDOWN) return false
        if (player.random.nextDouble() >= CHECK_INTERVAL * rate(SanityTracker.ticks(player))) return false
        armed.add(player.uuid)
        return true
    }

    internal fun rate(ticks: Int): Double = when (SanityPhase.of(ticks)) {
        SanityPhase.QUIET -> 1 / QUIET_MEAN
        SanityPhase.HUNT -> 1 / HUNT_MEAN
        SanityPhase.DISORIENTATION -> {
            val start = SanityPhase.DISORIENTATION.startTick
            val progress = (ticks - start).toDouble() / (SanityPhase.HUNT.startTick - start)
            Mth.lerp(progress.coerceIn(0.0, 1.0), 1 / QUIET_MEAN, 1 / DISORIENTATION_END_MEAN)
        }
    }

    fun pick(level: ServerLevel, player: ServerPlayer, spaced: Boolean): BlockPos? {
        val eye = player.eyePosition
        val look = player.lookAngle
        val feet = player.blockPosition()
        val candidates = ArrayList<Candidate>()
        for (cursor in BlockPos.betweenClosed(feet.offset(-REACH, 1, -REACH), feet.offset(REACH, HEIGHT, REACH))) {
            val state = level.getBlockState(cursor)
            if (!canBurst(state) || Blackouts.darkened(level, cursor)) continue
            val centre = Vec3.atCenterOf(cursor)
            val dx = centre.x - eye.x
            val dz = centre.z - eye.z
            val flat = dx * dx + dz * dz
            if (flat > REACH * REACH || (spaced && crowded(cursor)) || !visible(level, player, eye, centre, cursor)) continue
            val sick = if (ModBlocks.isLamp(state, LampCondition.ON)) 1 else SICK_WEIGHT
            val seen = if (look.dot(centre.subtract(eye).normalize()) >= VIEW_COS) VIEW_WEIGHT else 1
            val near = if (flat <= NEAR * NEAR) NEAR_WEIGHT else 1
            candidates.add(Candidate(cursor.immutable(), sick * seen * near))
        }
        if (candidates.isEmpty()) return null
        var roll = player.random.nextInt(candidates.sumOf { it.weight })
        for (candidate in candidates) {
            roll -= candidate.weight
            if (roll < 0) return candidate.pos
        }
        return null
    }

    private fun crowded(pos: BlockPos): Boolean =
        recent.any { it.pos.distSqr(pos) < AREA_RADIUS * AREA_RADIUS }

    private fun visible(level: ServerLevel, player: ServerPlayer, eye: Vec3, centre: Vec3, pos: BlockPos): Boolean {
        val hit = level.clip(ClipContext(eye, centre, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        return hit.type == HitResult.Type.MISS || (hit as BlockHitResult).blockPos == pos
    }

    private fun forget(uuid: UUID) {
        lastBurst.remove(uuid)
        armed.remove(uuid)
    }
}
