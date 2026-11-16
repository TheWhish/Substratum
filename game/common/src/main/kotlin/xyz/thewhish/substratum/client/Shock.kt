package xyz.thewhish.substratum.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.phys.Vec3
import xyz.thewhish.substratum.level.SubstratumLevels
import xyz.thewhish.substratum.registry.ModSounds
import xyz.thewhish.substratum.smiler.client.Lunge
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

object Shock {

    private const val END = 240f
    private const val SETTLE = 90f
    private const val FADE_FROM = 170f
    private const val DARK_RISE = 1.3f
    private const val DARK_HOLD = 3f
    private const val DARK_FADE = 8f
    private const val DARK_END = 40f
    private const val REST_BPM = 78f
    private const val RACE_BPM = 60f
    private const val RACE_FADE = 110f
    private const val BEAT_RISE = 0.06f
    private const val SECOND_BEAT = 0.32f
    private const val SECOND_WEIGHT = 0.6f
    private const val FIRST_BEAT = 5f
    private const val BEAT_LEAD = 0.5f
    private const val BEAT_EASE = 4f
    private const val TENSION_FADE = 4f
    private const val BRACED = 0.001f
    private const val SLOWED = 0.5f
    private const val HEART_GAIN = 1.3f
    private const val PACE_EASE = 0.7f
    private const val EARLY_RISE = 0.8f
    private const val EARLY_FADE = 30f
    private const val EARLY_END = 80f
    private const val EARLY_TREMOR = 0.8f
    private const val RUSH_BLUR = 0.05f
    private const val HIT_BLUR = 0.1f
    private const val BLUR_FADE = 6f
    private const val BLEED = 0.9f
    private const val QUIET_HEART = 0.08f
    private const val BREATH_FROM = 4f
    private const val BREATH = 1f
    private const val RING = 0.5f
    private const val RING_RISE = 3f
    private const val RING_FADE = 45f
    private const val RING_END = 150f
    private const val DEAF_HOLD = 16f
    private const val DEAF_FADE = 30f
    private const val DEAF_END = 140f
    private const val DOLLY = 7.0
    private const val DOLLY_RETURN = 5f
    private const val THROB = 0.5
    private const val TREMOR = 1.1f
    private const val JOLT = 2.4f
    private const val JOLT_FADE = 2.5f
    private const val JOLT_PERIOD = 2.3f
    private const val KICK_PITCH = 7f
    private const val KICK_YAW = 2.5f
    private const val PUSH = 0.24
    private const val PUSH_PROBE = 0.9
    private const val HAND_DROP = 0.07f
    private const val HAND_BACK = 0.06f
    private const val HAND_TIP = 10f

    private val random = RandomSource.create()
    private val phases = FloatArray(5)

    private var victim = -1
    private var lunge = Float.NaN
    private var impact = Float.NaN
    private var owner = false
    private var away = Vec3.ZERO
    private var side = 1f
    private var pushed = false
    private var beats = 0
    private var kickPitch = 0f
    private var kickYaw = 0f
    private var breath: Fading? = null
    private var ringing: Fading? = null

    private class Fading(event: SoundEvent, private val level: () -> Float) :
        AbstractTickableSoundInstance(event, SoundSource.PLAYERS, SoundInstance.createUnseededRandom()) {

        init {
            relative = true
            attenuation = SoundInstance.Attenuation.NONE
            volume = level()
        }

        override fun tick() {
            val next = level()
            if (next < 0f) stop() else volume = next
        }

        override fun canStartSilent(): Boolean = true

        fun end() = stop()
    }

