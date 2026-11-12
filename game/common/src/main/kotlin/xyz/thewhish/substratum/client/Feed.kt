package xyz.thewhish.substratum.client

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.multiplayer.PlayerInfo
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket
import net.minecraft.util.FastColor
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.GameType
import org.lwjgl.opengl.GL11
import xyz.thewhish.substratum.client.menu.Loading
import xyz.thewhish.substratum.client.menu.Menus
import xyz.thewhish.substratum.level.SubstratumLevels
import xyz.thewhish.substratum.network.FeedSync
import java.util.UUID

object Feed {
    private const val MARGIN = 16
    private const val SIGNAL_SCALE = 3f
    private const val TITLE_SCALE = 2f
    private const val LOST_TICKS = 60
    private const val RETURN_TICKS = 24
    private const val NOTE_DELAY = 6f
    private const val NOTE_FADE = 24f
    private const val FONT_ALPHA_FLOOR = 4
    private const val BLINK = 20
    private const val TAB_GAP = 14
    private const val LINE_GAP = 3
    private const val DOT = 11
    private const val SCRIM = 56
    private const val SCRIM_SHADE = 0x73000000
    private const val TICKS_PER_SECOND = 20
    private val SIGNAL_LOST: Component = Component.translatable("hud.substratum.signal_lost")
    private val RECORDING_CUT: Component = Component.translatable("hud.substratum.recording_cut")

    private class Reel(val id: UUID, val title: Component?, val name: Component, val length: Long?)

    private var silent = false
    private var lost = 0
    private var tuning = 0
    private var black = 0
    private var age = 0
    private var born = -1L
    private var level = 0
    private var cut: Reel? = null
    private var pending: ClientboundSetCameraPacket? = null

    val dark: Boolean get() = silent || lost > 0 || tuning > 0 || Minecraft.getInstance().cameraEntity?.let(Collapse::ended) == true

    fun signal(live: Boolean) {
        silent = !live
        if (live) Loading.begin(label = false)
    }

    fun tape(born: Long, level: Int) {
        this.born = born
        this.level = level
    }

    fun reset() {
        silent = false
        lost = 0
        tuning = 0
        black = 0
        born = -1L
        cut = null
        pending = null
    }

    @JvmStatic
    fun camera(packet: ClientboundSetCameraPacket) {
        val level = Minecraft.getInstance().level ?: return
        pending = packet.takeIf { it.getEntity(level) == null }
    }

    fun tick(minecraft: Minecraft) {
        age++
        settleCamera(minecraft)
        traceSignal(minecraft)
        if (!watching(minecraft)) return
        minecraft.options.keyShift.setDown(false)
        val channels = channels(minecraft)
        if (channels.isEmpty()) return
        val current = channel(minecraft, channels)
        while (minecraft.options.keyAttack.consumeClick()) tune(minecraft, channels[Math.floorMod(current + 1, channels.size)])
        while (minecraft.options.keyUse.consumeClick()) tune(minecraft, channels[Math.floorMod(if (current < 0) -1 else current - 1, channels.size)])
        for ((index, key) in minecraft.options.keyHotbarSlots.withIndex()) {
            while (key.consumeClick()) channels.getOrNull(index)?.let { tune(minecraft, it) }
        }
    }

    private fun settleCamera(minecraft: Minecraft) {
        val packet = pending ?: return
        val entity = minecraft.level?.let(packet::getEntity) ?: return
        minecraft.setCameraEntity(entity)
        pending = null
    }

    private fun traceSignal(minecraft: Minecraft) {
        val ended = minecraft.cameraEntity?.takeIf(Collapse::ended)
        lost = if (ended != null) LOST_TICKS else maxOf(0, lost - 1)
        tuning = if (pending != null) RETURN_TICKS else maxOf(0, tuning - 1)
        if (ended != null && cut?.id != ended.uuid) cut = record(minecraft, ended)
        val loss = loss(minecraft, 0f)
        black = when {
            loss >= 1f -> black + 1
            loss <= 0f -> 0
            else -> black
        }
        if (loss <= 0f) cut = null
    }

