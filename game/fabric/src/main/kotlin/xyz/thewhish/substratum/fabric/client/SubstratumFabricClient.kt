package xyz.thewhish.substratum.fabric.client

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.InteractionEvent
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationClient
import xyz.thewhish.substratum.client.SubstratumClient
import xyz.thewhish.substratum.level.BackroomsGuard
import xyz.thewhish.substratum.level.SubstratumLevels

object SubstratumFabricClient : ClientModInitializer {
    override fun onInitializeClient() {
        SubstratumClient.init()
        WorldRenderEvents.BLOCK_OUTLINE.register { context, _ ->
            context.world().dimension() != SubstratumLevels.LEVEL_0
        }
        InteractionEvent.LEFT_CLICK_BLOCK.register { player, _, _, _ ->
            if (!player.level().isClientSide) return@register EventResult.pass()
            val level = if (BlockManipulationClient.isPointingToPortal()) {
                runCatching { BlockManipulationClient.getRemotePointedWorld() }.getOrNull() ?: player.level()
            } else {
                player.level()
            }
            if (BackroomsGuard.isProtected(level)) EventResult.interruptFalse() else EventResult.pass()
        }
    }
}
