package xyz.thewhish.substratum.gaze

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import xyz.thewhish.substratum.level.SubstratumLevels
import xyz.thewhish.substratum.registry.ModBlocks
import xyz.thewhish.substratum.registry.ModBlocks.LampCondition
import xyz.thewhish.substratum.worldgen.Corridors
import xyz.thewhish.substratum.worldgen.MazeChunkGenerator
import xyz.thewhish.substratum.worldgen.MazeLayout
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class Gaze(
    val layout: MazeLayout?,
    val viewers: List<Viewer>,
    private val world: ServerLevel? = null,
    val cuts: Set<Long> = emptySet(),
) {

    class Lamp(val x: Int, val z: Int, val point: Vec3, val glow: Double, val sick: Boolean)

    class Lit(val light: Double, val sick: Boolean)

    enum class Sight { FAR, OUT, HIDDEN, DARK, SEEN }

    class Patch(val points: List<Vec3>) {
        val centre: Vec3
        val radius: Double

        init {
            var low = Vec3(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
            var high = Vec3(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
            for (point in points) {
                low = Vec3(min(low.x, point.x), min(low.y, point.y), min(low.z, point.z))
                high = Vec3(max(high.x, point.x), max(high.y, point.y), max(high.z, point.z))
            }
            centre = if (points.isEmpty()) Vec3.ZERO else low.add(high).scale(0.5)
            radius = if (points.isEmpty()) 0.0 else high.distanceTo(low) / 2.0
        }
    }

    private val lamps = HashMap<Long, Lamp>()
    private val lit = HashMap<Vec3, Lit>()
    private val columns = Long2IntOpenHashMap().apply { defaultReturnValue(UNKNOWN) }
    private val along = DoubleArray(POLYGON)
    private val side = DoubleArray(POLYGON)
    private val clippedAlong = DoubleArray(POLYGON)
    private val clippedSide = DoubleArray(POLYGON)
    private val triangleX = DoubleArray(3)
    private val triangleZ = DoubleArray(3)
    private val stripZ = DoubleArray(2)
    private val peeked = HashMap<Viewer, Long2ByteOpenHashMap>()
    private val walled = HashMap<Viewer, Long2ByteOpenHashMap>()
    private val cutSet = LongOpenHashSet(cuts)
    private var mergedFrom = DoubleArray(SHADOWS)
    private var mergedTo = DoubleArray(SHADOWS)
    private var merged = 0

    private fun reached(viewer: Viewer, from: Vec3, point: Vec3): Boolean {
        if (layout == null || viewer.window != null) return clear(from, point)
        val x = floor(point.x)
        val z = floor(point.z)
        if (point.x - x != 0.5 || point.z - z != 0.5) return clear(from, point)
        val known = walled.getOrPut(viewer) { Long2ByteOpenHashMap().apply { defaultReturnValue(UNTESTED) } }
        val key = key(x.toInt(), z.toInt())
        var state = known.get(key)
        if (state == UNTESTED) {
            state = if (solidBetween(from, point)) BLOCKED else PEEKS
            known.put(key, state)
        }
        return state != BLOCKED && clear(from, point)
    }

    private fun solidBetween(from: Vec3, to: Vec3): Boolean {
        val dx = to.x - from.x
        val dz = to.z - from.z
        var cellX = floor(from.x).toInt()
        var cellZ = floor(from.z).toInt()
        val endX = floor(to.x).toInt()
        val endZ = floor(to.z).toInt()
        val stepX = if (dx > 0) 1 else -1
        val stepZ = if (dz > 0) 1 else -1
        val spanX = if (dx == 0.0) Double.POSITIVE_INFINITY else abs(1.0 / dx)
        val spanZ = if (dz == 0.0) Double.POSITIVE_INFINITY else abs(1.0 / dz)
        var nextX = if (dx == 0.0) Double.POSITIVE_INFINITY else (if (dx > 0) cellX + 1 - from.x else from.x - cellX) * spanX
        var nextZ = if (dz == 0.0) Double.POSITIVE_INFINITY else (if (dz > 0) cellZ + 1 - from.z else from.z - cellZ) * spanZ
        while (true) {
            if (column(cellX, cellZ) and MazeLayout.SOLID != 0 && !cut(cellX, cellZ)) return true
            if (cellX == endX && cellZ == endZ) return false
            if (nextX >= 1.0 && nextZ >= 1.0) return false
            if (nextX <= nextZ) {
                cellX += stepX
                nextX += spanX
            } else {
                cellZ += stepZ
                nextZ += spanZ
            }
        }
    }

    private fun cut(x: Int, z: Int): Boolean = !cutSet.isEmpty() && cutSet.contains(key(x, z))

    fun viewer(uuid: UUID): Viewer? = viewers.firstOrNull { it.uuid == uuid && it.window == null }

    fun viewersOf(uuid: UUID): List<Viewer> = viewers.filter { it.uuid == uuid }

    fun column(x: Int, z: Int): Int {
        val key = key(x, z)
        val known = columns.get(key)
        if (known != UNKNOWN) return known
        return checkNotNull(layout).columnAt(x, z).also { columns.put(key, it) }
    }

    fun clear(from: Vec3, to: Vec3): Boolean {
        if (layout == null) return clearBlocks(from, to)
        return travel(from, to) >= 1.0
    }

    private fun travel(from: Vec3, to: Vec3): Double {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        var cellX = floor(from.x).toInt()
        var cellZ = floor(from.z).toInt()
        val stepX = if (dx > 0) 1 else -1
        val stepZ = if (dz > 0) 1 else -1
        val spanX = if (dx == 0.0) Double.POSITIVE_INFINITY else abs(1.0 / dx)
        val spanZ = if (dz == 0.0) Double.POSITIVE_INFINITY else abs(1.0 / dz)
        var nextX = if (dx == 0.0) Double.POSITIVE_INFINITY else (if (dx > 0) cellX + 1 - from.x else from.x - cellX) * spanX
        var nextZ = if (dz == 0.0) Double.POSITIVE_INFINITY else (if (dz > 0) cellZ + 1 - from.z else from.z - cellZ) * spanZ
        var enter = 0.0
        while (true) {
            val leave = min(min(nextX, nextZ), 1.0)
            if (!open(column(cellX, cellZ), cut(cellX, cellZ), from.y + dy * enter, from.y + dy * leave)) return enter
            if (leave >= 1.0) return 1.0
            enter = leave
            if (nextX <= nextZ) {
                cellX += stepX
                nextX += spanX
            } else {
                cellZ += stepZ
                nextZ += spanZ
            }
        }
    }

    fun clearBlocks(from: Vec3, to: Vec3, ignoring: Collection<BlockPos> = emptyList()): Boolean {
        val world = world ?: return layout == null || clear(from, to)
        val hit = world.clip(ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()))
        return hit.type == HitResult.Type.MISS || hit is BlockHitResult && hit.blockPos in ignoring
    }

    fun judge(viewer: Viewer, point: Vec3, glow: Boolean, anyTurn: Boolean = false): Sight {
        if (!viewer.reaches(point, range(viewer, glow))) return Sight.FAR
        if (!anyTurn && !viewer.frames(point)) return Sight.OUT
        val window = viewer.window
        val from = if (window == null) viewer.eye else window.crossing(viewer.eye, point) ?: return Sight.OUT
        if (viewer.kind == Viewer.Kind.EVERYWHERE) return Sight.SEEN
        if (!reached(viewer, from, point) && (window != null || !aside(viewer, point))) return Sight.HIDDEN
        if (glow || layout == null || point.distanceToSqr(viewer.eye) <= DARK_SIGHT * DARK_SIGHT || lit(point).light >= NOTICED) return Sight.SEEN
        return Sight.DARK
    }

    fun sees(viewer: Viewer, point: Vec3, glow: Boolean, anyTurn: Boolean = false): Boolean = judge(viewer, point, glow, anyTurn) == Sight.SEEN

    fun sees(viewer: Viewer, patch: Patch, glow: Boolean, anyTurn: Boolean = false): Boolean {
        if (patch.points.isEmpty() || !viewer.reaches(patch.centre, range(viewer, glow) + patch.radius)) return false
        if (!anyTurn && !viewer.mayFrame(patch.centre, patch.radius)) return false
        return patch.points.any { sees(viewer, it, glow, anyTurn) }
    }

    fun unseen(patch: Patch, glow: Boolean, anyTurn: Boolean = false): Boolean = viewers.none { sees(it, patch, glow, anyTurn) }

    fun area(x0: Int, z0: Int, x1: Int, z1: Int, stride: Int = 1): Patch {
        if (layout == null) return Patch(emptyList())
        val points = ArrayList<Vec3>()
        for (x in min(x0, x1)..max(x0, x1) step stride) for (z in min(z0, z1)..max(z0, z1) step stride) {
            val column = column(x, z)
            if (column and MazeLayout.SOLID != 0 && !cut(x, z)) continue
            val ceiling = if (column and MazeLayout.SOLID != 0) CUT_CEILING else MazeLayout.ceilingOf(column).toDouble()
            for (y in doubleArrayOf(FLOOR + SKIN, FLOOR + EYE, ceiling - SKIN)) points.add(Vec3(x + 0.5, y, z + 0.5))
        }
        return Patch(points)
    }

    fun cell(cellX: Int, cellZ: Int): Patch =
        area(cellX * MazeLayout.CELL, cellZ * MazeLayout.CELL, cellX * MazeLayout.CELL + MazeLayout.CELL - 1, cellZ * MazeLayout.CELL + MazeLayout.CELL - 1)

    fun soon(viewer: Viewer, field: Corridors): List<Vec3> {
        val look = Vec3(viewer.forward.x, 0.0, viewer.forward.z)
        if (look.lengthSqr() < 1.0E-4) return emptyList()
        val ahead = look.normalize()
        val found = ArrayList<Vec3>()
        field.reached { x, z, steps ->
            if (steps !in SOON) return@reached
            val way = Vec3((x - field.originX).toDouble(), 0.0, (z - field.originZ).toDouble()).normalize()
            if (way.dot(ahead) >= SOON_AHEAD) found.add(Vec3(x + 0.5, FLOOR + EYE, z + 0.5))
        }
        return found
    }

    fun rear(at: Vec3, group: List<Viewer>): List<Vec3> =
        DIRECTIONS.filter { direction -> group.none { it.frames(at.add(direction.scale(REAR_PROBE))) } }

    fun lit(point: Vec3): Lit = lit.getOrPut(point) { light(point) }

    private fun aside(viewer: Viewer, point: Vec3): Boolean {
        val stride = viewer.stride
        if (stride <= 0.0) return false
        val eye = viewer.eye
        val depth = hypot(eye.x - point.x, eye.z - point.z)
        if (depth < 1.0e-6) return false
        val across = Vec3((point.z - eye.z) / depth, 0.0, (eye.x - point.x) / depth)
        if (layout != null) return peeks(viewer, point, across, depth)
        for (share in SHIFTS) {
            val shift = across.scale(stride * share)
            if (clear(eye.add(shift), point) || clear(eye.subtract(shift), point)) return true
        }
        val climb = min(stride, CLIMB)
        return clear(eye.add(0.0, climb, 0.0), point) || clear(eye.add(0.0, -climb, 0.0), point)
    }

    private fun peeks(viewer: Viewer, point: Vec3, across: Vec3, depth: Double): Boolean {
        val x = floor(point.x)
        val z = floor(point.z)
        if (point.x - x != 0.5 || point.z - z != 0.5) return shadowed(viewer.eye, point, across, depth, viewer.stride)
        val known = peeked.getOrPut(viewer) { Long2ByteOpenHashMap().apply { defaultReturnValue(UNTESTED) } }
        val key = key(x.toInt(), z.toInt())
        val cached = known.get(key)
        if (cached != UNTESTED) return cached == PEEKS
        return shadowed(viewer.eye, point, across, depth, viewer.stride).also { known.put(key, if (it) PEEKS else BLOCKED) }
    }

    private fun shadowed(eye: Vec3, point: Vec3, across: Vec3, depth: Double, stride: Double): Boolean {
        val left = -stride * travel(eye, eye.subtract(across.scale(stride)))
        val right = stride * travel(eye, eye.add(across.scale(stride)))
        if (right <= left) return false
        val towardX = (eye.x - point.x) / depth
        val towardZ = (eye.z - point.z) / depth
        triangleX[0] = point.x
        triangleZ[0] = point.z
        triangleX[1] = eye.x + across.x * left
        triangleZ[1] = eye.z + across.z * left
        triangleX[2] = eye.x + across.x * right
        triangleZ[2] = eye.z + across.z * right
        merged = 0
        for (x in floor(triangleX.min()).toInt()..floor(triangleX.max()).toInt()) {
            if (!strip(x)) continue
            for (z in floor(stripZ[0]).toInt()..floor(stripZ[1]).toInt()) {
                if (column(x, z) and MazeLayout.SOLID == 0 || cut(x, z)) continue
                for (corner in 0 until 4) {
                    val offX = x + CORNER_X[corner] - point.x
                    val offZ = z + CORNER_Z[corner] - point.z
                    along[corner] = offX * towardX + offZ * towardZ
                    side[corner] = offX * across.x + offZ * across.z
                }
                val kept = clip(clip(4, NEAR, 1.0), depth, -1.0)
                if (kept == 0) continue
                var from = Double.POSITIVE_INFINITY
                var to = Double.NEGATIVE_INFINITY
                for (i in 0 until kept) {
                    val lateral = side[i] * depth / along[i]
                    from = min(from, lateral)
                    to = max(to, lateral)
                }
                if (cover(from, to, left, right)) return false
            }
        }
        return true
    }

    private fun cover(from: Double, to: Double, left: Double, right: Double): Boolean {
        var low = from
        var high = to
        var kept = 0
        for (i in 0 until merged) {
            if (mergedTo[i] < low || mergedFrom[i] > high) {
                mergedFrom[kept] = mergedFrom[i]
                mergedTo[kept++] = mergedTo[i]
            } else {
                low = min(low, mergedFrom[i])
                high = max(high, mergedTo[i])
            }
        }
        if (kept == mergedFrom.size) {
            mergedFrom = mergedFrom.copyOf(kept * 2)
            mergedTo = mergedTo.copyOf(kept * 2)
        }
        mergedFrom[kept] = low
        mergedTo[kept++] = high
        merged = kept
        return low <= left && high >= right
    }

    private fun strip(x: Int): Boolean {
        var low = Double.POSITIVE_INFINITY
        var high = Double.NEGATIVE_INFINITY
        for (i in 0 until 3) {
            val j = (i + 1) % 3
            val ax = triangleX[i]
            val az = triangleZ[i]
            val bx = triangleX[j]
            val bz = triangleZ[j]
            if (ax >= x && ax <= x + 1) {
                low = min(low, az)
                high = max(high, az)
            }
            for (edge in intArrayOf(x, x + 1)) {
                if ((ax - edge) * (bx - edge) > 0.0 || ax == bx) continue
                val z = az + (bz - az) * (edge - ax) / (bx - ax)
                low = min(low, z)
                high = max(high, z)
            }
        }
        if (low > high) return false
        stripZ[0] = low
        stripZ[1] = high
        return true
    }

    private fun clip(count: Int, limit: Double, sign: Double): Int {
        var kept = 0
        for (i in 0 until count) {
            val j = (i + 1) % count
            val here = sign * (along[i] - limit)
            val there = sign * (along[j] - limit)
            if (here >= 0.0) {
                clippedAlong[kept] = along[i]
                clippedSide[kept++] = side[i]
            }
            if ((here >= 0.0) != (there >= 0.0)) {
                val t = here / (here - there)
                clippedAlong[kept] = along[i] + (along[j] - along[i]) * t
                clippedSide[kept++] = side[i] + (side[j] - side[i]) * t
            }
        }
        clippedAlong.copyInto(along, 0, 0, kept)
        clippedSide.copyInto(side, 0, 0, kept)
        return kept
    }

    private fun range(viewer: Viewer, glow: Boolean): Double = if (glow) GLOW_RANGE else viewer.range

    private fun light(point: Vec3): Lit {
        if (layout == null) return Lit(1.0, false)
        val x = floor(point.x).toInt()
        val z = floor(point.z).toInt()
        val cellX = Math.floorDiv(x, MazeLayout.CELL)
        val cellZ = Math.floorDiv(z, MazeLayout.CELL)
        var light = 0.0
        var sick = false
        for (cx in cellX - 1..cellX + 1) for (cz in cellZ - 1..cellZ + 1) {
            val lamp = lamp(cx, cz)
            if (lamp.glow <= 0.0 && !lamp.sick) continue
            val distance = hypot(lamp.x - point.x + 0.5, lamp.z - point.z + 0.5)
            if (distance > LAMP_REACH || !clear(point, lamp.point)) continue
            light += lamp.glow * (1.0 - distance / LAMP_REACH)
            if (lamp.sick && distance <= SICK_REACH) sick = true
        }
        return Lit(light, sick)
    }

    private fun lamp(cellX: Int, cellZ: Int): Lamp = lamps.getOrPut(key(cellX, cellZ)) { readLamp(cellX, cellZ) }

    private fun readLamp(cellX: Int, cellZ: Int): Lamp {
        val x = cellX * MazeLayout.CELL + MazeLayout.CENTRE
        val z = cellZ * MazeLayout.CELL + MazeLayout.CENTRE
        val column = column(x, z)
        val pos = BlockPos(x, MazeLayout.ceilingOf(column), z)
        val condition = when {
            column and MazeLayout.LAMP == 0 -> if (column and MazeLayout.DEAD_LAMP != 0) LampCondition.DEAD else null
            world == null || !world.isLoaded(pos) -> LampCondition.ON
            else -> world.getBlockState(pos).takeIf { it.`is`(ModBlocks.lampBlock) }?.getValue(ModBlocks.LAMP_CONDITION) ?: LampCondition.DEAD
        }
        val glow = when (condition) {
            LampCondition.ON -> 1.0
            LampCondition.FLICKERING -> FLICKER_GLOW
            LampCondition.DIM -> DIM_GLOW
            else -> 0.0
        }
        return Lamp(x, z, Vec3(x + 0.5, pos.y - LAMP_DROP, z + 0.5), glow, condition != null && condition != LampCondition.ON)
    }

    companion object {
        const val GLOW_RANGE = (SubstratumLevels.SIGHT_CHUNKS + 1) * 16.0
        const val REACH = GLOW_RANGE + Viewer.CAMERA_DISTANCE
        const val FLOOR = MazeChunkGenerator.FLOOR_Y + 1.0
        const val EYE = 1.62
        const val DARK_SIGHT = 8.0
        const val NOTICED = 0.1
        const val CUT_CEILING = FLOOR + 2.0
        private const val SKIN = 0.1
        private const val CLIMB = 1.25
        private const val NEAR = 1.0e-6
        private const val POLYGON = 8
        private const val SHADOWS = 32
        private const val UNTESTED: Byte = 0
        private const val PEEKS: Byte = 1
        private const val BLOCKED: Byte = 2
        private val CORNER_X = intArrayOf(0, 1, 1, 0)
        private val CORNER_Z = intArrayOf(0, 0, 1, 1)
        private val SHIFTS = doubleArrayOf(0.125, 0.25, 0.375, 0.5, 0.625, 0.75, 0.875, 1.0)
        private const val UNKNOWN = Int.MIN_VALUE
        private const val LAMP_REACH = 8.0
        private const val SICK_REACH = 4.5
        private const val LAMP_DROP = 0.3
        private const val FLICKER_GLOW = 0.55
        private const val DIM_GLOW = 0.2
        private const val SOON_AHEAD = 0.3
        private const val REAR_PROBE = 8.0
        val SOON = 5..12
        private val DIRECTIONS = (0 until 16).map { Vec3(sin(it * PI / 8), 0.0, cos(it * PI / 8)) }

        fun key(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFF_FFFFL)

        fun open(column: Int, cut: Boolean, y0: Double, y1: Double): Boolean {
            val low = min(y0, y1)
            val high = max(y0, y1)
            if (column and MazeLayout.SOLID != 0) return cut && low >= FLOOR && high < CUT_CEILING
            if (high >= MazeLayout.ceilingOf(column)) return false
            return low >= FLOOR || column and MazeLayout.PIT != 0
        }
    }
}
