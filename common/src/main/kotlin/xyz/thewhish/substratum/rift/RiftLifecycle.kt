package xyz.thewhish.substratum.rift

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.TicketType
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import xyz.thewhish.substratum.config.ServerConfig
import xyz.thewhish.substratum.level.SubstratumLevels
import xyz.thewhish.substratum.network.ViewportSync
import java.util.Comparator
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2

object RiftLifecycle {

    private class ActiveLink(
        val outer: ResourceKey<Level>,
        val link: PortalLink,
        val corridor: List<BlockPos>,
        val owner: UUID,
        val targetDimension: ResourceKey<Level>,
        val closeDelayTicks: Long,
        var lastPresentTick: Long
    ) {
        var firstSeenTick: Long? = null
        val mazeIgnore: List<BlockPos> = corridor.flatMap { listOf(it, it.above()) }
    }

    private data class RiftKey(val dimension: ResourceKey<Level>, val pos: BlockPos)

    private val active = HashMap<RiftKey, ActiveLink>()

    private const val SEEN_RADIUS = 64.0

    internal const val PRESENCE_RADIUS = 64.0

    private const val ABANDON_TICKS = 200L

    private const val CAMERA_DISTANCE = 4.0

    private val WORLD_UP = Vec3(0.0, 1.0, 0.0)

    private val SEEN_MARGIN = Math.toRadians(2.0)

    private val RIFT_TICKET: TicketType<BlockPos> =
        TicketType.create("substratum_rift", Comparator.comparingLong(BlockPos::asLong))
    private const val TICKET_RADIUS = 1

    fun register() {
        TickEvent.SERVER_POST.register(::tick)
        LifecycleEvent.SERVER_STOPPING.register(::closeAll)
    }

    private fun closeAll(server: MinecraftServer) {
        if (active.isEmpty()) return
        val entries = active.values.toList()
        active.clear()
        entries.forEach { fullClose(server, it) }
    }

    fun closeIfTracked(level: ServerLevel, chunk: ChunkPos) {
        val key = active.entries.firstOrNull { (key, entry) ->
            (key.dimension == level.dimension() && ChunkPos(key.pos) == chunk) ||
                (level.dimension() == SubstratumLevels.LEVEL_0 && entry.corridor.any { ChunkPos(it) == chunk })
        }?.key ?: return
        val entry = active.remove(key) ?: return
        fullClose(level.server, entry)
    }

    fun open(
        outer: ServerLevel,
        link: PortalLink,
        corridor: List<BlockPos>,
        owner: UUID,
        targetDimension: ResourceKey<Level> = SubstratumLevels.LEVEL_0,
        closeDelayTicks: Long = ServerConfig.riftSeenDelayTicks
    ) {
        val key = RiftKey(outer.dimension(), link.rift)
        active.remove(key)?.let { teardownFarSide(outer.server, it) }
        RiftPortals.open(outer, link, targetDimension)
        active[key] = ActiveLink(
            outer.dimension(), link, corridor, owner = owner, targetDimension = targetDimension,
            closeDelayTicks = closeDelayTicks, lastPresentTick = outer.server.tickCount.toLong()
        )
        RiftJournal.add(outer.server, outer.dimension(), link.rift)
        outer.chunkSource.addRegionTicket(RIFT_TICKET, ChunkPos(link.rift), TICKET_RADIUS, link.rift)
        outer.server.getLevel(targetDimension)?.chunkSource
            ?.addRegionTicket(RIFT_TICKET, ChunkPos(link.target), TICKET_RADIUS, link.target)
    }

    fun hasActiveExit(owner: UUID): Boolean =
        active.values.any { it.owner == owner && it.targetDimension != SubstratumLevels.LEVEL_0 }

    fun hasActiveEntrance(owner: UUID): Boolean =
        active.values.any { it.owner == owner && it.targetDimension == SubstratumLevels.LEVEL_0 }

    fun closeActiveEntrance(server: MinecraftServer, owner: UUID) {
        val key = active.entries
            .firstOrNull { (_, entry) -> entry.owner == owner && entry.targetDimension == SubstratumLevels.LEVEL_0 }
            ?.key ?: return
        active.remove(key)?.let { fullClose(server, it) }
    }

    private fun tick(server: MinecraftServer) {
        if (active.isEmpty()) return
        val players = server.playerList.players
        for (key in active.keys.toList()) {
            val entry = active[key] ?: continue
            tickOne(server, players, key, entry)
        }
    }

