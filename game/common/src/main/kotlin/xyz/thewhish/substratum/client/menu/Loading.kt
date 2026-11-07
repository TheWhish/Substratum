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
import net.minecraft.util.FastColor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object Loading {
    private const val HOLD = 1500L
    private const val FADE = 1200L
    private const val LAYER = 1000f
    private const val PULSE = 600.0
    private const val MIN_TEXT_ALPHA = 8
    private val LABEL = Component.translatable("menu.substratum.loading")

    private var started = 0L
    private var seen = 0L

    fun begin() {
        started = Util.getMillis()
        seen = started
    }

    fun renderScreen(graphics: GuiGraphics, screen: Screen) {
        if (started == 0L) return
        if (covers(screen)) {
            seen = Util.getMillis()
            draw(graphics, 1f)
        } else if (Minecraft.getInstance().level == null) {
            fade(graphics)
        }
    }

    fun renderHud(graphics: GuiGraphics) {
        if (started != 0L) fade(graphics)
    }

    private fun covers(screen: Screen): Boolean =
        screen is LevelLoadingScreen || screen is ReceivingLevelScreen || screen is GenericMessageScreen ||
            screen is ProgressScreen || screen is ConnectScreen || screen is WaitScreen

    private fun fade(graphics: GuiGraphics) {
        val release = max(seen, started + HOLD)
        val left = 1f - (Util.getMillis() - release).toFloat() / FADE
        if (left <= 0f) {
            started = 0L
            return
        }
        draw(graphics, min(left, 1f))
    }

    private fun draw(graphics: GuiGraphics, opacity: Float) {
        val pose = graphics.pose()
        pose.pushPose()
        pose.translate(0f, 0f, LAYER)
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), FastColor.ARGB32.colorFromFloat(opacity, 0f, 0f, 0f))
        val pulse = 0.65f + 0.35f * sin(Util.getMillis() / PULSE).toFloat()
        val alpha = (opacity * pulse * 255).toInt()
        if (alpha >= MIN_TEXT_ALPHA) {
            val font = Minecraft.getInstance().font
            val y = (graphics.guiHeight() - font.lineHeight) / 2
            graphics.drawCenteredString(font, LABEL, graphics.guiWidth() / 2, y, FastColor.ARGB32.color(alpha, Menus.DIM))
        }
        pose.popPose()
    }
}
