package xyz.thewhish.substratum.client

import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import xyz.thewhish.substratum.client.menu.Menus
import xyz.thewhish.substratum.level.Hands
import kotlin.math.roundToInt

object Hotbar {
    private const val CELL = 20
    private const val GAP = 2
    private const val INSET = 2
    private const val MARGIN = 8
    private const val LABEL_GAP = 6
    private const val MARK_INSET = 6
    private const val HOLD = 40
    private const val FADE = 12
    private const val FRAME = 0x47E8E0BE
    private const val FILL = 0x2E000000
    const val ACCENT = 0xE6FFF6D6.toInt()
    private const val ACTIVE_FILL = 0x4D000000
    private const val FONT_ALPHA_FLOOR = 4

    private var selected = -1
    private var held: Item = Items.AIR
    private var age = HOLD + FADE

    fun guard(minecraft: Minecraft) {
        val options = minecraft.options
        for (slot in Hands.SLOTS until options.keyHotbarSlots.size) {
            while (options.keyHotbarSlots[slot].consumeClick()) Unit
        }
        while (options.keySwapOffhand.consumeClick()) Unit
    }

    fun tick(minecraft: Minecraft) {
        val player = minecraft.player ?: return reset()
        if (age < HOLD + FADE) age++
        val inventory = player.inventory
        val item = player.mainHandItem.item
        if (inventory.selected == selected && item === held) return
        selected = inventory.selected
        held = item
        age = if (item === Items.AIR) HOLD + FADE else 0
    }

    private fun reset() {
        selected = -1
        held = Items.AIR
        age = HOLD + FADE
    }

    fun render(graphics: GuiGraphics, delta: DeltaTracker) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        if (minecraft.options.hideGui || player.isSpectator) return
        val inventory = player.inventory
        val left = (graphics.guiWidth() - (CELL * Hands.SLOTS + GAP * (Hands.SLOTS - 1))) / 2
        val top = graphics.guiHeight() - MARGIN - CELL
        for (slot in 0 until Hands.SLOTS) {
            val x = left + slot * (CELL + GAP)
            val active = slot == inventory.selected
            graphics.fill(x + 1, top + 1, x + CELL - 1, top + CELL - 1, if (active) ACTIVE_FILL else FILL)
            graphics.renderOutline(x, top, CELL, CELL, if (active) ACCENT else FRAME)
            if (active) graphics.fill(x + MARK_INSET, top + CELL + 2, x + CELL - MARK_INSET, top + CELL + 3, ACCENT)
            val stack = inventory.items[slot]
            if (!stack.isEmpty) graphics.renderItem(stack, x + INSET, top + INSET)
        }
        label(graphics, minecraft, top, visibility(delta.getGameTimeDeltaPartialTick(false)))
    }

    private fun label(graphics: GuiGraphics, minecraft: Minecraft, top: Int, alpha: Float) {
        val stack = minecraft.player?.mainHandItem ?: return
        if (stack.isEmpty) return
        val color = fade(Menus.TEXT, alpha)
        if (color ushr 24 < FONT_ALPHA_FLOOR) return
        val name = Lettering.text(stack.hoverName)
        val font = minecraft.font
        graphics.drawString(font, name, (graphics.guiWidth() - font.width(name)) / 2, top - LABEL_GAP - font.lineHeight, color, false)
    }

    private fun visibility(partialTick: Float): Float {
        val fading = (age + partialTick - HOLD) / FADE
        if (fading <= 0f) return 1f
        if (fading >= 1f) return 0f
        val t = 1f - fading
        return t * t * (3 - 2 * t)
    }

    fun fade(color: Int, alpha: Float): Int = ((color ushr 24) * alpha).roundToInt() shl 24 or (color and 0xFFFFFF)
}
