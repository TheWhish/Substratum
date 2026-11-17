package xyz.thewhish.substratum.smiler

import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import xyz.thewhish.substratum.gaze.Gaze
import xyz.thewhish.substratum.gaze.Viewer
import xyz.thewhish.substratum.worldgen.Corridors
import xyz.thewhish.substratum.worldgen.MazeLayout
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.random.Random

object Corners {

    class Lair(val at: Vec3, val reveal: Double, val seenFrom: Corridors.Column)

    private class Junction(val x: Int, val z: Int, val steps: Int, val exits: List<IntArray>)

    const val REVEAL_NEAR = 3.5
    const val REVEAL_FAR = 10.5
    val PATH = 4..14
    private const val REACH = 2
    private const val INNER = 5
    private const val HALF_ROOM = 2.5
    private const val STRAIGHT = HALF_ROOM + 1.0
    private const val AIM = 0.5
    private const val STILL = 0.02
    private val DEPTH = 4..10
    private const val SIDE = 2
    private const val TRIED = 24
    private const val LEAD = 2
    private const val SURROUNDS = 6
    private const val CRAMPED = 0.7
    private const val EDGE = 0.45
    private val NUDGES = doubleArrayOf(0.0, 0.5)
    private val SIDES = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
    private val STANDS = listOf(0.0 to 0.0, EDGE to EDGE, EDGE to -EDGE, -EDGE to EDGE, -EDGE to -EDGE)

    fun find(gaze: Gaze, layout: MazeLayout, victim: Viewer, feet: Vec3, moving: Vec3, random: Random, fits: (Vec3) -> Boolean): Lair? {
        val going = going(victim, moving) ?: return null
        val lean = Vec3(victim.forward.x, 0.0, victim.forward.z).normalize().add(going)
        val origin = Spots.origin(layout, Mth.floor(feet.x), Mth.floor(feet.z)) ?: return null
        val field = Corridors(layout, origin.x, origin.z, PATH.last + 1)
        val homeX = Math.floorDiv(origin.x, MazeLayout.CELL)
        val homeZ = Math.floorDiv(origin.z, MazeLayout.CELL)
        val junctions = ArrayList<Junction>()
        for (cellX in homeX - REACH..homeX + REACH) for (cellZ in homeZ - REACH..homeZ + REACH) {
            junction(field, cellX, cellZ, feet, going, lean)?.let(junctions::add)
        }
        if (junctions.isEmpty()) return null
        val walked = ArrayList<Corridors.Column>()
        field.reached { x, z, _ -> walked.add(Corridors.Column(x, z)) }
        return junctions.sortedBy { it.steps }.firstNotNullOfOrNull { junction ->
            junction.exits.firstNotNullOfOrNull { lair(gaze, field, walked, junction, it, random, fits) }
        }
    }

    private fun going(victim: Viewer, moving: Vec3): Vec3? {
        val stepping = moving.horizontalDistanceSqr() >= STILL * STILL
        val flat = if (stepping) Vec3(moving.x, 0.0, moving.z) else Vec3(victim.forward.x, 0.0, victim.forward.z)
        return if (flat.lengthSqr() < 1.0E-6) null else flat.normalize()
    }

    private fun junction(field: Corridors, cellX: Int, cellZ: Int, feet: Vec3, going: Vec3, lean: Vec3): Junction? {
        val x = cellX * MazeLayout.CELL + INNER
        val z = cellZ * MazeLayout.CELL + INNER
        val steps = field.steps(x, z)
        if (steps == Corridors.UNREACHED) return null
        val dx = feet.x - (x + 0.5)
        val dz = feet.z - (z + 0.5)
        val open = SIDES.filter { (sx, sz) -> opens(field, cellX, cellZ, sx, sz) }
        val arrival = open.firstOrNull { (sx, sz) -> dx * sx + dz * sz > HALF_ROOM && abs(dx * sz - dz * sx) <= HALF_ROOM } ?: return null
        val along = dx * arrival[0] + dz * arrival[1]
        if (steps > along + STRAIGHT) return null
        if (-(dx * going.x + dz * going.z) < AIM * sqrt(dx * dx + dz * dz)) return null
        for (passed in 1 until ceil((along + HALF_ROOM) / MazeLayout.CELL).toInt()) {
            val nextX = cellX + arrival[0] * passed
            val nextZ = cellZ + arrival[1] * passed
            if (opens(field, nextX, nextZ, arrival[1], arrival[0]) || opens(field, nextX, nextZ, -arrival[1], -arrival[0])) return null
        }
        val exits = open.filter { it !== arrival }
        if (exits.isEmpty() || exits.any { it[0] == -arrival[0] && it[1] == -arrival[1] }) return null
        return Junction(x, z, steps, exits.sortedByDescending { (ex, ez) -> lean.x * ex + lean.z * ez + (dx * ex + dz * ez) / HALF_ROOM })
    }

