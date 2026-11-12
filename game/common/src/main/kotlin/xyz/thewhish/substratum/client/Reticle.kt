package xyz.thewhish.substratum.client

import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.core.registries.Registries
import net.minecraft.tags.TagKey
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.level.Remains

object Reticle {
    private const val FADE_TICKS = 2f
    private const val HALF = 1
    private val INTERACTABLE: TagKey<Block> = TagKey.create(Registries.BLOCK, Substratum.id("interactable"))

    private var presence = 0f

    fun render(graphics: GuiGraphics, delta: DeltaTracker) {
        val minecraft = Minecraft.getInstance()
        val target = if (aimed(minecraft)) 1f else 0f
        val step = delta.realtimeDeltaTicks / FADE_TICKS
        presence = if (target > presence) minOf(target, presence + step) else maxOf(target, presence - step)
        if (presence <= 0f || minecraft.options.hideGui) return
        val x = graphics.guiWidth() / 2
        val y = graphics.guiHeight() / 2
        graphics.fill(x - HALF, y - HALF, x + HALF, y + HALF, Hotbar.fade(Hotbar.ACCENT, presence))
    }

    private fun aimed(minecraft: Minecraft): Boolean {
        val player = minecraft.player ?: return false
        if (player.isSpectator || minecraft.screen != null || Collapse.falling(player)) return false
        return when (val hit = minecraft.hitResult) {
            is BlockHitResult -> hit.type == HitResult.Type.BLOCK && minecraft.level?.getBlockState(hit.blockPos)?.`is`(INTERACTABLE) == true
            is EntityHitResult -> (hit.entity as? Remains)?.stocked == true
            else -> false
        }
    }
}
