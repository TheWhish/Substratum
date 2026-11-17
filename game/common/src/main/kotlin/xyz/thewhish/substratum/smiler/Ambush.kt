package xyz.thewhish.substratum.smiler

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import xyz.thewhish.substratum.level.Run
import xyz.thewhish.substratum.sanity.SanityTracker

class Ambush(val smiler: Smiler, private val armedAt: Long) {

    private var endsAt = NEVER
    private var victim: ServerPlayer? = null
    private var strikeAt = NEVER
    private var closest = Double.MAX_VALUE

    val armed: Boolean get() = endsAt == NEVER

    fun advance(stage: Stage): Boolean {
        if (smiler.isRemoved) return false
        if (!armed) {
            victim?.takeIf { stage.now >= strikeAt }?.let(::strike)
            if (stage.now < endsAt) return true
            smiler.discard()
            return false
        }
        val feet = smiler.position()
        val nearest = stage.players.filter(::prey).minOfOrNull { it.position().distanceTo(feet) } ?: Double.MAX_VALUE
        closest = minOf(closest, nearest)
        if (nearest > closest + PASSED && !stage.noticed(feet)) {
            smiler.discard()
            return false
        }
        if (stage.now - armedAt <= PATIENCE) return true
        if (stage.hidden(feet, emptySet())) {
            smiler.discard()
            return false
        }
        smiler.fizzle()
        endsAt = stage.now + DISSOLVE + LINGER
        return true
    }

    fun spring(now: Long, player: ServerPlayer, eye: Vec3): Boolean {
        if (!armed || !prey(player) || player.level() !== smiler.level()) return false
        smiler.spring(player.id, eye, SanityTracker.LIMIT - SanityTracker.ticks(player) <= SHAKEN + LAST_BREATH)
        victim = player
        strikeAt = now + smiler.hold() + LUNGE + LATE_STRIKE
        endsAt = now + smiler.hold() + LUNGE + DISSOLVE + LINGER
        return true
    }

    fun strike(player: ServerPlayer) {
        if (player !== victim) return
        victim = null
        if (prey(player)) SanityTracker.set(player, if (smiler.lethal()) SanityTracker.LIMIT else SanityTracker.ticks(player) + SHAKEN)
    }

    companion object {
        const val LUNGE = 30
        const val DISSOLVE = 12
        val HOLD = 2..2
        private const val SHAKEN = 5000
        private const val LAST_BREATH = 200
        private const val NEVER = Long.MAX_VALUE
        private const val PATIENCE = 25 * 20L
        private const val PASSED = 3.0
        private const val LATE_STRIKE = 20L
        private const val LINGER = 40L

        fun prey(player: ServerPlayer): Boolean = player.isAlive && !player.isSpectator && !Run.falling(player)
    }
}