    private fun lair(gaze: Gaze, field: Corridors, walked: List<Corridors.Column>, junction: Junction, exit: IntArray, random: Random, fits: (Vec3) -> Boolean): Lair? {
        val spots = ArrayList<Vec3>()
        for (depth in DEPTH) for (side in -SIDE..SIDE) {
            val x = junction.x + exit[0] * depth + exit[1] * side
            val z = junction.z + exit[1] * depth + exit[0] * side
            if (field.steps(x, z) !in PATH) continue
            for (nx in NUDGES) for (nz in NUDGES) spots.add(Vec3(x + 0.5 + nx, Spots.FLOOR, z + 0.5 + nz))
        }
        spots.shuffle(random)
        var best: Lair? = null
        var weight = 0.0
        for (at in spots.take(TRIED)) {
            if (!tight(field::flags, Mth.floor(at.x), Mth.floor(at.z)) || !fits(at)) continue
            if (gaze.viewers.any { Spots.sees(gaze, it, at, anyTurn = true) }) continue
            val from = reveal(gaze, field, walked, at) ?: continue
            if (field.steps(from.x, from.z) < LEAD || !tight(field::flags, from.x, from.z)) continue
            val reveal = sqrt(flat(from, at))
            val score = (1.0 + REVEAL_FAR - reveal) * (0.8 + 0.4 * random.nextDouble())
            if (score <= weight) continue
            best = Lair(at, reveal, from)
            weight = score
        }
        return best
    }

    private fun reveal(gaze: Gaze, field: Corridors, walked: List<Corridors.Column>, at: Vec3): Corridors.Column? {
        val steps = field.steps(Mth.floor(at.x), Mth.floor(at.z))
        if (steps == Corridors.UNREACHED) return null
        val first = walked.asSequence()
            .filter { field.steps(it.x, it.z) < steps }
            .sortedByDescending { flat(it, at) }
            .firstOrNull { column ->
                STANDS.any { (dx, dz) ->
                    val eye = Vec3(column.x + 0.5 + dx, Gaze.FLOOR + Gaze.EYE, column.z + 0.5 + dz)
                    Spots.face(at, eye).any { gaze.clear(eye, it) }
                }
            } ?: return null
        return first.takeIf { sqrt(flat(it, at)) in REVEAL_NEAR..REVEAL_FAR }
    }

    private fun opens(field: Corridors, cellX: Int, cellZ: Int, dx: Int, dz: Int): Boolean {
        val base = if (dx + dz > 0) 1 else 0
        for (across in MazeLayout.WALL until MazeLayout.CELL) {
            val x = if (dx != 0) (cellX + base * dx) * MazeLayout.CELL + 1 else cellX * MazeLayout.CELL + across
            val z = if (dz != 0) (cellZ + base * dz) * MazeLayout.CELL + 1 else cellZ * MazeLayout.CELL + across
            if (Corridors.walkable(field.flags(x, z))) return true
        }
        return false
    }

    fun tight(flags: (Int, Int) -> Int, x: Int, z: Int): Boolean {
        var open = 0
        for (dx in -SURROUNDS..SURROUNDS) for (dz in -SURROUNDS..SURROUNDS) if (Corridors.walkable(flags(x + dx, z + dz))) open++
        return open <= CRAMPED * (2 * SURROUNDS + 1) * (2 * SURROUNDS + 1)
    }

    private fun flat(column: Corridors.Column, at: Vec3): Double {
        val dx = column.x + 0.5 - at.x
        val dz = column.z + 0.5 - at.z
        return dx * dx + dz * dz
    }
}