    private fun record(minecraft: Minecraft, entity: Entity): Reel {
        val channel = channels(minecraft).indexOfFirst { it.profile.id == entity.uuid }
        return Reel(entity.uuid, if (channel < 0) null else label(channel), entity.name, length(minecraft))
    }

    private fun length(minecraft: Minecraft): Long? {
        val now = minecraft.level?.gameTime ?: return null
        return if (born < 0) null else maxOf(0L, now - born)
    }

    private fun smooth(value: Float): Float = Mth.clamp(value, 0f, 1f).let { it * it * (3f - 2f * it) }

    private fun loss(minecraft: Minecraft, partialTick: Float): Float {
        val dying = minecraft.cameraEntity?.let { Collapse.fade(it, partialTick) } ?: 0f
        return maxOf(if (silent) 1f else 0f, dying, smooth((lost - partialTick) / RETURN_TICKS))
    }

    private fun shade(minecraft: Minecraft, partialTick: Float): Float =
        maxOf(loss(minecraft, partialTick), smooth((tuning - partialTick) / RETURN_TICKS))

    private fun noteAlpha(partialTick: Float): Float {
        val shown = smooth((black + partialTick - NOTE_DELAY) / NOTE_FADE)
        return if (silent) shown else shown * smooth((lost - partialTick) / RETURN_TICKS)
    }

    private fun tune(minecraft: Minecraft, target: PlayerInfo) {
        if (target.profile.id == minecraft.cameraEntity?.uuid) return
        Vhs.burst()
        FeedSync.watch(target.profile.id)
    }

    private fun channels(minecraft: Minecraft): List<PlayerInfo> =
        minecraft.connection?.onlinePlayers?.filter { it.gameMode != GameType.SPECTATOR }?.sortedBy { it.profile.id }.orEmpty()

    private fun channel(minecraft: Minecraft, channels: List<PlayerInfo>): Int =
        channels.indexOfFirst { it.profile.id == minecraft.cameraEntity?.uuid }

    private fun watching(minecraft: Minecraft): Boolean {
        val player = minecraft.player ?: return false
        return player.isSpectator && player.level().dimension() == SubstratumLevels.LEVEL_0
    }

    fun renderHud(graphics: GuiGraphics) {
        val minecraft = Minecraft.getInstance()
        if (dark || !watching(minecraft) || minecraft.options.hideGui) return
        val channels = channels(minecraft)
        val channel = channel(minecraft, channels)
        if (channel < 0) return
        graphics.fillGradient(0, 0, graphics.guiWidth(), SCRIM, SCRIM_SHADE, 0)
        graphics.fillGradient(0, graphics.guiHeight() - SCRIM, graphics.guiWidth(), graphics.guiHeight(), 0, SCRIM_SHADE)
        osd(graphics, label(channel), name(channels[channel]), length(minecraft), 1f)
        strip(graphics, minecraft, channels, channel)
    }

    private fun name(info: PlayerInfo): Component = Component.literal(info.profile.name)

    private fun label(channel: Int): Component = Component.translatable("hud.substratum.channel", "%02d".format(channel + 1))

    private fun osd(graphics: GuiGraphics, title: Component?, name: Component?, length: Long?, alpha: Float) {
        val font = Minecraft.getInstance().font
        val titleHeight = (font.lineHeight * TITLE_SCALE).toInt()
        if (age % (BLINK * 2) < BLINK) disc(graphics, MARGIN, MARGIN + (titleHeight - DOT) / 2, Hotbar.fade(Menus.ALARM, alpha))
        val left = MARGIN + DOT + LINE_GAP * 2
        title?.let { scaled(graphics, it, left, MARGIN, TITLE_SCALE, Menus.TEXT, alpha) }
        name?.let { write(graphics, Lettering.text(it), left, MARGIN + titleHeight + LINE_GAP, Menus.DIM, alpha) }
        if (length == null) return
        val area = Lettering.text(Component.translatable("hud.substratum.level", level))
        write(graphics, area, graphics.guiWidth() - MARGIN - font.width(area), MARGIN + (titleHeight - font.lineHeight) / 2, Menus.DIM, alpha)
        val counter = Lettering.text(Component.literal(Menus.tape(length / TICKS_PER_SECOND)))
        write(graphics, counter, graphics.guiWidth() - MARGIN - font.width(counter), graphics.guiHeight() - MARGIN - font.lineHeight, Menus.DIM, alpha)
    }

