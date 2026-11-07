package xyz.thewhish.substratum.client.menu

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component

class MenuButton(
    x: Int,
    y: Int,
    message: Component,
    private val press: () -> Unit,
) : AbstractButton(x, y, WIDTH, HEIGHT, message) {

    override fun onPress() = press()

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val font = Minecraft.getInstance().font
        val lit = active && isHoveredOrFocused
        val color = when {
            !active -> Menus.MUTE
            lit -> Menus.TEXT
            else -> Menus.DIM
        }
        if (lit) graphics.fill(x, y + 4, x + 2, y + height - 4, Menus.TEXT)
        graphics.drawString(font, message, x + INDENT, y + (height - font.lineHeight) / 2 + 1, color, false)
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)

    companion object {
        const val WIDTH = 200
        const val HEIGHT = 20
        private const val INDENT = 10
    }
}
