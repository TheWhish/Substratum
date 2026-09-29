package xyz.thewhish.substratum.client

import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import xyz.thewhish.substratum.level.SubstratumLevels
import xyz.thewhish.substratum.sanity.SanityPhase
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

object SanityEffects {

    private const val SETTLE = 0.02f
    private const val BREATH_TICKS = 11 * 20.0
    private const val FOV_SWING = 2.0
    private const val ABERRATION = 1.6f
    private const val ABERRATION_BREATH = 0.3f
    private const val GLARE = 0.9f
    private const val TUNNEL = 0.55f
    private const val TUNNEL_IN_LIGHT = 0.4f
    private const val DRAIN = 0.15f
    private const val CLOSE_IN = 0.25f

    private const val PULSE_RISE = 5f
    private const val PULSE_FADE = 16f
    private const val PULSE_END = PULSE_RISE + PULSE_FADE * 5
    private const val FIRST_PULSE = 8 * 20
    private const val PULSE_MIN = 18 * 20
    private const val PULSE_SPREAD = 22 * 20
    private const val PULSE_FOV = -3.0
    private const val PULSE_ABERRATION = 2.2f
    private const val PULSE_GHOST = 1f
    private const val PULSE_TUNNEL = 0.45f
    private const val PULSE_GLARE = 0.8f
    private const val PULSE_DRAIN = 0.25f

    private val random = RandomSource.create()

    private var dread = 0f
    private var dreadO = 0f
    private var dark = 0f
    private var clock = 0L
    private var pulseAge = -1
    private var nextPulse = FIRST_PULSE

    fun tick(minecraft: Minecraft) {
        clock++
        dreadO = dread
        val level = minecraft.level?.takeIf { it.dimension() == SubstratumLevels.LEVEL_0 }
        val player = minecraft.player
        val inside = level != null && player != null
        dread += ((if (inside) phaseRamp() else 0f) - dread) * SETTLE
        if (level != null && player != null) dark = 1f - smoothstep(3f, 10f, LightField.smoothLight(level, player.eyePosition).toFloat())
        if (pulseAge >= 0 && ++pulseAge > PULSE_END) pulseAge = -1
        if (!inside || SanityClient.phase != SanityPhase.HUNT) {
            nextPulse = FIRST_PULSE
            return
        }
        if (--nextPulse > 0) return
        startPulse()
        nextPulse = PULSE_MIN + random.nextInt(PULSE_SPREAD)
    }

    private fun startPulse() {
        pulseAge = 0
    }

    @JvmStatic
    fun fovOffset(partialTick: Float): Double {
        if (ClientConfig.motionSicknessSafe) return 0.0
        val dread = dread(partialTick)
        val pulse = pulse(partialTick)
        if (dread <= 0.001f && pulse <= 0f) return 0.0
        return (FOV_SWING * dread * breath(partialTick) + PULSE_FOV * pulse) * ClientConfig.intensity
    }

    fun aberration(partialTick: Float): Float =
        dread(partialTick) * ABERRATION * (1f + ABERRATION_BREATH * breath(partialTick).toFloat()) + PULSE_ABERRATION * pulse(partialTick)

    fun ghost(partialTick: Float): Float = PULSE_GHOST * pulse(partialTick)

    fun glare(partialTick: Float): Float = GLARE * dread(partialTick) + PULSE_GLARE * pulse(partialTick)

    fun tunnel(partialTick: Float): Float =
        TUNNEL * dread(partialTick) * Mth.lerp(dark, TUNNEL_IN_LIGHT, 1f) + PULSE_TUNNEL * pulse(partialTick)

    fun drain(partialTick: Float): Float = DRAIN * dread(partialTick) + PULSE_DRAIN * pulse(partialTick)

    fun grain(partialTick: Float): Float = dread(partialTick)

    fun closeIn(partialTick: Float): Float = CLOSE_IN * dread(partialTick) * ClientConfig.intensity

    private fun dread(partialTick: Float): Float = Mth.lerp(partialTick, dreadO, dread)

    private fun breath(partialTick: Float): Double = sin(2 * PI * (clock + partialTick) / BREATH_TICKS)

    private fun pulse(partialTick: Float): Float {
        if (pulseAge < 0) return 0f
        val age = pulseAge + partialTick
        return if (age < PULSE_RISE) smoothstep(0f, PULSE_RISE, age) else exp(-(age - PULSE_RISE) / PULSE_FADE)
    }

    private fun phaseRamp(): Float = when (SanityClient.phase) {
        SanityPhase.QUIET -> 0f
        SanityPhase.HUNT -> 1f
        SanityPhase.DISORIENTATION -> {
            val start = SanityPhase.DISORIENTATION.startTick
            ((SanityClient.ticks(0f) - start) / (SanityPhase.HUNT.startTick - start)).coerceIn(0f, 1f)
        }
    }

    private fun smoothstep(from: Float, to: Float, value: Float): Float {
        val t = Mth.clamp((value - from) / (to - from), 0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
