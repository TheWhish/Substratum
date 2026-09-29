package xyz.thewhish.substratum.sanity

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import xyz.thewhish.substratum.level.SubstratumLevels
import xyz.thewhish.substratum.network.SanitySync
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object SanityTracker {
    const val SYNC_INTERVAL = 40

    private val elapsed = ConcurrentHashMap<UUID, Int>()

    fun register() {
        TickEvent.SERVER_POST.register { server -> server.playerList.players.forEach(::tick) }
        LifecycleEvent.SERVER_STOPPING.register { elapsed.clear() }
        PlayerEvent.PLAYER_JOIN.register(::sync)
        PlayerEvent.PLAYER_RESPAWN.register { player, _, _ ->
            elapsed.remove(player.uuid)
            sync(player)
        }
    }

    fun ticks(player: Player): Int = elapsed[player.uuid] ?: 0

    fun phase(player: Player): SanityPhase = SanityPhase.of(ticks(player))

    fun rewind(player: ServerPlayer, ticks: Int) {
        require(ticks >= 0) { "sanity rewind must be positive, got $ticks" }
        elapsed.computeIfPresent(player.uuid) { _, current -> (current - ticks).coerceAtLeast(0) } ?: return
        sync(player)
    }

    fun set(player: ServerPlayer, ticks: Int): Boolean {
        require(ticks >= 0) { "sanity ticks must be positive, got $ticks" }
        if (player.level().dimension() != SubstratumLevels.LEVEL_0) return false
        elapsed[player.uuid] = ticks
        sync(player)
        return true
    }

    private fun tick(player: ServerPlayer) {
        if (player.level().dimension() != SubstratumLevels.LEVEL_0) {
            if (elapsed.remove(player.uuid) != null) sync(player)
            return
        }
        val now = elapsed.merge(player.uuid, 1) { current, _ -> if (current == Int.MAX_VALUE) current else current + 1 } ?: return
        if (now == 1 || now % SYNC_INTERVAL == 0 || SanityPhase.of(now) != SanityPhase.of(now - 1)) sync(player)
    }

    private fun sync(player: ServerPlayer) = SanitySync.send(player, ticks(player))
}