    private fun tickOne(server: MinecraftServer, players: List<ServerPlayer>, key: RiftKey, entry: ActiveLink) {
        val outer = server.getLevel(entry.outer) ?: return
        val maze = server.getLevel(SubstratumLevels.LEVEL_0) ?: return
        val target = server.getLevel(entry.targetDimension) ?: return
        val link = entry.link
        val now = server.tickCount.toLong()
        val present = players.any { near(it, outer, link.rift) || near(it, maze, entry.corridor.first()) || near(it, target, link.target) }
        if (present) {
            entry.lastPresentTick = now
        } else if (now - entry.lastPresentTick >= ABANDON_TICKS) {
            active.remove(key)
            fullClose(server, entry)
            return
        }
        val outerBox = RiftPortals.slitBox(link)
        val mazeBox = RiftPortals.corridorFace(entry.corridor.first(), link.out)
        val targetBox = RiftPortals.destinationBox(link)
        val outerCells = listOf(link.rift, link.rift.above())
        val targetCells = listOf(link.target, link.target.above())
        val outerNormal = direction(link.mouth)
        val mazeNormal = direction(link.out)
        val seen = players.any { isFacing(it, outer, outerBox, outerCells, outerNormal) } ||
            players.any { isFacing(it, maze, mazeBox, entry.mazeIgnore, mazeNormal) } ||
            players.any { isFacing(it, target, targetBox, targetCells, mazeNormal) }

        val firstSeen = entry.firstSeenTick
        if (firstSeen == null) {
            if (seen) entry.firstSeenTick = server.tickCount.toLong()
            return
        }
        if (server.tickCount - firstSeen < entry.closeDelayTicks) return
        if (seen) return
        if (players.any { occupies(it, outer, entry.link.rift) }) return
        if (players.any { player -> entry.corridor.any { occupies(player, maze, it) } }) return
        if (players.any { occupies(it, target, entry.link.target) }) return

        active.remove(key)
        fullClose(server, entry)
    }

    private class ViewBasis(val forward: Vec3, val right: Vec3, val up: Vec3) {
        companion object {
            fun of(forward: Vec3): ViewBasis {
                var right = forward.cross(WORLD_UP)
                if (right.lengthSqr() < 1.0e-6) right = Vec3(1.0, 0.0, 0.0)
                right = right.normalize()
                return ViewBasis(forward, right, right.cross(forward).normalize())
            }
        }

        fun angles(eye: Vec3, point: Vec3): Pair<Double, Double>? {
            val toPoint = point.subtract(eye)
            val forwardComp = toPoint.dot(forward)
            if (forwardComp <= 0.0) return null
            return atan2(abs(toPoint.dot(right)), forwardComp) to atan2(abs(toPoint.dot(up)), forwardComp)
        }
    }

    private class CameraView(val eye: Vec3, val forward: Vec3)

    private fun cameraView(player: ServerPlayer, level: ServerLevel): CameraView {
        val lookAngle = player.lookAngle
        val eye = player.eyePosition
        return when (ViewportSync.of(player).perspective) {
            ViewportSync.Perspective.FIRST_PERSON -> CameraView(eye, lookAngle)
            ViewportSync.Perspective.THIRD_PERSON_BACK ->
                CameraView(pullCamera(level, player, eye, lookAngle.scale(-1.0)), lookAngle)
            ViewportSync.Perspective.THIRD_PERSON_FRONT ->
                CameraView(pullCamera(level, player, eye, lookAngle), lookAngle.scale(-1.0))
        }
    }

