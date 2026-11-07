package xyz.thewhish.substratum.client.menu

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class WaitScreen : Screen(Component.translatable("menu.substratum.loading")) {

    override fun shouldCloseOnEsc(): Boolean = false

    override fun isPauseScreen(): Boolean = false

    override fun renderBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        graphics.fill(0, 0, width, height, Menus.SKY)
    }
}
