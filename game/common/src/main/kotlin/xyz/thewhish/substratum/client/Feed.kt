package xyz.thewhish.substratum.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Player
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.client.menu.Loading
import xyz.thewhish.substratum.client.menu.Menus
import xyz.thewhish.substratum.level.SubstratumLevels
import xyz.thewhish.substratum.network.FeedSync

object Feed {
    private const val MARGIN = 12
    private const val SIGNAL_SCALE = 3f
    private val FONT = Substratum.id("caption")
    private val NO_SIGNAL: Component = Component.translatable("hud.substratum.no_signal").withStyle { it.withFont(FONT) }

    private var silent = false

    fun signal(live: Boolean) {
        silent = !live
        if (live) Loading.begin(label = false)
    }

    fun reset() {
        silent = false
    }

    fun tick(minecraft: Minecraft) {
        if (!watching(minecraft)) return
        while (minecraft.options.keyAttack.consumeClick()) FeedSync.switch(forward = true)
        while (minecraft.options.keyUse.consumeClick()) FeedSync.switch(forward = false)
    }

    fun renderHud(graphics: GuiGraphics) {
        val minecraft = Minecraft.getInstance()
        if (silent) return noSignal(graphics, minecraft)
        if (!watching(minecraft)) return
        val camera = minecraft.cameraEntity as? Player ?: return
        val level = minecraft.level ?: return
        val channels = level.players().filter { !it.isSpectator }.sortedBy { it.uuid }
        val channel = channels.indexOfFirst { it.uuid == camera.uuid }
        if (channel < 0) return
        val label = Component.translatable("hud.substratum.channel", "%02d".format(channel + 1)).withStyle { it.withFont(FONT) }
        graphics.drawString(minecraft.font, label, MARGIN, MARGIN, Menus.DIM, false)
    }

    private fun watching(minecraft: Minecraft): Boolean {
        val player = minecraft.player ?: return false
        return player.isSpectator && player.level().dimension() == SubstratumLevels.LEVEL_0
    }

    private fun noSignal(graphics: GuiGraphics, minecraft: Minecraft) {
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), 0xFF000000.toInt())
        val pose = graphics.pose()
        pose.pushPose()
        pose.translate(graphics.guiWidth() / 2f, graphics.guiHeight() / 2f, 0f)
        pose.scale(SIGNAL_SCALE, SIGNAL_SCALE, 1f)
        val font = minecraft.font
        graphics.drawString(font, NO_SIGNAL, -font.width(NO_SIGNAL) / 2, -font.lineHeight / 2, Menus.DIM, false)
        pose.popPose()
    }
}
