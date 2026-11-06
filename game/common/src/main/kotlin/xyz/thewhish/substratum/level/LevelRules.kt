package xyz.thewhish.substratum.level

import dev.architectury.event.CompoundEventResult
import dev.architectury.event.EventResult
import dev.architectury.event.events.common.EntityEvent
import dev.architectury.event.events.common.InteractionEvent
import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BucketItem
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.block.state.BlockState
import xyz.thewhish.substratum.registry.ModBlocks
import java.util.UUID

object LevelRules {
    private const val DEBUG_CHECK_INTERVAL = 10
    private const val REDUCED_DEBUG_ON: Byte = 22
    private const val REDUCED_DEBUG_OFF: Byte = 23

    private val reducedDebug = HashMap<UUID, Boolean>()
    private var reducedGlobal: Boolean? = null

    fun register() {
        InteractionEvent.RIGHT_CLICK_BLOCK.register { player, hand, pos, _ ->
            val level = BackroomsGuard.actualLevel(player.level())
            if (level.isClientSide || level.dimension() != SubstratumLevels.LEVEL_0 || picksUp(player, hand, level.getBlockState(pos))) {
                EventResult.pass()
            } else {
                EventResult.interruptFalse()
            }
        }
        InteractionEvent.RIGHT_CLICK_ITEM.register { player, hand ->
            val stack = player.getItemInHand(hand)
            if (stack.item is BucketItem && player.level().dimension() == SubstratumLevels.LEVEL_0) {
                CompoundEventResult.interruptFalse(stack)
            } else {
                CompoundEventResult.pass()
            }
        }
        EntityEvent.LIVING_HURT.register { entity, source, _ ->
            if (entity is ServerPlayer && !source.`is`(DamageTypes.GENERIC_KILL) && entity.level().dimension() == SubstratumLevels.LEVEL_0) {
                EventResult.interruptFalse()
            } else {
                EventResult.pass()
            }
        }
        TickEvent.SERVER_POST.register(::tickReducedDebug)
        PlayerEvent.PLAYER_JOIN.register(::resyncReducedDebug)
        PlayerEvent.PLAYER_RESPAWN.register { player, _, _ -> resyncReducedDebug(player) }
        PlayerEvent.CHANGE_DIMENSION.register { player, _, _ -> syncReducedDebug(player) }
        PlayerEvent.PLAYER_QUIT.register { reducedDebug.remove(it.uuid) }
        LifecycleEvent.SERVER_STOPPING.register {
            reducedDebug.clear()
            reducedGlobal = null
        }
    }

    private fun tickReducedDebug(server: MinecraftServer) {
        if (server.tickCount % DEBUG_CHECK_INTERVAL != 0) return
        val global = server.gameRules.getBoolean(GameRules.RULE_REDUCEDDEBUGINFO)
        if (reducedGlobal != global) {
            reducedGlobal = global
            reducedDebug.clear()
        }
        server.playerList.players.forEach(::syncReducedDebug)
    }

    private fun resyncReducedDebug(player: ServerPlayer) {
        reducedDebug.remove(player.uuid)
        syncReducedDebug(player)
    }

    private fun syncReducedDebug(player: ServerPlayer) {
        val wanted = player.server.gameRules.getBoolean(GameRules.RULE_REDUCEDDEBUGINFO) ||
            player.level().dimension() == SubstratumLevels.LEVEL_0
        if (reducedDebug.put(player.uuid, wanted) == wanted) return
        player.connection.send(ClientboundEntityEventPacket(player, if (wanted) REDUCED_DEBUG_ON else REDUCED_DEBUG_OFF))
    }

    private fun picksUp(player: Player, hand: InteractionHand, state: BlockState): Boolean =
        state.`is`(ModBlocks.ALMOND_WATER.get()) && hand == InteractionHand.MAIN_HAND && !player.isSpectator &&
            !(player.isSecondaryUseActive && (!player.mainHandItem.isEmpty || !player.offhandItem.isEmpty))
}