    fun begin(victim: Int, lunge: Float, from: Vec3, owner: Boolean) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        stopSounds()
        this.victim = victim
        this.lunge = lunge
        impact = Float.NaN
        this.owner = owner
        val body = minecraft.cameraEntity ?: player
        away = Vec3(body.x - from.x, 0.0, body.z - from.z).let { if (it.lengthSqr() < 1.0E-6) Vec3.ZERO else it.normalize() }
        val look = body.lookAngle
        side = if (look.x * away.z - look.z * away.x >= 0) 1f else -1f
        pushed = false
        beats = 0
        kickPitch = 0f
        kickYaw = 0f
        for (i in phases.indices) phases[i] = random.nextFloat() * 2f * PI.toFloat()
    }

    fun hit(at: Float) {
        if (lunge.isNaN()) return
        impact = at
        play(ModSounds.SMILER_HIT.get(), SoundSource.HOSTILE, 1f)
    }

    fun reset() {
        stopSounds()
        lunge = Float.NaN
        impact = Float.NaN
        victim = -1
        Muffle.apply(Minecraft.getInstance(), 0f) { false }
    }

    private fun active(minecraft: Minecraft): Boolean =
        !lunge.isNaN() && minecraft.cameraEntity?.id == victim && minecraft.level?.dimension() == SubstratumLevels.LEVEL_0

    private fun time(partialTick: Float): Float {
        val now = Lunge.now(partialTick)
        return if (impact.isNaN()) minOf(now - lunge - Lunge.LENGTH, -BRACED) else now - impact
    }

    fun tick(minecraft: Minecraft) {
        if (lunge.isNaN()) return
        val t = time(0f)
        if (!active(minecraft) || t > END) {
            Muffle.apply(minecraft, 0f) { false }
            if (t > END || minecraft.cameraEntity?.id != victim) reset()
            return
        }
        Muffle.apply(minecraft, deafness(t), ::spared)
        if (t < 0f) return
        push(minecraft)
        if (t >= BREATH_FROM && breath == null) breath = Fading(ModSounds.SHOCK_BREATH.get()) { if (active(minecraft)) BREATH else -1f }.also(::start)
        if (ringing == null) ringing = Fading(ModSounds.SHOCK_RINGING.get()) { if (active(minecraft)) ring(time(0f)) else -1f }.also(::start)
        val beat = floor(phase(t + BEAT_LEAD)).toInt()
        if (t >= FIRST_BEAT && beat > beats) {
            val loud = minOf(1f, HEART_GAIN * maxOf(shock(t).pow(0.8f), early(t)))
            if (loud > QUIET_HEART) play(SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, loud)
        }
        beats = maxOf(beats, beat)
    }

    private fun push(minecraft: Minecraft) {
        if (!owner || pushed) return
        pushed = true
        val player = minecraft.player ?: return
        if (away == Vec3.ZERO) return
        val level = player.level()
        val ground = BlockPos.containing(player.x + away.x * PUSH_PROBE, player.y - 0.5, player.z + away.z * PUSH_PROBE)
        if (level.getBlockState(ground).getCollisionShape(level, ground).isEmpty) return
        player.addDeltaMovement(away.scale(PUSH))
    }

    @JvmStatic
    fun turn() {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        if (!owner || !active(minecraft)) return
        val t = time(minecraft.timer.getGameTimeDeltaPartialTick(false))
        val kick = if (ClientConfig.motionSicknessSafe) 0f else Lunge.recoil(t) * ClientConfig.intensity
        val pitch = -KICK_PITCH * kick - kickPitch
        val yaw = KICK_YAW * side * kick - kickYaw
        if (pitch == 0f && yaw == 0f) return
        kickPitch += pitch
        kickYaw += yaw
        player.xRot = Mth.clamp(player.xRot + pitch, -90f, 90f)
        player.xRotO = Mth.clamp(player.xRotO + pitch, -90f, 90f)
        player.yRot += yaw
        player.yRotO += yaw
    }

    @JvmStatic
    fun pace(): Float {
        val minecraft = Minecraft.getInstance()
        if (!owner || !active(minecraft)) return 1f
        return 1f - SLOWED * shock(time(0f)).pow(PACE_EASE)
    }

    @JvmStatic
    fun fovOffset(partialTick: Float): Double {
        val minecraft = Minecraft.getInstance()
        if (!active(minecraft) || ClientConfig.motionSicknessSafe) return 0.0
        val t = time(partialTick)
        val dolly = if (t < 0f) approach(t).let { it * it * it } else exp(-t / DOLLY_RETURN)
        return -(DOLLY * dolly + THROB * pulse(t) * (shock(t) + early(t))) * ClientConfig.intensity
    }

    @JvmStatic
    fun shake(pose: PoseStack, partialTick: Float) {
        val minecraft = Minecraft.getInstance()
        if (!active(minecraft) || ClientConfig.motionSicknessSafe) return
        val t = time(partialTick)
        if (t < 0f) return
        val tremor = TREMOR * (shock(t).pow(1.5f) + EARLY_TREMOR * early(t))
        val jolt = JOLT * exp(-t / JOLT_FADE)
        val beat = 2f * PI.toFloat() * t / JOLT_PERIOD
        val pitch = tremor * (0.55f * wave(0.07f, t, 0) + 0.45f * wave(0.19f, t, 1)) + jolt * sin(beat)
        val yaw = tremor * (0.5f * wave(0.055f, t, 2) + 0.5f * wave(0.16f, t, 3)) + 0.5f * jolt * sin(beat + 1.1f)
        val roll = 0.7f * tremor * wave(0.045f, t, 4) + 0.6f * jolt * sin(beat + 2f)
        val scale = ClientConfig.intensity
        pose.mulPose(Axis.ZP.rotationDegrees(roll * scale))
        pose.mulPose(Axis.XP.rotationDegrees(pitch * scale))
        pose.mulPose(Axis.YP.rotationDegrees(yaw * scale))
    }

    @JvmStatic
    fun hands(pose: PoseStack, partialTick: Float) {
        val minecraft = Minecraft.getInstance()
        if (!active(minecraft)) return
        val kick = Lunge.recoil(time(partialTick))
        if (kick <= 0f) return
        pose.translate(0f, -HAND_DROP * kick, HAND_BACK * kick)
        pose.mulPose(Axis.XP.rotationDegrees(HAND_TIP * kick))
    }

    fun faint(partialTick: Float): Float = effect(partialTick) { t -> maxOf(dark(t), 0.5f * early(t) + 0.12f * shock(t)) }

    fun tunnel(partialTick: Float): Float = effect(partialTick) { t -> 0.35f * tension(t) + 0.35f * early(t) + shock(t) * (0.2f + 0.35f * pulse(t)) }

    fun drain(partialTick: Float): Float = effect(partialTick) { t -> 0.45f * shock(t) + 0.4f * dark(t) + 0.2f * early(t) }

    fun aberration(partialTick: Float): Float =
        effect(partialTick) { t -> 1.2f * tension(t) + 2.2f * dark(t) + 2f * early(t) + 0.9f * shock(t) * (1f + 0.4f * pulse(t)) }

    fun ghost(partialTick: Float): Float = effect(partialTick) { t -> 0.9f * early(t) + 0.4f * shock(t) * shock(t) }

    fun blur(partialTick: Float): Float = effect(partialTick) { t -> if (t < 0f) RUSH_BLUR * approach(t).pow(3) else HIT_BLUR * exp(-t / BLUR_FADE) }

    fun bleed(partialTick: Float): Float = effect(partialTick) { t -> BLEED * early(t) * (0.7f + 0.3f * pulse(t)) }

    fun grain(partialTick: Float): Float = effect(partialTick) { t -> 0.8f * shock(t) }

    private inline fun effect(partialTick: Float, value: (Float) -> Float): Float {
        if (MenuShot.busy || !active(Minecraft.getInstance())) return 0f
        return value(time(partialTick))
    }

    private fun approach(t: Float): Float = if (t >= 0f) 0f else Mth.clamp(1f + t / Lunge.LENGTH, 0f, 1f)

    private fun tension(t: Float): Float = if (t < 0f) approach(t).let { it * it } else exp(-t / TENSION_FADE)

    private fun dark(t: Float): Float = when {
        t < 0f -> 0f
        t < DARK_HOLD -> smoothstep(0f, DARK_RISE, t)
        else -> exp(-(t - DARK_HOLD) / DARK_FADE) * (1f - smoothstep(DARK_END * 0.7f, DARK_END, t))
    }

    private fun early(t: Float): Float = if (t < 0f) 0f else smoothstep(0f, EARLY_RISE, t) * exp(-t / EARLY_FADE) * (1f - smoothstep(EARLY_END * 0.6f, EARLY_END, t))

    private fun shock(t: Float): Float = if (t < 0f) 0f else smoothstep(0f, 2f, t) * exp(-t / SETTLE) * (1f - smoothstep(FADE_FROM, END, t))

    private fun deafness(t: Float): Float = when {
        t < 0f -> 0f
        t < DEAF_HOLD -> smoothstep(0f, 1f, t)
        else -> exp(-(t - DEAF_HOLD) / DEAF_FADE) * (1f - smoothstep(DEAF_END * 0.7f, DEAF_END, t))
    }

    private fun ring(t: Float): Float = if (t > RING_END) -1f else RING * smoothstep(0f, RING_RISE, t) * exp(-t / RING_FADE)

    private fun phase(t: Float): Float = (REST_BPM * t + RACE_BPM * RACE_FADE * (1f - exp(-t / RACE_FADE))) / 1200f

    private fun pulse(t: Float): Float {
        if (t < FIRST_BEAT) return 0f
        val f = phase(t) % 1f
        return smoothstep(FIRST_BEAT, FIRST_BEAT + BEAT_EASE, t) * (beat(f) + SECOND_WEIGHT * beat(f - SECOND_BEAT))
    }

    private fun beat(x: Float): Float = if (x <= 0f) 0f else x / BEAT_RISE * exp(1f - x / BEAT_RISE)

    private fun wave(rate: Float, t: Float, index: Int): Float = sin(2f * PI.toFloat() * rate * t + phases[index])

    private fun spared(sound: SoundInstance): Boolean {
        val id = sound.location
        return id == ModSounds.SMILER_RUSH.id || id == ModSounds.SMILER_HIT.id || id == ModSounds.SHOCK_BREATH.id || id == ModSounds.SHOCK_RINGING.id || id == SoundEvents.WARDEN_HEARTBEAT.location
    }

    private fun play(event: SoundEvent, source: SoundSource, volume: Float) {
        Minecraft.getInstance().soundManager.play(
            SimpleSoundInstance(event.location, source, volume, 1f, SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true)
        )
    }

    private fun start(sound: Fading) = Minecraft.getInstance().soundManager.play(sound)

    private fun stopSounds() {
        breath?.end()
        ringing?.end()
        breath = null
        ringing = null
    }

    private fun smoothstep(from: Float, to: Float, value: Float): Float {
        val t = Mth.clamp((value - from) / (to - from), 0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
