package xyz.thewhish.substratum.level

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.BlockEvent
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import qouteall.imm_ptl.core.api.PortalAPI
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationServer
import qouteall.q_misc_util.my_util.IntBox

object BackroomsGuard {
    fun register() {
        BlockEvent.BREAK.register { level, pos, _, _, _ ->
            val actual = actualLevel(level)
            val state = actual.getBlockState(pos)
            if (!isProtected(actual)) {
                resyncRemoteWatchersAfterCrossPortalEdit(actual, pos)
                return@register EventResult.pass()
            }
            actual.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL)
            EventResult.interruptFalse()
        }
        BlockEvent.PLACE.register { level, pos, _, _ ->
            val actual = actualLevel(level)
            if (isProtected(actual)) return@register EventResult.interruptFalse()
            resyncRemoteWatchersAfterCrossPortalEdit(actual, pos)
            EventResult.pass()
        }
    }

    fun resyncRemoteWatchersAfterCrossPortalEdit(level: Level, pos: BlockPos) {
        if (level.isClientSide || level !is ServerLevel) return
        if (BlockManipulationServer.REDIRECT_CONTEXT.get() == null) return
        val target = pos.immutable()
        level.server.execute { PortalAPI.syncBlockUpdateToClientImmediately(level, IntBox(target, target)) }
    }

    fun actualLevel(level: Level): Level =
        BlockManipulationServer.REDIRECT_CONTEXT.get()?.world() ?: level

    fun isProtected(level: Level): Boolean =
        level.dimension() == SubstratumLevels.LEVEL_0
}
