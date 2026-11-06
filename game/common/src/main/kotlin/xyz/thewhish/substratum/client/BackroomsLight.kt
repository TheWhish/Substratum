package xyz.thewhish.substratum.client

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.util.Mth
import net.minecraft.world.effect.MobEffects
import xyz.thewhish.substratum.level.SubstratumLevels

object BackroomsLight {

    private const val FLOOR = 0.025f
    private const val GAMMA_LIFT = 0.05f
    private const val DARK_EXPOSURE = 2.2f
    private const val ADAPT_TO_DARK = 1.2f / 280
    private const val ADAPT_TO_LIGHT = 1.2f / 20
    private const val ADAPTED_BELOW = 2f
    private const val UNADAPTED_ABOVE = 9f
    private const val NIGHT_VISION_FLOOR = 0.35f
    private const val FULL = 15

    private val WARM = floatArrayOf(1.0f, 0.97f, 0.88f)
    private val DIM = floatArrayOf(1.0f, 0.84f, 0.60f)

    private var exposure = 1f

    fun tick(minecraft: Minecraft) {
        val level = minecraft.level
        val player = minecraft.player
        if (level == null || player == null || level.dimension() != SubstratumLevels.LEVEL_0) {
            exposure = 1f
            return
        }
        val light = LightField.smoothLight(level, player.eyePosition).toFloat()
        val target = Mth.lerp(smoothstep(ADAPTED_BELOW, UNADAPTED_ABOVE, light), DARK_EXPOSURE, 1f)
        exposure = if (target > exposure) minOf(target, exposure + ADAPT_TO_DARK) else maxOf(target, exposure - ADAPT_TO_LIGHT)
    }

    @JvmStatic
    fun paint(minecraft: Minecraft, pixels: NativeImage, partialTick: Float): Boolean {
        val level = minecraft.level ?: return false
        if (level.dimension() != SubstratumLevels.LEVEL_0) return false
        val player = minecraft.player
        val vision = if (player != null && player.hasEffect(MobEffects.NIGHT_VISION)) GameRenderer.getNightVisionScale(player, partialTick) else 0f
        for (block in 0 until 16) {
            val pool = pool(block.toFloat())
            val seen = brightness(minecraft, block.toFloat())
            val lamp = Mth.lerp(vision, seen, maxOf(seen, NIGHT_VISION_FLOOR + (1f - NIGHT_VISION_FLOOR) * pool))
            for (sky in 0 until 16) {
                val colour = if (block == FULL && sky == FULL) -1 else rgba(maxOf(lamp, sky * sky / 225f))
                pixels.setPixelRGBA(block, sky, colour)
            }
        }
        return true
    }

    fun brightness(minecraft: Minecraft, light: Float): Float {
        val floor = (FLOOR + GAMMA_LIFT * minecraft.options.gamma().get().toFloat().coerceIn(0f, 1f)) * exposure
        return floor + (1f - floor) * pool(light)
    }

    private fun pool(light: Float): Float {
        val lift = ((light - Irradiance.DARK) / (Irradiance.BRIGHT - Irradiance.DARK)).coerceIn(0f, 1f)
        return lift * lift
    }

    fun tinted(brightness: Float, index: Int): Float =
        Mth.clamp(brightness * Mth.lerp(smoothstep(0.1f, 0.8f, brightness), DIM[index], WARM[index]), 0f, 1f)

    private fun rgba(brightness: Float): Int {
        val r = (tinted(brightness, 0) * 255f).toInt()
        val g = (tinted(brightness, 1) * 255f).toInt()
        val b = (tinted(brightness, 2) * 255f).toInt()
        return (0xFF shl 24) or (b shl 16) or (g shl 8) or r
    }

    private fun smoothstep(from: Float, to: Float, value: Float): Float {
        val t = Mth.clamp((value - from) / (to - from), 0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
