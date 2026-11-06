package xyz.thewhish.substratum.smiler

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import xyz.thewhish.substratum.network.VhsSync
import xyz.thewhish.substratum.registry.ModEntities
import xyz.thewhish.substratum.sanity.SanityTracker
import java.util.UUID
import kotlin.random.Random

class Visit(
    val victim: UUID,
    val approach: Spots.Approach,
    private val band: IntRange,
    private val random: Random,
    private val from: Double,
) {
    var smiler: Smiler? = null
        private set
    private val stay = random.nextInt(STAY_MIN, STAY_MAX + 1)
    private val linger = random.nextInt(LINGER_MIN, LINGER_MAX + 1)
    private var cue: Cue? = null
    private var target: Vec3? = null
    private var sight = 0.0
    private var keep = 0.0
    private var stayUntil = NEVER
    private var leaveBy = NEVER
    private var answerBy = NEVER
    private val gone = HashSet<UUID>()

    fun arrive(stage: Stage, spot: Spots.Spot, loud: Boolean, victim: ServerPlayer) {
        sight = spot.sight
        if (!loud) return spawn(stage, spot.at, victim, loud = false)
        target = spot.at
        cue = stage.cue(listOf(spot.at), anyTurn = false)
    }

    fun lose(player: ServerPlayer) {
        val smiler = smiler ?: return
        if (smiler.leaving()) gone.add(player.uuid)
    }

    fun advance(stage: Stage, victim: ServerPlayer?): Boolean {
        val cue = cue
        if (cue != null) {
            this.cue = stage.hold(cue)
            if (this.cue == null) land(stage, victim)
            return this.cue != null || smiler != null
        }
        val smiler = smiler ?: return false
        if (smiler.isRemoved) return false
        val spot = smiler.position()
        if (!smiler.leaving()) {
            stay(stage, smiler, victim?.takeIf(::welcome))
            if (stage.now < leaveBy) return true
            smiler.leave()
            answerBy = stage.now + answer(stage)
        }
        if (stage.hidden(spot, gone)) {
            smiler.discard()
            this.smiler = null
            return false
        }
        if (stage.now >= answerBy) depart(stage, smiler)
        return true
    }

    private fun stay(stage: Stage, smiler: Smiler, present: ServerPlayer?) {
        if (present == null || approached(stage, smiler) || lost(present, smiler)) leaveBy = minOf(leaveBy, stage.now)
        leaveBy = minOf(leaveBy, stayUntil)
        if (!stage.noticed(smiler.position())) return
        leaveBy = minOf(leaveBy, stage.now + linger)
        if (random.nextDouble() < TWITCH) smiler.glitch(random.nextInt(TWITCH_MIN, TWITCH_MAX + 1))
    }

    private fun answer(stage: Stage): Long {
        val latency = stage.players.maxOfOrNull { it.connection.latency() } ?: 0
        return minOf(ANSWER + latency / MILLIS_PER_TICK, MAX_ANSWER)
    }

    private fun welcome(victim: ServerPlayer): Boolean =
        victim.isAlive && !victim.isSpectator && SanityTracker.pressure(victim) >= from

    private fun approached(stage: Stage, smiler: Smiler): Boolean {
        val near = maxOf(keep, Spots.CLOSEST)
        return stage.players.any { it.isAlive && !it.isSpectator && it.distanceToSqr(smiler) < near * near }
    }

    private fun lost(victim: ServerPlayer, smiler: Smiler): Boolean {
        val far = band.last + LOST_MARGIN
        return victim.distanceToSqr(smiler) > far * far
    }

    private fun depart(stage: Stage, smiler: Smiler) {
        smiler.glitch(VhsSync.TICKS)
        target = null
        cue = stage.cue(listOf(smiler.position()), anyTurn = true, spared = gone)
    }

    private fun land(stage: Stage, victim: ServerPlayer?) {
        val spot = target
        target = null
        if (spot == null) {
            smiler?.discard()
            smiler = null
        } else if (victim != null && welcome(victim)) {
            spawn(stage, spot, victim, loud = true)
        }
    }

    private fun spawn(stage: Stage, spot: Vec3, victim: ServerPlayer, loud: Boolean) {
        val entity = ModEntities.SMILER.get().create(stage.level) ?: error("the smiler entity type is disabled")
        entity.moveTo(spot.x, spot.y, spot.z, 0f, 0f)
        if (loud) entity.glitch(ARRIVAL_GLITCH)
        if (!stage.level.addFreshEntity(entity)) return
        smiler = entity
        stayUntil = stage.now + stay
        keep = minOf(sight, victim.position().distanceTo(spot)) * (1.0 - LEAVE_SHARE)
    }

    companion object {
        private const val SECOND = 20
        private const val STAY_MIN = 8 * SECOND
        private const val STAY_MAX = 20 * SECOND
        private const val LINGER_MIN = 6 * SECOND
        private const val LINGER_MAX = 8 * SECOND
        private const val LEAVE_SHARE = 0.25
        private const val ARRIVAL_GLITCH = 8
        private const val NEVER = Long.MAX_VALUE
        private const val ANSWER = 3L
        private const val MAX_ANSWER = 20L
        private const val MILLIS_PER_TICK = 50L
        private const val TWITCH = 0.01
        private const val TWITCH_MIN = 3
        private const val TWITCH_MAX = 6
        private const val LOST_MARGIN = 24.0
    }
}
