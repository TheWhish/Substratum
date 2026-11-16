package xyz.thewhish.substratum.client

import com.mojang.blaze3d.audio.Channel
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SoundInstance
import org.lwjgl.openal.AL10
import org.lwjgl.openal.ALC10
import org.lwjgl.openal.EXTEfx
import xyz.thewhish.substratum.mixin.ChannelAccessor
import xyz.thewhish.substratum.mixin.SoundEngineAccessor
import xyz.thewhish.substratum.mixin.SoundManagerAccessor

object Muffle {

    private const val GAIN_DROP = 0.45f
    private const val TREBLE_FLOOR = 0.03f

    private var applied = false
    private var filter = 0
    private var supported: Boolean? = null

    fun apply(minecraft: Minecraft, amount: Float, spared: (SoundInstance) -> Boolean) {
        if (amount <= 0f && !applied) return
        val engine = (minecraft.soundManager as SoundManagerAccessor).`substratum$engine`()
        val access = engine as SoundEngineAccessor
        if (!access.`substratum$loaded`()) return
        val gain = 1f - GAIN_DROP * amount
        val treble = TREBLE_FLOOR + (1f - TREBLE_FLOOR) * (1f - amount) * (1f - amount)
        for ((sound, handle) in access.`substratum$channels`()) {
            val clear = amount <= 0f || spared(sound)
            handle.execute { channel -> if (clear) detach(channel) else attach(channel, gain, treble) }
        }
        applied = amount > 0f
    }

    private fun attach(channel: Channel, gain: Float, treble: Float) {
        if (!ready()) return
        EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAIN, gain)
        EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAINHF, treble)
        AL10.alSourcei((channel as ChannelAccessor).`substratum$source`(), EXTEfx.AL_DIRECT_FILTER, filter)
    }

    private fun detach(channel: Channel) {
        if (supported != true) return
        AL10.alSourcei((channel as ChannelAccessor).`substratum$source`(), EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL)
    }

    private fun ready(): Boolean {
        val known = supported ?: ALC10.alcIsExtensionPresent(ALC10.alcGetContextsDevice(ALC10.alcGetCurrentContext()), "ALC_EXT_EFX").also { supported = it }
        if (!known) return false
        if (filter != 0 && EXTEfx.alIsFilter(filter)) return true
        filter = EXTEfx.alGenFilters()
        EXTEfx.alFilteri(filter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS)
        return true
    }
}
