package xyz.thewhish.substratum.level

import dev.architectury.event.events.common.TickEvent
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import kotlin.math.sign

object Hands {
    const val SLOTS = 6

    fun register() {
        TickEvent.SERVER_POST.register { server -> server.playerList.players.forEach(::tidy) }
    }

    @JvmStatic
    fun freeSlot(inventory: Inventory): Int {
        val selected = inventory.selected
        if (selected in 0 until SLOTS && inventory.items[selected].isEmpty) return selected
        return (0 until SLOTS).firstOrNull { inventory.items[it].isEmpty } ?: -1
    }

    @JvmStatic
    fun scroll(selected: Int, direction: Double): Int = Math.floorMod(selected - direction.sign.toInt(), SLOTS)

    private fun tidy(player: ServerPlayer) {
        if (player.isCreative || player.isSpectator) return
        val inventory = player.inventory
        if (inventory.selected !in 0 until SLOTS) {
            inventory.selected = 0
            player.connection.send(ClientboundSetCarriedItemPacket(0))
        }
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            val kept = if (slot < SLOTS) 1 else 0
            if (stack.count <= kept) continue
            val loose = stack.split(stack.count - kept)
            inventory.add(loose)
            if (!loose.isEmpty) player.drop(loose, false)
        }
    }
}
