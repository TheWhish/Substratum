package xyz.thewhish.substratum.gaze

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import qouteall.imm_ptl.core.portal.Portal
import xyz.thewhish.substratum.level.SubstratumLevels
import xyz.thewhish.substratum.network.ViewportSync
import xyz.thewhish.substratum.rift.RiftLifecycle
import xyz.thewhish.substratum.worldgen.MazeChunkGenerator
import xyz.thewhish.substratum.worldgen.MazeLayout
import java.util.UUID
import kotlin.math.acos
import kotlin.math.floor
import kotlin.math.min

object Watch {

    private const val HISTORY = 20
    private const val STEP_TICKS = 2
    private const val REMEMBER_EVERY = 10
    private const val REMEMBER_RADIUS = 6
    private const val REMEMBER_STRIDE = 2
    private const val FORGET_TICKS = 20L * 60 * 10
    private const val CELL_RADIUS = 8.0
    private const val PORTAL_REACH = 64.0
    private const val PORTAL_INSET = 0.9
    private const val PORTAL_LIFT = 0.05

    private class Turns {
        val rates = DoubleArray(HISTORY)
        val feet = arrayOfNulls<Vec3>(HISTORY)
        var head = 0
        var look: Vec3? = null
    }

    private val turns = HashMap<UUID, Turns>()
    private val memories = HashMap<UUID, HashMap<Long, Long>>()
    private val own = HashMap<ResourceKey<Level>, Gaze>()
    private val snapshots = HashMap<ResourceKey<Level>, Gaze>()

    fun register() {
        TickEvent.SERVER_PRE.register(::track)
        TickEvent.SERVER_POST.register(::observe)
        PlayerEvent.PLAYER_QUIT.register { forget(it.uuid) }
        LifecycleEvent.SERVER_STOPPING.register {
            turns.clear()
            memories.clear()
            own.clear()
            snapshots.clear()
        }
    }

    fun gaze(level: ServerLevel): Gaze = snapshots.getOrPut(level.dimension()) {
        val home = own(level)
        val through = level.server.allLevels.flatMap { windows(it, level) }
        if (through.isEmpty()) home else Gaze(home.layout, home.viewers + through, level, home.cuts)
    }

    fun remember(gaze: Gaze, viewer: Viewer, memory: MutableMap<Long, Long>, now: Long) {
        val cellX = Math.floorDiv(floor(viewer.eye.x).toInt(), MazeLayout.CELL)
        val cellZ = Math.floorDiv(floor(viewer.eye.z).toInt(), MazeLayout.CELL)
        for (cx in cellX - REMEMBER_RADIUS..cellX + REMEMBER_RADIUS) for (cz in cellZ - REMEMBER_RADIUS..cellZ + REMEMBER_RADIUS) {
            val x0 = cx * MazeLayout.CELL
            val z0 = cz * MazeLayout.CELL
            val centre = Vec3(x0 + MazeLayout.CELL / 2.0, viewer.eye.y, z0 + MazeLayout.CELL / 2.0)
            if (!viewer.mayFrame(centre, CELL_RADIUS)) continue
            val points = gaze.area(x0, z0, x0 + MazeLayout.CELL - 1, z0 + MazeLayout.CELL - 1, REMEMBER_STRIDE)
            if (gaze.sees(viewer, points, glow = false)) memory[Gaze.key(cx, cz)] = now
        }
        memory.values.removeIf { now - it > FORGET_TICKS }
    }

    fun windowOf(portal: Portal): Viewer.Window = Viewer.Window(
        portal.originPos, portal.normal, portal.axisW, portal.axisH, portal.width / 2.0, portal.height / 2.0,
    )

    fun through(viewer: Viewer, portal: Portal): Viewer {
        val axisW = portal.transformLocalVec(portal.axisW)
        val axisH = portal.transformLocalVec(portal.axisH)
        val window = Viewer.Window(
            portal.transformPoint(portal.originPos),
            portal.transformLocalVec(portal.normal).normalize(),
            axisW.normalize(),
            axisH.normalize(),
            axisW.length() * portal.width / 2.0,
            axisH.length() * portal.height / 2.0,
        )
        return viewer.through(portal.transformPoint(viewer.eye), portal.transformLocalVec(viewer.forward).normalize(), window)
    }

    private fun own(level: ServerLevel): Gaze = own.getOrPut(level.dimension()) {
        val layout = if (level.dimension() == SubstratumLevels.LEVEL_0) (level.chunkSource.generator as? MazeChunkGenerator)?.layout else null
        val cuts = if (layout == null) emptySet() else RiftLifecycle.mazeCuts().mapTo(HashSet()) { Gaze.key(it.x, it.z) }
        Gaze(layout, level.players().map { viewer(level, layout != null, it) }, level, cuts)
    }

