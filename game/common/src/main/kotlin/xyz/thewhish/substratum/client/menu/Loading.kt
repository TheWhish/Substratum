package xyz.thewhish.substratum.client.menu

import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.GenericMessageScreen
import net.minecraft.client.gui.screens.LevelLoadingScreen
import net.minecraft.client.gui.screens.ProgressScreen
import net.minecraft.client.gui.screens.ReceivingLevelScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundSource
import net.minecraft.util.FastColor
import net.minecraft.util.Mth
import xyz.thewhish.substratum.mixin.SoundEngineAccessor
import xyz.thewhish.substratum.mixin.SoundManagerAccessor
import kotlin.math.max
import kotlin.math.sin

object Loading {
    private const val HOLD = 1500L
    private const val FADE = 1200L
    private const val AWAKE = 4000L
    private const val LAYER = 1000f
    private const val PULSE = 600.0
    private const val MIN_TEXT_ALPHA = 8
    private val LABEL = Component.translatable("menu.substratum.loading")

    private var started = 0L
    private var seen = 0L
    private var labelled = true

    fun begin(label: Boolean = true) {
        labelled = label
        started = Util.getMillis()
        seen = started
    }

    fun renderScreen(graphics: GuiGraphics, screen: Screen) {
        if (started == 0L) return
        val minecraft = Minecraft.getInstance()
        if (covers(screen)) {
            seen = Util.getMillis()
            if (minecraft.level != null) listen(minecraft, 0f)
            draw(graphics, 1f)
        } else if (minecraft.level == null) {
            fade(graphics, FADE)
            listen(minecraft, 1f)
        }
    }

    fun renderHud(graphics: GuiGraphics) {
        if (started == 0L) return
        val opacity = fade(graphics, AWAKE)
        listen(Minecraft.getInstance(), 1f - opacity)
    }

    private fun covers(screen: Screen): Boolean =
        screen is LevelLoadingScreen || screen is ReceivingLevelScreen || screen is GenericMessageScreen ||
            screen is ProgressScreen || screen is ConnectScreen || screen is WaitScreen

    private fun fade(graphics: GuiGraphics, length: Long): Float {
        val release = max(seen, started + HOLD)
        val progress = (Util.getMillis() - release).toFloat() / length
        if (progress >= 1f) {
            started = 0L
            return 0f
        }
        val opacity = 1f - Mth.smoothstep(progress.coerceAtLeast(0f).toDouble()).toFloat()
        draw(graphics, opacity)
        return opacity
    }

    private fun listen(minecraft: Minecraft, volume: Float) {
        val engine = (minecraft.soundManager as SoundManagerAccessor).`substratum$engine`() as SoundEngineAccessor
        if (!engine.`substratum$loaded`()) return
        engine.`substratum$listener`().setGain(minecraft.options.getSoundSourceVolume(SoundSource.MASTER) * volume)
    }

    private fun draw(graphics: GuiGraphics, opacity: Float) {
        val pose = graphics.pose()
        pose.pushPose()
        pose.translate(0f, 0f, LAYER)
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), FastColor.ARGB32.colorFromFloat(opacity, 0f, 0f, 0f))
        val pulse = 0.65f + 0.35f * sin(Util.getMillis() / PULSE).toFloat()
        val alpha = (opacity * pulse * 255).toInt()
        if (labelled && alpha >= MIN_TEXT_ALPHA) {
            val font = Minecraft.getInstance().font
            val y = (graphics.guiHeight() - font.lineHeight) / 2
            graphics.drawCenteredString(font, LABEL, graphics.guiWidth() / 2, y, FastColor.ARGB32.color(alpha, Menus.DIM))
        }
        pose.popPose()
    }
}
