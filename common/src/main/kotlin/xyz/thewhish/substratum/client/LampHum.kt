package xyz.thewhish.substratum.client

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import xyz.thewhish.substratum.registry.ModBlocks
import xyz.thewhish.substratum.registry.ModBlocks.LampCondition
import xyz.thewhish.substratum.registry.ModSounds

object LampHum {

    private const val RANGE = 16
    private const val MAX_EMITTERS = 6
    private const val SCAN_INTERVAL = 10
    private const val FADE_TICKS = 20

    private enum class Tone { MAINS, DIM }

    private val emitters = HashMap<BlockPos, Emitter>()
    private var level: ClientLevel? = null
    private var ticks = 0

    fun tick(minecraft: Minecraft) {
        val current = minecraft.level
        val player = minecraft.player
        if (current !== level || player == null) {
            stopAll(minecraft)
            level = current
        }
        if (current == null || player == null || ticks++ % SCAN_INTERVAL != 0) return
        val wanted = nearestLamps(current, player.eyePosition)
        emitters.values.removeIf { it.isStopped || !minecraft.soundManager.isActive(it) }
        emitters.forEach { (pos, emitter) -> emitter.keep = wanted[pos] == emitter.tone }
        for ((pos, tone) in wanted) {
            if (pos in emitters) continue
            val emitter = Emitter(pos, tone)
            emitters[pos] = emitter
            minecraft.soundManager.play(emitter)
        }
    }

    private fun stopAll(minecraft: Minecraft) {
        emitters.values.forEach(minecraft.soundManager::stop)
        emitters.clear()
    }

    private fun tone(state: BlockState): Tone? {
        if (!state.`is`(ModBlocks.lampBlock)) return null
        return when (state.getValue(ModBlocks.LAMP_CONDITION)) {
            LampCondition.ON, LampCondition.FLICKERING, LampCondition.FLICKERING_OFF -> Tone.MAINS
            LampCondition.DIM -> Tone.DIM
            else -> null
        }
    }

    private fun nearestLamps(level: ClientLevel, eye: Vec3): Map<BlockPos, Tone> {
        val center = BlockPos.containing(eye)
        return scanBlocks(level, center, RANGE) { tone(it) != null }
            .sortedBy { it.distSqr(center) }
            .take(MAX_EMITTERS)
            .associateWith { tone(level.getBlockState(it))!! }
    }

    private fun sound(tone: Tone): SoundEvent = when (tone) {
        Tone.MAINS -> ModSounds.HUM_60HZ.get()
        Tone.DIM -> ModSounds.LAMP_BUZZ.get()
    }

    private class Emitter(private val pos: BlockPos, val tone: Tone) :
        AbstractTickableSoundInstance(sound(tone), SoundSource.BLOCKS, SoundInstance.createUnseededRandom()) {

        var keep = true
        private var cut = false

        init {
            looping = true
            delay = 0
            volume = 0f
            x = pos.x + 0.5
            y = pos.y + 0.5
            z = pos.z + 0.5
        }

        override fun canStartSilent(): Boolean = true

        override fun tick() {
            val state = Minecraft.getInstance().level?.getBlockState(pos)
            if (keep && (state == null || tone(state) != tone)) keep = false
            if (!keep) {
                volume -= 1f / FADE_TICKS
                if (volume <= 0f) stop()
                return
            }
            if (state?.getValue(ModBlocks.LAMP_CONDITION)?.lit != true) {
                volume = 0f
                cut = true
            } else if (cut) {
                volume = 1f
                cut = false
            } else {
                volume = (volume + 1f / FADE_TICKS).coerceAtMost(1f)
            }
        }
    }
}