    private fun windows(from: ServerLevel, into: ServerLevel): List<Viewer> {
        if (from.players().isEmpty()) return emptyList()
        val home = own(from)
        val found = ArrayList<Viewer>()
        for (viewer in home.viewers) {
            val area = AABB(viewer.eye, viewer.eye).inflate(PORTAL_REACH)
            for (portal in from.getEntitiesOfClass(Portal::class.java, area) { it.isVisible && it.destDim == into.dimension() }) {
                if (portal.isInFrontOfPortal(viewer.eye) && looksInto(home, viewer, portal)) found.add(through(viewer, portal))
            }
        }
        return found
    }

    private fun looksInto(home: Gaze, viewer: Viewer, portal: Portal): Boolean {
        val window = windowOf(portal)
        val lift = window.normal.scale(PORTAL_LIFT)
        val probes = listOf(
            window.centre,
            window.centre.add(window.axisW.scale(window.halfW * PORTAL_INSET)).add(window.axisH.scale(window.halfH * PORTAL_INSET)),
            window.centre.add(window.axisW.scale(window.halfW * PORTAL_INSET)).subtract(window.axisH.scale(window.halfH * PORTAL_INSET)),
            window.centre.subtract(window.axisW.scale(window.halfW * PORTAL_INSET)).add(window.axisH.scale(window.halfH * PORTAL_INSET)),
            window.centre.subtract(window.axisW.scale(window.halfW * PORTAL_INSET)).subtract(window.axisH.scale(window.halfH * PORTAL_INSET)),
        ).map { it.add(lift) }
        return probes.any { home.judge(viewer, it, glow = false, anyTurn = true).let { sight -> sight == Gaze.Sight.SEEN || sight == Gaze.Sight.DARK } }
    }

    private fun track(server: MinecraftServer) {
        own.clear()
        snapshots.clear()
        for (player in server.playerList.players) {
            val turn = turns.getOrPut(player.uuid) { Turns() }
            val look = player.lookAngle
            val before = turn.look
            turn.look = look
            turn.head = (turn.head + 1) % HISTORY
            turn.rates[turn.head] = if (before == null) 0.0 else acos(before.dot(look).coerceIn(-1.0, 1.0))
            turn.feet[turn.head] = player.position()
        }
    }

    private fun observe(server: MinecraftServer) {
        Probe.tick(server)
        val now = server.tickCount
        val level = server.getLevel(SubstratumLevels.LEVEL_0) ?: return
        if (level.players().isEmpty()) return
        val gaze = own(level)
        for (viewer in gaze.viewers) {
            if (Math.floorMod(now + viewer.uuid.hashCode(), REMEMBER_EVERY) != 0) continue
            remember(gaze, viewer.steady(), memories.getOrPut(viewer.uuid) { HashMap() }, now.toLong())
        }
    }

    private fun viewer(level: ServerLevel, maze: Boolean, player: ServerPlayer): Viewer {
        val viewport = ViewportSync.of(player)
        val turn = turnOf(player)
        val margin = Viewer.margin(turn, player.connection.latency())
        val (eye, forward) = Viewer.camera(player.eyePosition, player.lookAngle, viewport.perspective) { zoom(level, player, it) }
        val haze = if (maze) viewport.haze.toDouble() else min(level.server.playerList.viewDistance, player.clientInformation().viewDistance()) * 16.0
        val range = Viewer.range(haze, player.getEffect(MobEffects.BLINDNESS)?.duration)
        val stride = Viewer.stride(speedOf(player), player.connection.latency())
        return Viewer(player.uuid, eye, forward, viewport, margin, range, Viewer.kind(player.isSpectator, turn, margin), cylindrical = !maze, stride = stride)
    }

    private fun turnOf(player: ServerPlayer): Double {
        val turn = turns[player.uuid] ?: return 0.0
        val window = Viewer.ahead(player.connection.latency()).coerceIn(2, HISTORY)
        return (0 until window).maxOf { turn.rates[Math.floorMod(turn.head - it, HISTORY)] }
    }

    private fun speedOf(player: ServerPlayer): Double {
        val turn = turns[player.uuid] ?: return 0.0
        val now = turn.feet[turn.head] ?: return 0.0
        val before = turn.feet[Math.floorMod(turn.head - STEP_TICKS, HISTORY)] ?: return 0.0
        return now.distanceTo(before) / STEP_TICKS
    }

    private fun zoom(level: ServerLevel, player: ServerPlayer, direction: Vec3): Double {
        val eye = player.eyePosition
        var zoom = Viewer.CAMERA_DISTANCE * player.scale
        for (probe in Viewer.probes(eye)) {
            val hit = level.clip(ClipContext(probe, probe.add(direction.scale(zoom)), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player))
            if (hit.type != HitResult.Type.MISS) zoom = min(zoom, hit.location.distanceTo(eye))
        }
        return zoom
    }

    private fun forget(uuid: UUID) {
        turns.remove(uuid)
        memories.remove(uuid)
        Probe.stop(uuid)
    }
}
