package xyz.thewhish.substratum.client.menu

import dev.architectury.event.CompoundEventResult
import dev.architectury.event.events.client.ClientGuiEvent
import net.minecraft.client.gui.components.Button
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.DisconnectedScreen
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.options.OptionsScreen
import net.minecraft.network.chat.Component
import net.minecraft.sounds.Music
import qouteall.imm_ptl.core.miscellaneous.IPortalInitialScreen
import xyz.thewhish.substratum.client.Lobby
import xyz.thewhish.substratum.registry.ModSounds

object Menus {
    const val TEXT = 0xFFE6DFC8.toInt()
    const val DIM = 0xFF8C8672.toInt()
    const val MUTE = 0xFF4A473E.toInt()
    const val ALARM = 0xFFC8483C.toInt()
    const val SKY = 0xFF0C0B09.toInt()
    const val FLOOR = 0xFF1C1912.toInt()
    const val SHADE = 0x99000000.toInt()
    const val READY = 0xFF8FA65A.toInt()
    const val PLAYING = 0xFFC8483C.toInt()

    @JvmField
    val MUSIC = Music(ModSounds.MENU_MUSIC, 0, 0, true)

    private val LANGUAGE = Component.translatable("options.language")

    fun init() {
        ClientGuiEvent.SET_SCREEN.register { screen ->
            when {
                screen is TitleScreen || screen is IPortalInitialScreen -> CompoundEventResult.interruptTrue<Screen>(MainMenu())
                screen is PauseScreen && screen.showsPauseMenu() -> CompoundEventResult.interruptTrue<Screen>(PauseMenu())
                screen is DisconnectedScreen && Lobby.guest -> CompoundEventResult.interruptTrue<Screen>(MainMenu(Lobby.disconnectNote()))
                screen == null && Lobby.gated && Minecraft.getInstance().level != null -> CompoundEventResult.interruptTrue<Screen>(WaitScreen())
                else -> CompoundEventResult.pass()
            }
        }
        ClientGuiEvent.INIT_POST.register { screen, _ -> if (screen is OptionsScreen) lockLanguage(screen) }
        ClientGuiEvent.RENDER_POST.register { screen, graphics, _, _, _ -> Loading.renderScreen(graphics, screen) }
        ClientGuiEvent.RENDER_HUD.register { graphics, _ -> Loading.renderHud(graphics) }
    }

    private fun lockLanguage(screen: OptionsScreen) {
        screen.children().filterIsInstance<Button>().filter { it.message == LANGUAGE }.forEach {
            it.active = false
            it.setTooltip(Tooltip.create(Component.translatable("menu.substratum.language")))
        }
    }
}
