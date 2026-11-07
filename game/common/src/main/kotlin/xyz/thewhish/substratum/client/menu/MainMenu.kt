package xyz.thewhish.substratum.client.menu

import dev.architectury.platform.Platform
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.PlayerFaceRenderer
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.options.OptionsScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.client.Link
import xyz.thewhish.substratum.client.Lobby

class MainMenu(private var note: Component? = null) : Screen(Component.literal(TITLE)) {
    private var confirming = false
    private var failed = false

    private val left get() = width / 10
    private val top get() = height / 2

    override fun init() {
        Lobby.inMenu()
        val buttons = when {
            confirming -> listOf(
                MenuButton(left, 0, Component.translatable("menu.substratum.restart"), ::start),
                MenuButton(left, 0, Component.translatable("menu.substratum.cancel")) { confirm(false) },
            )
            Lobby.guest -> listOf(guestAction(), settings(), quit())
            else -> hostActions() + listOf(settings(), quit())
        }
        buttons.forEachIndexed { index, button ->
            button.y = top + index * MenuButton.HEIGHT
            addRenderableWidget(button)
        }
    }

    fun refresh() = rebuildWidgets()

    private fun hostActions(): List<MenuButton> {
        val saved = Save.exists()
        val ready = Lobby.everyoneReady()
        return listOf(
            MenuButton(left, 0, Component.translatable("menu.substratum.continue"), ::resume).apply { active = saved && ready },
            MenuButton(left, 0, Component.translatable("menu.substratum.new_game")) {
                if (saved) confirm(true) else start()
            }.apply { active = ready },
        )
    }

    private fun guestAction(): MenuButton {
        if (Lobby.session != null) {
            return MenuButton(left, 0, Component.translatable("menu.substratum.return")) {
                Lobby.rejoin(minecraft!!)
            }.apply { active = Lobby.canReturn() }
        }
        val label = if (Lobby.ready) "menu.substratum.unready" else "menu.substratum.ready"
        return MenuButton(left, 0, Component.translatable(label)) {
            Lobby.toggleReady()
            rebuildWidgets()
        }.apply { active = Lobby.sameVersion() }
    }

    private fun settings() = MenuButton(left, 0, Component.translatable("menu.substratum.settings")) {
        minecraft!!.setScreen(OptionsScreen(this, minecraft!!.options))
    }

    private fun quit() = MenuButton(left, 0, Component.translatable("menu.substratum.quit")) { minecraft!!.stop() }

    private fun confirm(asking: Boolean) {
        confirming = asking
        failed = false
        note = null
        rebuildWidgets()
    }

    private fun resume() {
        note = null
        Lobby.launch {
            Loading.begin()
            Save.resume(this)
        }
    }

    private fun start() {
        if (!Save.archive()) {
            confirming = false
            failed = true
            rebuildWidgets()
            return
        }
        confirming = false
        note = null
        Lobby.launch {
            Loading.begin()
            Save.create(this)
        }
        rebuildWidgets()
    }

    override fun shouldCloseOnEsc(): Boolean = confirming

    override fun onClose() = confirm(false)

    override fun renderBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        graphics.fillGradient(0, 0, width, height, Menus.SKY, Menus.FLOOR)
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        val pose = graphics.pose()
        pose.pushPose()
        pose.translate(left.toFloat(), (top - LOGO_GAP).toFloat(), 0f)
        pose.scale(LOGO_SCALE, LOGO_SCALE, 1f)
        graphics.drawString(font, TITLE, 0, -font.lineHeight, Menus.TEXT, false)
        pose.popPose()
        status()?.let { (text, color) ->
            graphics.drawString(font, text, left + NOTE_INDENT, top - font.lineHeight - NOTE_GAP, color, false)
        }
        drawTeam(graphics)
        graphics.drawString(font, version, EDGE, height - EDGE - font.lineHeight, Menus.MUTE, false)
    }

    private fun status(): Pair<Component, Int>? {
        val lobbyNote = Lobby.note
        return when {
            confirming -> Component.translatable("menu.substratum.archive_warning") to Menus.DIM
            failed -> Component.translatable("menu.substratum.archive_failed") to Menus.ALARM
            note != null -> note!! to Menus.ALARM
            lobbyNote != null -> lobbyNote to Menus.ALARM
            Lobby.team == null || Lobby.team!!.members.size < 2 -> null
            Lobby.guest && !Lobby.sameVersion() -> Component.translatable("menu.substratum.other_version") to Menus.ALARM
            Lobby.guest && Lobby.session != null -> Component.translatable("menu.substratum.in_progress") to Menus.DIM
            Lobby.guest -> Component.translatable("menu.substratum.waiting_host") to Menus.DIM
            else -> Lobby.readiness().let { (ready, total) ->
                Component.translatable("menu.substratum.ready_count", ready, total) to Menus.DIM
            }
        }
    }

    private fun drawTeam(graphics: GuiGraphics) {
        val team = Lobby.team ?: return
        if (team.members.size < 2) return
        val right = width - EDGE
        team.members.forEachIndexed { index, member ->
            val y = EDGE + index * SLOT
            val skin = ResourceLocation.tryBuild(Substratum.ID, "textures/skin/${member.skin}.png")
            if (skin != null) PlayerFaceRenderer.draw(graphics, skin, right - FACE, y, FACE)
            val (label, color) = state(member)
            val textRight = right - FACE - SLOT_GAP
            graphics.drawString(font, member.nick, textRight - font.width(member.nick), y, Menus.TEXT, false)
            val stateText = Component.translatable(label)
            graphics.drawString(font, stateText, textRight - font.width(stateText), y + font.lineHeight + 1, color, false)
        }
    }

    private fun state(member: Link.Member): Pair<String, Int> = when {
        !member.online -> "menu.substratum.state.offline" to Menus.MUTE
        member.presence == Link.READY -> "menu.substratum.state.ready" to Menus.READY
        member.presence == Link.PLAYING -> "menu.substratum.state.playing" to Menus.PLAYING
        member.presence == Link.MENU -> "menu.substratum.state.menu" to Menus.DIM
        else -> "menu.substratum.state.away" to Menus.MUTE
    }

    private companion object {
        const val TITLE = "SUBSTRATUM"
        const val LOGO_SCALE = 4f
        const val LOGO_GAP = 24
        const val NOTE_INDENT = 10
        const val NOTE_GAP = 6
        const val EDGE = 8
        const val FACE = 18
        const val SLOT = 24
        const val SLOT_GAP = 6

        val version: String by lazy {
            "v" + Platform.getMod(Substratum.ID).version
        }
    }
}
