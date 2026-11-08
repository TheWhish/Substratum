package xyz.thewhish.substratum.client.menu

import dev.architectury.platform.Platform
import net.minecraft.Util
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.options.OptionsScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.client.Link
import xyz.thewhish.substratum.client.Lobby
import xyz.thewhish.substratum.client.Suit
import java.util.SplittableRandom

class MainMenu(private var note: Component? = null) : Screen(Component.literal(TITLE)) {
    private var confirming = false
    private var failed = false

    private val left get() = width / 10
    private val top get() = (height + HEADER - rows * MenuButton.HEIGHT) / 2
    private var rows = 0

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
        rows = buttons.size
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
        Backdrop.render(graphics, width, height, mouseX, mouseY, true)
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        drawLogo(graphics)
        status()?.let { (text, color) ->
            graphics.drawString(font, text, left + NOTE_INDENT, top + rows * MenuButton.HEIGHT + NOTE_GAP, color, false)
        }
        drawTeam(graphics)
        graphics.drawString(font, version, EDGE, height - EDGE - GLYPH, Menus.MUTE, false)
        drawTape(graphics)
    }

    private fun drawLogo(graphics: GuiGraphics) {
        val now = Util.getMillis()
        if (now > glitchEnd) {
            glitchAt = now + random.nextLong(GLITCH_QUIET_MIN_MS, GLITCH_QUIET_MAX_MS)
            glitchEnd = glitchAt + random.nextLong(GLITCH_MIN_MS, GLITCH_MAX_MS)
        }
        val split = if (now >= glitchAt) GLITCH_SPLIT else SPLIT
        val tagline = top - TAGLINE_GAP - BASELINE
        val pose = graphics.pose()
        for ((shift, color) in listOf(-split to RED, split to CYAN, 0f to Menus.TEXT)) {
            pose.pushPose()
            pose.translate(left + shift, (tagline - LOGO_GAP).toFloat(), 0f)
            pose.scale(LOGO_SCALE, LOGO_SCALE, 1f)
            graphics.drawString(font, LOGO, 0, -BASELINE, color, false)
            pose.popPose()
        }
        val glyphs = TAGLINE.map { letter -> Component.literal(letter.toString()).withStyle { it.withFont(CAPTION) } }
        val span = (font.width(LOGO) - 1) * LOGO_SCALE - TAGLINE_INDENT
        val spacing = (span - glyphs.sumOf { font.width(it) }) / (glyphs.size - 1)
        var x = left + TAGLINE_INDENT
        for (glyph in glyphs) {
            pose.pushPose()
            pose.translate(x, 0f, 0f)
            graphics.drawString(font, glyph, 0, tagline, Menus.DIM, false)
            pose.popPose()
            x += font.width(glyph) + spacing
        }
    }

    private fun drawTape(graphics: GuiGraphics) {
        val seconds = (Util.getMillis() - opened) / 1000
        val tape = "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        graphics.drawString(font, tape, width - EDGE - font.width(tape) + 1, height - EDGE - GLYPH, Menus.MUTE, false)
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
            if (skin != null) graphics.blit(skin, right - FACE, y, FACE, FACE, 8f, 8f, 8, 8, Suit.WIDTH, Suit.HEIGHT)
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
        const val LOGO_SCALE = 1.6f
        const val HEADER = 84
        const val LOGO_GAP = 5
        const val BASELINE = 7
        const val GLYPH = 7
        const val TAGLINE = "FOUND FOOTAGE · NO SIGNAL"
        const val TAGLINE_GAP = 24
        const val TAGLINE_INDENT = 1f
        const val SPLIT = 0.35f
        const val GLITCH_SPLIT = 1.2f
        const val GLITCH_MIN_MS = 70L
        const val GLITCH_MAX_MS = 160L
        const val GLITCH_QUIET_MIN_MS = 2_500L
        const val GLITCH_QUIET_MAX_MS = 7_000L
        const val RED = 0x8CFF3C28.toInt()
        const val CYAN = 0x733CC8FF
        const val NOTE_INDENT = 10
        const val NOTE_GAP = 6
        const val EDGE = 8
        const val FACE = 18
        const val SLOT = 24
        const val SLOT_GAP = 6

        val CAPTION: ResourceLocation = Substratum.id("caption")
        val LOGO: Component = Component.literal(TITLE).withStyle { it.withFont(Substratum.id("logo")) }
        val random = SplittableRandom()
        val opened = Util.getMillis()
        var glitchAt = 0L
        var glitchEnd = 0L

        val version: String by lazy {
            "v" + Platform.getMod(Substratum.ID).version
        }
    }
}
