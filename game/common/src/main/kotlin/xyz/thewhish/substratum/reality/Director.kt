package xyz.thewhish.substratum.reality

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import xyz.thewhish.substratum.config.ServerConfig
import xyz.thewhish.substratum.level.SubstratumLevels
import xyz.thewhish.substratum.sanity.SanityTracker
import xyz.thewhish.substratum.smiler.SmilerDirector
import xyz.thewhish.substratum.worldgen.MazeChunkGenerator
import xyz.thewhish.substratum.worldgen.MazeLayout
import java.util.UUID
import kotlin.random.Random

object Director {

    internal const val STEP = 20
    private const val MINUTE = 1200.0
    private const val GAIN = 0.3
    private const val CALM = 0.3
    private const val NEAR = 16.0
    private const val DARK = 0.7

    private val random = Random.Default
    private val tensions = HashMap<UUID, Tension<Manifestation>>()

    fun register() {
        TickEvent.SERVER_POST.register(::tick)
        PlayerEvent.PLAYER_QUIT.register { tensions.remove(it.uuid) }
        LifecycleEvent.SERVER_STOPPING.register { tensions.clear() }
    }

    internal fun gain(pressure: Double): Double = GAIN * STEP / MINUTE * (CALM + (1.0 - CALM) * pressure)

    private fun tick(server: MinecraftServer) {
        if (server.tickCount % STEP != 0) return
        val level = server.getLevel(SubstratumLevels.LEVEL_0) ?: return
        val layout = (level.chunkSource.generator as? MazeChunkGenerator)?.layout ?: return
        val settings = ServerConfig.reality
        val players = level.players().filter { it.isAlive && !it.isSpectator }
        tensions.keys.retainAll(players.mapTo(HashSet()) { it.uuid })
        if (!settings.enabled) return tensions.clear()
        for (player in players) {
            tensions.getOrPut(player.uuid) { Tension(random, settings.pause) }.accrue(gain(SanityTracker.pressure(player)))
        }
        val now = level.gameTime
        for (player in players.shuffled(random)) {
            val tension = tensions.getValue(player.uuid)
            if (!tension.due(now)) continue
            val offer = tension.pick(now, offers(player, layout, settings)) ?: continue
            val payers = perform(offer.kind, player, settings)
            if (payers.isEmpty()) tension.miss(now) else payers.forEach { tensions[it.uuid]?.spend(now, offer) }
            return
        }
    }

    private fun offers(player: ServerPlayer, layout: MazeLayout, settings: ServerConfig.RealitySettings): List<Tension.Offer<Manifestation>> {
        val pressure = SanityTracker.pressure(player)
        return Manifestation.entries.mapNotNull { kind ->
            val pace = settings.pace(kind)
            if (pace.frequency <= 0.0 || pressure < pace.pressure) return@mapNotNull null
            when (kind) {
                Manifestation.SMILER -> {
                    val dark = layout.columnAt(player.blockX, player.blockZ) and MazeLayout.DARK_ZONE != 0
                    Tension.Offer(kind, kind.cost * (if (dark) DARK else 1.0) / pace.frequency, pace.frequency, kind.big) { SmilerDirector.ready(player) }
                }
            }
        }
    }

    private fun perform(kind: Manifestation, player: ServerPlayer, settings: ServerConfig.RealitySettings): List<ServerPlayer> = when (kind) {
        Manifestation.SMILER -> {
            val visit = SmilerDirector.visit(player, settings.pace(kind).pressure)
            if (visit == null) emptyList() else near(player)
        }
    }

    private fun near(player: ServerPlayer): List<ServerPlayer> =
        player.serverLevel().players().filter { it.isAlive && !it.isSpectator && it.distanceToSqr(player) <= NEAR * NEAR }
}
