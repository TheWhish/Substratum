package xyz.thewhish.substratum.client.menu

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.GenericMessageScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.options.OptionsScreen
import net.minecraft.network.chat.Component

class PauseMenu : Screen(Component.translatable("menu.game")) {

    override fun init() {
        val left = width / 10
        val top = height / 2
        listOf(
            MenuButton(left, 0, Component.translatable("menu.substratum.continue"), ::onClose),
            MenuButton(left, 0, Component.translatable("menu.substratum.settings")) {
                minecraft!!.setScreen(OptionsScreen(this, minecraft!!.options))
            },
            MenuButton(left, 0, Component.translatable("menu.substratum.to_menu"), ::leave),
        ).forEachIndexed { index, button ->
            button.y = top + index * MenuButton.HEIGHT
            addRenderableWidget(button)
        }
    }

    override fun renderBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick)
        graphics.fill(0, 0, width, height, Menus.SHADE)
    }

    private fun leave() {
        val minecraft = minecraft!!
        val local = minecraft.isLocalServer
        Loading.begin()
        minecraft.level?.disconnect()
        if (local) {
            minecraft.disconnect(GenericMessageScreen(Component.translatable("menu.savingLevel")))
        } else {
            minecraft.disconnect()
        }
        minecraft.setScreen(MainMenu())
    }
}
