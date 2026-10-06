package xyz.thewhish.substratum.client

import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import xyz.thewhish.substratum.level.SubstratumLevels
import xyz.thewhish.substratum.network.VhsSync
import xyz.thewhish.substratum.registry.ModSounds
import kotlin.random.Random

object Vhs {

    private const val LENGTH = VhsSync.TICKS
    private const val PITCH = 0.92f
    private const val PITCH_SPREAD = 0.16f
    private const val COARSE = 7
    private const val FINE = 64
    private const val COVER = 1.2f
    private val BARS = intArrayOf(0x8A1E8A, 0x1E8A8A, 0x2A8A2A, 0x8A8A8A, 0x1E1E8A)

    private val random = Random(0x5EED)

    private var age = LENGTH
    var seed = 0f
        private set

    fun burst() {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        if (level.dimension() != SubstratumLevels.LEVEL_0) return
        age = 0
        seed = random.nextFloat()
        val at = minecraft.gameRenderer.mainCamera.position
        level.playLocalSound(at.x, at.y, at.z, ModSounds.VHS_BURST.get(), SoundSource.AMBIENT, 1f, PITCH + random.nextFloat() * PITCH_SPREAD, false)
    }

    fun tick(minecraft: Minecraft) {
        if (minecraft.level?.dimension() != SubstratumLevels.LEVEL_0) return reset()
        if (age < LENGTH) age++
    }

    fun reset() {
        age = LENGTH
    }

    fun level(partialTick: Float): Float {
        if (age >= LENGTH) return 0f
        return Mth.clamp(LENGTH - age - partialTick, 0f, 1f)
    }

    fun shake(): Float = if (ClientConfig.motionSicknessSafe) 0f else 1f

    fun renderHud(graphics: GuiGraphics, delta: DeltaTracker) {
        if (Atmosphere.consumeComposited()) return
        val strength = level(delta.getGameTimeDeltaPartialTick(false))
        if (strength <= 0f) return
        val width = graphics.guiWidth()
        val height = graphics.guiHeight()
        val layout = Random((seed * Int.MAX_VALUE).toInt() + age)
        var y = 0
        while (y < height) {
            val rows = if (layout.nextBoolean()) height / COARSE else maxOf(1, height / FINE)
            val bottom = minOf(height, y + rows)
            if (layout.nextFloat() < strength * COVER) graphics.fill(0, y, width, bottom, band(layout))
            y = bottom
        }
    }

    private fun band(layout: Random): Int {
        val pick = layout.nextFloat()
        return when {
            pick < 0.55f -> 0xFF050505.toInt()
            pick < 0.85f -> (0xFF shl 24) or BARS[layout.nextInt(BARS.size)]
            else -> 0xFF6E6E6E.toInt()
        }
    }
}