    private fun pullCamera(level: ServerLevel, player: ServerPlayer, eye: Vec3, direction: Vec3): Vec3 {
        val desired = eye.add(direction.scale(CAMERA_DISTANCE))
        val hit = level.clip(ClipContext(eye, desired, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        return if (hit.type == HitResult.Type.MISS) desired else hit.location
    }

    private fun boxCorners(box: AABB): List<Vec3> = listOf(
        Vec3(box.minX, box.minY, box.minZ), Vec3(box.minX, box.minY, box.maxZ),
        Vec3(box.minX, box.maxY, box.minZ), Vec3(box.minX, box.maxY, box.maxZ),
        Vec3(box.maxX, box.minY, box.minZ), Vec3(box.maxX, box.minY, box.maxZ),
        Vec3(box.maxX, box.maxY, box.minZ), Vec3(box.maxX, box.maxY, box.maxZ)
    )

    private fun isFacing(
        player: ServerPlayer,
        level: ServerLevel,
        box: AABB,
        ignoring: List<BlockPos>,
        viewNormal: Vec3
    ): Boolean {
        if (player.level() !== level) return false
        if (player.eyePosition.distanceToSqr(box.center) > (SEEN_RADIUS + CAMERA_DISTANCE).let { it * it }) return false
        val camera = cameraView(player, level)
        val eye = camera.eye
        val center = box.center
        val toCenter = center.subtract(eye)
        val distanceSq = toCenter.lengthSqr()
        if (distanceSq > SEEN_RADIUS * SEEN_RADIUS || distanceSq < 1.0e-6) return false
        if (toCenter.dot(viewNormal) >= 0.0) return false

        val basis = ViewBasis.of(camera.forward)
        val viewport = ViewportSync.of(player)
        val framed = (boxCorners(box) + center).any { point ->
            val (horizontal, vertical) = basis.angles(eye, point) ?: return@any false
            horizontal <= viewport.halfHFov + SEEN_MARGIN && vertical <= viewport.halfVFov + SEEN_MARGIN
        }
        if (!framed) return false

        return hasAnyClearView(level, player, eye, basis.right, box, viewNormal, ignoring)
    }

    private fun hasClearView(level: ServerLevel, viewer: ServerPlayer, from: Vec3, to: Vec3, ignoring: List<BlockPos>): Boolean {
        val context = ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer)
        val hit = level.clip(context)
        if (hit.type == HitResult.Type.MISS) return true
        return hit is BlockHitResult && hit.blockPos in ignoring
    }

    private const val SAMPLE_INSET = 0.03

    private const val DEPTH_INSET = 0.25

    private fun sightSamples(box: AABB, viewNormal: Vec3): List<Vec3> {
        val center = box.center.add(viewNormal.scale(DEPTH_INSET))
        val narrowOnX = box.maxX - box.minX < box.maxZ - box.minZ
        val half = (if (narrowOnX) box.maxX - box.minX else box.maxZ - box.minZ) / 2.0 - SAMPLE_INSET
        val vertical = (box.maxY - box.minY) / 2.0 - SAMPLE_INSET
        val dx = if (narrowOnX) half else 0.0
        val dz = if (narrowOnX) 0.0 else half
        return listOf(
            center,
            center.add(dx, 0.0, dz),
            center.add(-dx, 0.0, -dz),
            center.add(0.0, vertical, 0.0),
            center.add(0.0, -vertical, 0.0)
        )
    }

    private const val EYE_PEEK = 0.3

    private fun hasAnyClearView(
        level: ServerLevel,
        viewer: ServerPlayer,
        eye: Vec3,
        right: Vec3,
        box: AABB,
        viewNormal: Vec3,
        ignoring: List<BlockPos>
    ): Boolean {
        val eyes = listOf(eye, eye.add(right.scale(EYE_PEEK)), eye.add(right.scale(-EYE_PEEK)))
        val targets = sightSamples(box, viewNormal)
        return eyes.any { from -> targets.any { to -> hasClearView(level, viewer, from, to, ignoring) } }
    }

    private fun direction(face: Direction): Vec3 =
        Vec3(face.stepX.toDouble(), face.stepY.toDouble(), face.stepZ.toDouble())

    private fun near(player: ServerPlayer, level: ServerLevel, pos: BlockPos): Boolean =
        player.level() === level && player.distanceToSqr(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5) <= PRESENCE_RADIUS * PRESENCE_RADIUS

    private fun occupies(player: ServerPlayer, level: ServerLevel, rift: BlockPos): Boolean {
        if (player.level() !== level) return false
        val box = AABB(rift.x.toDouble(), rift.y.toDouble(), rift.z.toDouble(), rift.x + 1.0, rift.y + 2.0, rift.z + 1.0)
        return player.boundingBox.intersects(box)
    }

    private fun teardownFarSide(server: MinecraftServer, entry: ActiveLink) {
        val outer = server.getLevel(entry.outer) ?: return
        val maze = server.getLevel(SubstratumLevels.LEVEL_0) ?: return
        val target = server.getLevel(entry.targetDimension) ?: return
        outer.chunkSource.removeRegionTicket(RIFT_TICKET, ChunkPos(entry.link.rift), TICKET_RADIUS, entry.link.rift)
        target.chunkSource.removeRegionTicket(RIFT_TICKET, ChunkPos(entry.link.target), TICKET_RADIUS, entry.link.target)
        RiftPortals.close(outer, entry.link, entry.targetDimension)
        entry.corridor.forEach { Rifts.close(maze, it) }
    }

    private fun fullClose(server: MinecraftServer, entry: ActiveLink) {
        RiftJournal.remove(server, entry.outer, entry.link.rift)
        teardownFarSide(server, entry)
        RiftSpawnData.onRiftClosed(server, entry.owner, server.overworld().gameTime / RiftSpawnData.DAY_TICKS)
        server.getLevel(entry.outer)?.let { Rifts.close(it, entry.link.rift) }
    }
}
