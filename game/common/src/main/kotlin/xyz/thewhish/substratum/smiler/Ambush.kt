package xyz.thewhish.substratum.smiler

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3

class Ambush(val smiler: Smiler, private val armedAt: Long) {

    private var endsAt = NEVER
    private var noticedSince = NEVER

    val armed: Boolean get() = endsAt == NEVER

    fun advance(stage: Stage): Boolean {
        if (smiler.isRemoved) return false
        if (!armed) {
            if (stage.now < endsAt) return true
            smiler.discard()
            return false
        }
        val feet = smiler.position()
        if (stage.now - armedAt > PATIENCE) {
            if (stage.hidden(feet, emptySet())) {
                smiler.discard()
                return false
            }
            fizzle(stage.now)
            return true
        }
        noticedSince = if (stage.noticed(feet)) minOf(noticedSince, stage.now) else NEVER
        if (stage.now - noticedSince > UNREPORTED) fizzle(stage.now)
        return true
    }

    fun spot(now: Long, player: ServerPlayer, eye: Vec3): ServerPlayer? {
        if (!armed || !player.isAlive || player.isSpectator || player.level() !== smiler.level()) return null
        if (eye.distanceToSqr(player.eyePosition) > TRUSTED_EYE * TRUSTED_EYE) return null
        val face = smiler.position().add(0.0, Smiler.FACE_CENTRE, 0.0)
        if (eye.distanceTo(face) > Corners.REVEAL_FAR + Smiler.FACE_HALF + LATE) {
            fizzle(now)
            return null
        }
        smiler.spring(player.id, eye)
        endsAt = now + smiler.hold() + (if (smiler.fake()) STARE else LUNGE) + DISSOLVE + LINGER
        return player
    }

    private fun fizzle(now: Long) {
        smiler.fizzle()
        endsAt = now + DISSOLVE + LINGER
    }

    companion object {
        const val LUNGE = 30
        const val STARE = 26
        const val DISSOLVE = 12
        val HOLD = 4..7
        const val FAKE_ODDS = 0.25
        private const val NEVER = Long.MAX_VALUE
        private const val PATIENCE = 25 * 20L
        private const val UNREPORTED = 30L
        private const val LINGER = 40L
        private const val TRUSTED_EYE = 3.0
        private const val LATE = 1.5
    }
}
