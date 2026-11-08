package xyz.thewhish.substratum.client

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.util.Mth
import xyz.thewhish.substratum.sanity.SanityPhase
import xyz.thewhish.substratum.sanity.SanityTracker

object SanityClient {

    private const val MAX_EXTRAPOLATION = SanityTracker.SYNC_INTERVAL * 2f
    private const val LAST_MINUTE = 60 * 20f

    private var ticks = 0
    private var level: ClientLevel? = null
    private var receivedAt = 0L

    fun accept(ticks: Int) {
        this.ticks = ticks
        level = Minecraft.getInstance().level
        receivedAt = level?.gameTime ?: 0L
    }

    private fun ticks(partialTick: Float): Float {
        val synced = level ?: return ticks.toFloat()
        if (ticks == 0 || Minecraft.getInstance().player?.level() !== synced) return ticks.toFloat()
        val elapsed = (synced.gameTime - receivedAt + partialTick).coerceIn(0f, MAX_EXTRAPOLATION)
        return ticks + elapsed
    }

    fun pressure(partialTick: Float): Float = SanityPhase.pressure(ticks(partialTick).toDouble()).toFloat()

    fun end(partialTick: Float): Float {
        val t = Mth.clamp((ticks(partialTick) - SanityTracker.LIMIT + LAST_MINUTE) / LAST_MINUTE, 0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun reset() {
        ticks = 0
        level = null
    }
}
