package xyz.thewhish.substratum.smiler

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import xyz.thewhish.substratum.config.ServerConfig
import xyz.thewhish.substratum.level.SubstratumLevels
import xyz.thewhish.substratum.registry.ModEntities
import xyz.thewhish.substratum.smiler.Spots.Approach
import xyz.thewhish.substratum.worldgen.Corridors
import xyz.thewhish.substratum.worldgen.MazeChunkGenerator
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random

object SmilerDirector {

    private const val MAX_AT_ONCE = 6
    private const val FOOTING = 1.5
    private const val COMPANY = 12.0
    private const val REPEAT = 0.35
    private const val CROWDED_BEHIND = 0.5
    private val WEIGHTS = mapOf(Approach.AHEAD to 4.0, Approach.CORNER to 4.0, Approach.BEHIND to 2.0)

    private val random = Random.Default
    private val visits = HashMap<UUID, Visit>()
    private val last = HashMap<UUID, Approach>()

    fun register() {
        TickEvent.SERVER_POST.register(::tick)
        PlayerEvent.PLAYER_QUIT.register { last.remove(it.uuid) }
        LifecycleEvent.SERVER_STOPPING.register {
            visits.clear()
            last.clear()
        }
    }

    private fun tick(server: MinecraftServer) {
        if (visits.isEmpty()) return
        val level = server.getLevel(SubstratumLevels.LEVEL_0) ?: return
        val layout = (level.chunkSource.generator as? MazeChunkGenerator)?.layout ?: return
        val stage = Stage(level, layout)
        visits.values.removeIf { !it.advance(stage, stage.level.getPlayerByUUID(it.victim) as? ServerPlayer) }
    }

    fun ready(player: ServerPlayer): Boolean {
        if (player.uuid in visits || visits.size >= MAX_AT_ONCE) return false
        val reach = ServerConfig.reality.smilerDistance.last.toDouble()
        return player.serverLevel().getEntitiesOfClass(Smiler::class.java, player.boundingBox.inflate(reach)) {
            it.isAlive && it.distanceToSqr(player) < reach * reach
        }.isEmpty()
    }

    fun visit(player: ServerPlayer, from: Double): Visit? {
        val stage = stageOf(player) ?: return null
        val visit = summon(stage, player, order(last[player.uuid], company(stage, player)), from) ?: return null
        visits[player.uuid] = visit
        last[player.uuid] = visit.approach
        return visit
    }

    fun conjure(player: ServerPlayer, approach: Approach?): Boolean {
        val stage = stageOf(player) ?: return false
        visits.remove(player.uuid)?.smiler?.discard()
        val approaches = approach?.let(::listOf) ?: order(last[player.uuid], company(stage, player))
        val visit = summon(stage, player, approaches, 0.0) ?: return false
        visits[player.uuid] = visit
        return true
    }

    fun lost(player: ServerPlayer, entity: Int) {
        visits.values.firstOrNull { it.smiler?.id == entity }?.lose(player)
    }

    fun clear(level: ServerLevel): Int {
        val smilers = level.getEntities(ModEntities.SMILER.get()) { true }
        smilers.forEach { it.discard() }
        visits.clear()
        return smilers.size
    }

    private fun stageOf(player: ServerPlayer): Stage? {
        val level = player.serverLevel().takeIf { it.dimension() == SubstratumLevels.LEVEL_0 } ?: return null
        val layout = (level.chunkSource.generator as? MazeChunkGenerator)?.layout ?: return null
        return Stage(level, layout)
    }

    private fun company(stage: Stage, player: ServerPlayer): Boolean =
        stage.players.any { it !== player && !it.isSpectator && it.isAlive && it.distanceToSqr(player) < COMPANY * COMPANY }

    private fun summon(stage: Stage, victim: ServerPlayer, approaches: List<Approach>, from: Double): Visit? {
        if (abs(victim.y - Spots.FLOOR) > FOOTING) return null
        val origin = Spots.origin(stage.layout, victim.blockX, victim.blockZ) ?: return null
        val band = ServerConfig.reality.smilerDistance
        val field = Corridors(stage.layout, origin.x, origin.z, Spots.fieldRadius(band))
        val scene = stage.scene(victim) ?: return null
        for (approach in approaches) {
            val spot = Spots.find(scene, field, band, approach, random, stage::weigh) ?: continue
            val visit = Visit(victim.uuid, approach, band, random, from)
            visit.arrive(stage, spot, approach == Approach.AHEAD, victim)
            return visit
        }
        return null
    }

    private fun order(last: Approach?, company: Boolean): List<Approach> {
        val weights = WEIGHTS.mapValues { (approach, weight) ->
            weight * (if (approach == last) REPEAT else 1.0) * (if (company && approach == Approach.BEHIND) CROWDED_BEHIND else 1.0)
        }.toMutableMap()
        return buildList {
            while (weights.isNotEmpty()) {
                val next = Spots.pick(weights.keys.toList(), weights.values.toList(), random) ?: break
                add(next)
                weights.remove(next)
            }
        }
    }
}
