package xyz.thewhish.substratum.level

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import xyz.thewhish.substratum.network.BlackoutSync
import xyz.thewhish.substratum.registry.ModBlocks.LampCondition
import xyz.thewhish.substratum.sanity.SanityPhase
import xyz.thewhish.substratum.sanity.SanityTracker
import xyz.thewhish.substratum.worldgen.MazeChunkGenerator
import xyz.thewhish.substratum.worldgen.MazeLayout
import java.util.UUID

object Blackouts {
    const val RADIUS = 32
    const val HEIGHT = MazeLayout.CEILING_HIGH - MazeChunkGenerator.FLOOR_Y
    const val HYSTERESIS = 3
    const val DURATION = 1500L

    private const val RAGGED_EDGE = 6
    private const val RAMP = 300L
    private const val FADE = 400L
    private const val FLICKER_SHARE = 0x4CCCL
    private const val OUT_SHARE = 0x8000L
    private const val DIM_SHARE = 0xC000L

    private const val CHECK_INTERVAL = 20
    private const val SEND_RANGE = RADIUS + 64
    private const val FORGET_RANGE = SEND_RANGE + 16
    private const val SPACING = 2 * RADIUS
    private const val FIRST_DELAY = 2400
    private const val FIRST_SPREAD = 3600
    private const val NEXT_DELAY = 3600
    private const val NEXT_SPREAD = 2400
    private const val POP_FROM = 160L
    private const val POP_UNTIL = 1250L
    private const val POP_RETRY = 200L
    private const val POP_GAP = 200L

    class Event(val victim: Int, val start: Long, val seed: Long)

    private class Active(val event: Event, val victim: UUID, val pops: LongArray) {
        val informed = HashSet<UUID>()
        var nextPop = 0
        var lastPop = -POP_GAP
    }

    private val active = ArrayList<Active>()
    private val due = HashMap<UUID, Long>()

    fun register() {
        TickEvent.SERVER_POST.register(::tick)
        LifecycleEvent.SERVER_STOPPING.register {
            active.clear()
            due.clear()
        }
    }

    fun isVictim(player: ServerPlayer): Boolean = active.any { it.victim == player.uuid }

    private fun start(level: ServerLevel, victim: ServerPlayer) {
        val random = victim.random
        val count = 2 + random.nextInt(2)
        val slot = (POP_UNTIL - POP_FROM) / count
        val pops = LongArray(count) { POP_FROM + it * slot + random.nextInt((slot - POP_RETRY / 2).toInt()) }
        active.add(Active(Event(victim.id, level.gameTime, random.nextLong()), victim.uuid, pops))
        inform(level)
    }

    fun darkened(level: ServerLevel, pos: BlockPos): Boolean = active.any { entry ->
        val victim = level.getPlayerByUUID(entry.victim) ?: return@any false
        within(victim.x, victim.y, victim.z, pos, reach(entry.event, pos, false)) &&
            role(entry.event, pos, level.gameTime - entry.event.start) == LampCondition.OUT
    }

    fun role(event: Event, pos: BlockPos, age: Long): LampCondition? {
        if (age < 0 || age >= DURATION) return null
        val hash = mix(event.seed xor mix(pos.asLong()))
        val share = hash and 0xFFFF
        val role = when {
            share < FLICKER_SHARE -> LampCondition.FLICKERING
            share < OUT_SHARE -> LampCondition.OUT
            share < DIM_SHARE -> LampCondition.DIM
            else -> return null
        }
        val onset = Math.floorMod(hash ushr 16, RAMP)
        val fade = if (role == LampCondition.OUT) FADE / 2 else FADE
        val release = DURATION - Math.floorMod(hash ushr 32, fade)
        return if (age in onset until release) role else null
    }

    fun reach(event: Event, pos: BlockPos, inside: Boolean): Int {
        val ragged = Math.floorMod(mix(event.seed + pos.asLong()) ushr 48, RAGGED_EDGE.toLong()).toInt()
        return RADIUS - ragged + if (inside) HYSTERESIS else 0
    }

    fun within(x: Double, y: Double, z: Double, pos: BlockPos, reach: Int): Boolean {
        val dx = pos.x + 0.5 - x
        val dz = pos.z + 0.5 - z
        return dx * dx + dz * dz <= reach.toDouble() * reach && Math.abs(pos.y - y) <= HEIGHT
    }

    private fun tick(server: MinecraftServer) {
        if (server.tickCount % CHECK_INTERVAL != 0) return
        val level = server.getLevel(SubstratumLevels.LEVEL_0) ?: return
        val now = level.gameTime
        active.removeIf { !advance(level, it, now) }
        val present = level.players().mapTo(HashSet()) { it.uuid }
        due.keys.retainAll(present)
        active.forEach { it.informed.retainAll(present) }
        for (player in level.players()) schedule(level, player, now)
        inform(level)
    }

    private fun advance(level: ServerLevel, entry: Active, now: Long): Boolean {
        val victim = level.getPlayerByUUID(entry.victim) as? ServerPlayer
        val age = now - entry.event.start
        if (victim == null || !victim.isAlive || age >= DURATION) {
            if (victim != null) LampBursts.rest(victim, now)
            return false
        }
        if (entry.nextPop < entry.pops.size && age >= entry.pops[entry.nextPop] && age - entry.lastPop >= POP_GAP) pop(level, entry, victim, age)
        return true
    }

    private fun pop(level: ServerLevel, entry: Active, victim: ServerPlayer, age: Long) {
        val pos = LampBursts.pick(level, victim, spaced = false)
        if (pos != null) {
            LampBursts.burst(level, pos)
            entry.lastPop = age
        }
        if (pos != null || age >= entry.pops[entry.nextPop] + POP_RETRY) entry.nextPop++
    }

    private fun schedule(level: ServerLevel, player: ServerPlayer, now: Long) {
        if (isVictim(player)) {
            due[player.uuid] = now + NEXT_DELAY + player.random.nextInt(NEXT_SPREAD)
            return
        }
        if (player.isSpectator || !player.isAlive || SanityTracker.phase(player) != SanityPhase.HUNT) {
            due.remove(player.uuid)
            return
        }
        val at = due.getOrPut(player.uuid) { now + FIRST_DELAY + player.random.nextInt(FIRST_SPREAD) }
        if (now < at) return
        due[player.uuid] = now + NEXT_DELAY + player.random.nextInt(NEXT_SPREAD)
        val crowded = active.any { entry ->
            val other = level.getPlayerByUUID(entry.victim) ?: return@any false
            other.distanceToSqr(player) < SPACING.toDouble() * SPACING
        }
        if (!crowded) start(level, player)
    }

    private fun inform(level: ServerLevel) {
        for (entry in active) {
            val victim = level.getPlayerByUUID(entry.victim) ?: continue
            for (player in level.players()) {
                val dx = player.x - victim.x
                val dz = player.z - victim.z
                val distance = dx * dx + dz * dz
                if (distance > FORGET_RANGE.toDouble() * FORGET_RANGE) entry.informed.remove(player.uuid)
                if (distance > SEND_RANGE.toDouble() * SEND_RANGE || !entry.informed.add(player.uuid)) continue
                BlackoutSync.send(player, entry.event)
            }
        }
    }

    private fun mix(value: Long): Long {
        var z = value * -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }
}
