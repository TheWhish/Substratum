package xyz.thewhish.substratum.client.menu

import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component
import net.minecraft.util.FastColor
import kotlin.math.exp

class MenuButton(
    x: Int,
    y: Int,
    message: Component,
    private val press: () -> Unit,
) : AbstractButton(x, y, WIDTH, HEIGHT, message) {
    private var focus = 0f
    private var flash = 0f
    private var last = Util.getMillis()

    override fun onPress() {
        flash = 1f
        press()
    }

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val font = Minecraft.getInstance().font
        val now = Util.getMillis()
        val elapsed = (now - last).coerceIn(0L, 100L).toFloat()
        last = now
        focus += ((if (active && isHoveredOrFocused) 1f else 0f) - focus) * (1f - exp(-elapsed / FOCUS_MS))
        flash *= exp(-elapsed / FLASH_MS)
        if (!active) {
            val scale = textScale()
            val pose = graphics.pose()
            pose.pushPose()
            pose.translate(x + INDENT.toFloat(), y + (height - GLYPH * scale) / 2f, 0f)
            pose.scale(scale, scale, 1f)
            graphics.drawString(font, message, 0, 0, Menus.MUTE, false)
            pose.popPose()
            return
        }
        val band = ((focus * BAND_ALPHA + flash * FLASH_ALPHA).toInt().coerceAtMost(255) shl 24) or LAMP
        val scale = textScale()
        val reach = x + INDENT * 2 + (font.width(message) * scale).toInt() + (SLIDE * focus).toInt()
        if (band ushr 24 > 0) graphics.fill(x, y + 1, reach, y + height - 1, band)
        val bar = ((height - 6) * focus / 2f).toInt()
        if (bar > 0) graphics.fill(x, y + height / 2 - bar, x + 2, y + height / 2 + bar, Menus.TEXT)
        val pose = graphics.pose()
        pose.pushPose()
        pose.translate(x + INDENT + SLIDE * focus, y + (height - GLYPH * scale) / 2f, 0f)
        pose.scale(scale, scale, 1f)
        graphics.drawString(font, message, 0, 0, FastColor.ARGB32.lerp(focus, Menus.DIM, Menus.TEXT), false)
        pose.popPose()
    }

    private fun textScale(): Float {
        val gui = Minecraft.getInstance().window.guiScale.toFloat()
        return (gui + 1f) / gui
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)

    companion object {
        const val WIDTH = 200
        const val HEIGHT = 26
        private const val GLYPH = 7
        private const val INDENT = 10
        private const val SLIDE = 5f
        private const val FOCUS_MS = 70f
        private const val FLASH_MS = 160f
        private const val BAND_ALPHA = 22f
        private const val FLASH_ALPHA = 60f
        private const val LAMP = 0xD8C27A
    }
}
