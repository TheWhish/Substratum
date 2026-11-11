package xyz.thewhish.substratum.client.menu

import dev.architectury.event.CompoundEventResult
import dev.architectury.event.events.client.ClientGuiEvent
import dev.architectury.event.events.client.ClientLifecycleEvent
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.OptionsList
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.DisconnectedScreen
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.options.OptionsScreen
import net.minecraft.client.gui.screens.options.VideoSettingsScreen
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
        ClientLifecycleEvent.CLIENT_STARTED.register { it.options.gamma().set(0.0) }
        ClientGuiEvent.INIT_POST.register { screen, _ ->
            when (screen) {
                is OptionsScreen -> lockLanguage(screen)
                is VideoSettingsScreen -> lockBrightness(screen)
            }
        }
        ClientGuiEvent.RENDER_POST.register { screen: Screen?, graphics, _, _, _ ->
            if (screen != null) Loading.renderScreen(graphics, screen)
        }
    }

    private fun lockBrightness(screen: VideoSettingsScreen) {
        val gamma = Minecraft.getInstance().options.gamma()
        screen.children().filterIsInstance<OptionsList>().forEach { list ->
            list.findOption(gamma)?.let {
                it.active = false
                it.setTooltip(Tooltip.create(Component.translatable("menu.substratum.brightness")))
            }
        }
    }

    private fun lockLanguage(screen: OptionsScreen) {
        screen.children().filterIsInstance<Button>().filter { it.message == LANGUAGE }.forEach {
            it.active = false
            it.setTooltip(Tooltip.create(Component.translatable("menu.substratum.language")))
        }
    }
}
