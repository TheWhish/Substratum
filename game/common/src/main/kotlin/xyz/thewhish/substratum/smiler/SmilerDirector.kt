package xyz.thewhish.substratum.smiler

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import xyz.thewhish.substratum.config.ServerConfig
import xyz.thewhish.substratum.level.SubstratumLevels
import xyz.thewhish.substratum.sanity.SanityTracker
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
    private const val WITNESSES = 24.0
    private val VICTIM_CALM = 5 * 60 * 20L..7 * 60 * 20L
    private val WITNESS_CALM = 3 * 60 * 20L..5 * 60 * 20L
    private const val AFTER_AMBUSH = 2 * 60 * 20L
    private const val AFTER_VISIT = 60 * 20L
    private const val HUNT_EVERY = 4
    private const val TRUSTED_EYE = 3.0
    private const val STRIDE_FOLLOW = 0.2

    private val random = Random.Default
    private val visits = HashMap<UUID, Visit>()
    private val last = HashMap<UUID, Approach>()
    private val ambushes = ArrayList<Ambush>()
    private val calm = HashMap<UUID, Long>()
    private val quiet = HashMap<UUID, Long>()
    private val strides = HashMap<UUID, Stride>()

    private class Stride(var at: Vec3) {
        var velocity: Vec3 = Vec3.ZERO
    }

    fun register() {
        TickEvent.SERVER_POST.register(::tick)
        PlayerEvent.PLAYER_QUIT.register {
            last.remove(it.uuid)
            strides.remove(it.uuid)
        }
        LifecycleEvent.SERVER_STOPPING.register {
            visits.clear()
            last.clear()
            ambushes.clear()
            calm.clear()
            quiet.clear()
            strides.clear()
        }
    }

    private fun tick(server: MinecraftServer) {
        val level = server.getLevel(SubstratumLevels.LEVEL_0) ?: return
        for (player in level.players()) {
            val stride = strides.getOrPut(player.uuid) { Stride(player.position()) }
            stride.velocity = stride.velocity.lerp(player.position().subtract(stride.at), STRIDE_FOLLOW)
            stride.at = player.position()
        }
        val hunting = server.tickCount % HUNT_EVERY == 0
        if (visits.isEmpty() && ambushes.isEmpty() && !hunting) return
        val layout = (level.chunkSource.generator as? MazeChunkGenerator)?.layout ?: return
        val stage = Stage(level, layout)
        visits.values.removeIf { visit ->
            val over = !visit.advance(stage, level.getPlayerByUUID(visit.victim) as? ServerPlayer)
            if (over) quiet.merge(visit.victim, stage.now + AFTER_VISIT, ::maxOf)
            over
        }
        ambushes.removeIf { !it.advance(stage) }
        if (hunting) hunt(stage)
    }

    private fun hunt(stage: Stage) {
        val pace = ServerConfig.reality.ambush
        if (!ServerConfig.reality.enabled || pace.frequency <= 0.0) return
        for (player in stage.players.shuffled(random)) {
            if (due(stage, player, pace) && lie(stage, player) != null) return
        }
    }

    private fun due(stage: Stage, player: ServerPlayer, pace: ServerConfig.Pace): Boolean =
        Ambush.prey(player) && SanityTracker.pressure(player) >= pace.pressure && stage.now >= (calm[player.uuid] ?: 0L) &&
            Corners.tight(stage.layout::columnAt, player.blockX, player.blockZ) && ready(player)

    fun ready(player: ServerPlayer): Boolean {
        if (player.uuid in visits || visits.size + ambushes.size >= MAX_AT_ONCE) return false
        if (player.serverLevel().gameTime < (quiet[player.uuid] ?: 0L)) return false
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

    fun ambush(player: ServerPlayer): Corners.Lair? = stageOf(player)?.let { lie(it, player) }

    private fun lie(stage: Stage, player: ServerPlayer): Corners.Lair? {
        if (abs(player.y - Spots.FLOOR) > FOOTING) return null
        val viewer = stage.gaze.viewer(player.uuid) ?: return null
        val moving = strides[player.uuid]?.velocity ?: Vec3.ZERO
        val lair = Corners.find(stage.gaze, stage.layout, viewer, player.position(), moving, random, stage::fits) ?: return null
        val smiler = ModEntities.SMILER.get().create(stage.level) ?: error("the smiler entity type is disabled")
        smiler.moveTo(lair.at.x, lair.at.y, lair.at.z, 0f, 0f)
        smiler.lurk(random.nextInt(Ambush.HOLD.first, Ambush.HOLD.last + 1))
        if (!stage.level.addFreshEntity(smiler)) return null
        ambushes.add(Ambush(smiler, stage.now))
        return lair
    }

    fun disarm() {
        ambushes.removeIf { ambush -> ambush.armed.also { if (it) ambush.smiler.discard() } }
    }

    fun spotted(player: ServerPlayer, entity: Int, reported: Vec3) {
        val ambush = ambushes.firstOrNull { it.smiler.id == entity } ?: return
        val stage = stageOf(player) ?: return
        val eye = reported.takeIf { it.distanceToSqr(player.eyePosition) <= TRUSTED_EYE * TRUSTED_EYE } ?: player.eyePosition
        spring(stage, ambush, player, eye)
    }

    fun struck(player: ServerPlayer, entity: Int) {
        ambushes.firstOrNull { it.smiler.id == entity }?.strike(player)
    }

    private fun spring(stage: Stage, ambush: Ambush, victim: ServerPlayer, eye: Vec3) {
        if (!ambush.spring(stage.now, victim, eye)) return
        for (witness in stage.players) {
            if (witness.distanceToSqr(victim) >= WITNESSES * WITNESSES) continue
            calm.merge(witness.uuid, stage.now + calmFor(WITNESS_CALM), ::maxOf)
            quiet.merge(witness.uuid, stage.now + AFTER_AMBUSH, ::maxOf)
        }
        calm[victim.uuid] = stage.now + calmFor(VICTIM_CALM)
    }

    private fun calmFor(span: LongRange): Long {
        val frequency = ServerConfig.reality.ambush.frequency
        return if (frequency <= 0.0) 0L else (random.nextLong(span.first, span.last + 1) / frequency).toLong()
    }

    fun lost(player: ServerPlayer, entity: Int) {
        visits.values.firstOrNull { it.smiler?.id == entity }?.lose(player)
    }

    fun clear(level: ServerLevel): Int {
        val smilers = level.getEntities(ModEntities.SMILER.get()) { true }
        smilers.forEach { it.discard() }
        visits.clear()
        ambushes.clear()
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
