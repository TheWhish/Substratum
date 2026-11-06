package xyz.thewhish.substratum.gaze

import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import xyz.thewhish.substratum.network.ProbeSync
import xyz.thewhish.substratum.worldgen.Corridors
import java.util.UUID
import kotlin.math.floor

object Probe {

    const val STEP = 2
    const val REACH = 10
    const val RISE = 4
    const val MAX_SOON = 256
    const val MAX_POINTS = (2 * REACH + 1) * (2 * REACH + 1) * (2 * RISE + 1)
    private const val SOON_RADIUS = 12

    private enum class Mode { LIVE, FREEZING, FROZEN }

    private val sessions = HashMap<UUID, Mode>()

    fun toggle(player: ServerPlayer): Boolean {
        if (sessions[player.uuid] == Mode.LIVE) {
            stop(player)
            return false
        }
        sessions[player.uuid] = Mode.LIVE
        return true
    }

    fun freeze(player: ServerPlayer) {
        sessions[player.uuid] = Mode.FREEZING
    }

    fun stop(player: ServerPlayer) {
        if (sessions.remove(player.uuid) != null) ProbeSync.off(player)
    }

    fun stop(uuid: UUID) {
        sessions.remove(uuid)
    }

    fun tick(server: MinecraftServer) {
        if (sessions.isEmpty()) return
        for ((uuid, mode) in sessions) {
            if (mode == Mode.FROZEN) continue
            val player = server.playerList.getPlayer(uuid) ?: continue
            ProbeSync.send(player, frame(player, frozen = mode == Mode.FREEZING) ?: continue)
            if (mode == Mode.FREEZING) sessions[uuid] = Mode.FROZEN
        }
    }

    private fun frame(player: ServerPlayer, frozen: Boolean): ProbeSync.Frame? {
        val started = System.nanoTime()
        val level = player.serverLevel()
        val gaze = Watch.gaze(level)
        val viewer = gaze.viewer(player.uuid) ?: return null
        val views = gaze.viewersOf(player.uuid)
        val eye = player.eyePosition
        val originX = Math.floorDiv(floor(eye.x).toInt(), STEP) * STEP
        val originY = Math.floorDiv(floor(eye.y).toInt(), STEP) * STEP
        val originZ = Math.floorDiv(floor(eye.z).toInt(), STEP) * STEP
        val points = ByteArray(MAX_POINTS * 4)
        var size = 0
        val cursor = BlockPos.MutableBlockPos()
        for (dx in -REACH..REACH) for (dy in -RISE..RISE) for (dz in -REACH..REACH) {
            cursor.set(originX + dx * STEP, originY + dy * STEP, originZ + dz * STEP)
            if (!level.isLoaded(cursor) || !level.getBlockState(cursor).getCollisionShape(level, cursor).isEmpty) continue
            val point = Vec3(cursor.x + 0.5, cursor.y + 0.5, cursor.z + 0.5)
            val sight = views.maxOf { gaze.judge(it, point, glow = false) }
            points[size++] = dx.toByte()
            points[size++] = dy.toByte()
            points[size++] = dz.toByte()
            points[size++] = sight.ordinal.toByte()
        }
        val layout = gaze.layout
        val soon = if (layout == null) emptyList() else gaze.soon(viewer, Corridors(layout, player.blockX, player.blockZ, SOON_RADIUS)).take(MAX_SOON)
        return ProbeSync.Frame(
            active = true,
            frozen = frozen,
            kind = viewer.kind,
            perspective = viewer.viewport.perspective,
            eye = viewer.eye,
            forward = viewer.forward,
            halfH = viewer.halfH.toFloat(),
            halfV = viewer.halfV.toFloat(),
            margin = viewer.margin.toFloat(),
            range = viewer.range.toFloat(),
            fov = viewer.viewport.vFovDegrees,
            latency = player.connection.latency(),
            micros = ((System.nanoTime() - started) / 1000).toInt(),
            windows = views.size - 1,
            origin = BlockPos(originX, originY, originZ),
            points = points.copyOf(size),
            soon = soon,
        )
    }
}
