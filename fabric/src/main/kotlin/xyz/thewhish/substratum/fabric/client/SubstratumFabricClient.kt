package xyz.thewhish.substratum.fabric.client

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.InteractionEvent
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationClient
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.client.SubstratumClient
import xyz.thewhish.substratum.level.BackroomsGuard
import xyz.thewhish.substratum.level.SubstratumLevels
import xyz.thewhish.substratum.rift.RiftCutBlock

object SubstratumFabricClient : ClientModInitializer {
    override fun onInitializeClient() {
        SubstratumClient.init()
        WorldRenderEvents.BLOCK_OUTLINE.register { context, outlineContext ->
            outlineContext.blockState().block !is RiftCutBlock && context.world().dimension() != SubstratumLevels.LEVEL_0
        }
        InteractionEvent.LEFT_CLICK_BLOCK.register { player, _, pos, _ ->
            // fabric api fires this on the server too, in singleplayer that punches holes on the client; BackroomsGuard owns that side
            if (!player.level().isClientSide) return@register EventResult.pass()
            val level = if (BlockManipulationClient.isPointingToPortal()) {
                runCatching { BlockManipulationClient.getRemotePointedWorld() }.getOrNull() ?: player.level()
            } else {
                player.level()
            }
            if (BackroomsGuard.isProtected(level, level.getBlockState(pos))) EventResult.interruptFalse() else EventResult.pass()
        }
        ModelLoadingPlugin.register { context ->
            context.modifyModelAfterBake().register { model, bakeContext ->
                if (model != null && bakeContext.topLevelId()?.id() == RIFT_CUT) RiftCutFabricModel(model) else model
            }
        }
    }

    private val RIFT_CUT = Substratum.id("rift_cut")
}