    private fun disc(graphics: GuiGraphics, x: Int, y: Int, color: Int) {
        val radius = DOT / 2f
        for (row in 0 until DOT) {
            val offset = row + 0.5f - radius
            val inset = Math.round(radius - Mth.sqrt(radius * radius - offset * offset))
            graphics.fill(x + inset, y + row, x + DOT - inset, y + row + 1, color)
        }
    }

    private fun strip(graphics: GuiGraphics, minecraft: Minecraft, channels: List<PlayerInfo>, current: Int) {
        val font = minecraft.font
        val tabs = channels.mapIndexed { index, info ->
            Lettering.text(Component.literal("%02d ".format(index + 1)).append(name(info)))
        }
        val hint = Lettering.text(Component.translatable("hud.substratum.feed_hint", channels.size))
        val width = tabs.sumOf { font.width(it) } + TAB_GAP * (tabs.size - 1)
        val bottom = graphics.guiHeight() - MARGIN - font.lineHeight
        val tabsTop = if (channels.size > 1) bottom - font.lineHeight - LINE_GAP * 3 else bottom
        var x = (graphics.guiWidth() - width) / 2
        for ((index, tab) in tabs.withIndex()) {
            val selected = index == current
            write(graphics, tab, x, tabsTop, if (selected) Menus.TEXT else Menus.DIM, 1f)
            if (selected) graphics.fill(x, tabsTop + font.lineHeight, x + font.width(tab) - 1, tabsTop + font.lineHeight + 1, Hotbar.ACCENT)
            x += font.width(tab) + TAB_GAP
        }
        if (channels.size > 1) write(graphics, hint, (graphics.guiWidth() - font.width(hint)) / 2, bottom, Menus.DIM, 1f)
    }

    @JvmStatic
    fun renderOverlay(graphics: GuiGraphics, partialTick: Float) {
        val minecraft = Minecraft.getInstance()
        val shade = shade(minecraft, partialTick)
        Loading.hear(1f - shade)
        graphics.flush()
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX)
        if (shade > 0f) {
            graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), FastColor.ARGB32.colorFromFloat(shade, 0f, 0f, 0f))
            val note = noteAlpha(partialTick)
            if (note > 0f) blackout(graphics, minecraft, note)
        }
        Loading.renderHud(graphics)
    }

    private fun blackout(graphics: GuiGraphics, minecraft: Minecraft, alpha: Float) {
        val font = minecraft.font
        val title = Lettering.text(SIGNAL_LOST, SIGNAL_SCALE)
        scaled(graphics, title, (graphics.guiWidth() - (font.width(title) * SIGNAL_SCALE).toInt()) / 2, (graphics.guiHeight() - (font.lineHeight * SIGNAL_SCALE).toInt()) / 2, SIGNAL_SCALE, Menus.TEXT, alpha)
        val note = Lettering.text(RECORDING_CUT)
        val top = graphics.guiHeight() / 2 + (font.lineHeight * SIGNAL_SCALE).toInt() / 2 + LINE_GAP * 2
        write(graphics, note, (graphics.guiWidth() - font.width(note)) / 2, top, Menus.DIM, alpha)
        val reel = cut
        osd(graphics, reel?.title, reel?.name, reel?.length, alpha)
    }

    private fun scaled(graphics: GuiGraphics, text: Component, x: Int, y: Int, scale: Float, color: Int, alpha: Float) {
        val pose = graphics.pose()
        pose.pushPose()
        pose.translate(x.toFloat(), y.toFloat(), 0f)
        pose.scale(scale, scale, 1f)
        write(graphics, Lettering.text(text, scale), 0, 0, color, alpha)
        pose.popPose()
    }

    private fun write(graphics: GuiGraphics, text: Component, x: Int, y: Int, color: Int, alpha: Float) {
        val faded = Hotbar.fade(color, alpha)
        if (faded ushr 24 >= FONT_ALPHA_FLOOR) graphics.drawString(Minecraft.getInstance().font, text, x, y, faded, false)
    }
}
