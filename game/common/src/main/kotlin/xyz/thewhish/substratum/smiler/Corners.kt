package xyz.thewhish.substratum.smiler

import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import xyz.thewhish.substratum.gaze.Gaze
import xyz.thewhish.substratum.gaze.Viewer
import xyz.thewhish.substratum.worldgen.Corridors
import xyz.thewhish.substratum.worldgen.MazeLayout
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

object Corners {

    class Lair(val at: Vec3, val reveal: Double, val seenFrom: Corridors.Column)

    const val REVEAL_NEAR = 3.5
    const val REVEAL_FAR = 10.5
    val PATH = 4..14
    private const val AHEAD = 0.3
    private const val TRIED = 48
    private const val RADIUS = 26
    private val NUDGES = doubleArrayOf(0.0, 0.5)
    private val SIDES = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
    private const val INNER = 5
    private const val HALF_ROOM = 2.5
    private const val LOOK_FOCUS = 8.0
    private const val STILL = 0.02
    private const val EDGE = 0.45
    private const val SURROUNDS = 6
    private const val CRAMPED = 0.7
    private val STANDS = listOf(0.0 to 0.0, EDGE to EDGE, EDGE to -EDGE, -EDGE to EDGE, -EDGE to -EDGE)

    fun find(gaze: Gaze, layout: MazeLayout, victim: Viewer, feet: Vec3, moving: Vec3, random: Random, fits: (Vec3) -> Boolean): Lair? {
        val look = Vec3(victim.forward.x, 0.0, victim.forward.z)
        if (look.lengthSqr() < 1.0E-4) return null
        val ahead = look.normalize()
        val origin = Spots.origin(layout, Mth.floor(feet.x), Mth.floor(feet.z)) ?: return null
        val field = Corridors(layout, origin.x, origin.z, RADIUS)
        val walked = ArrayList<Corridors.Column>()
        val lairs = ArrayList<Vec3>()
        field.reached { x, z, steps ->
            walked.add(Corridors.Column(x, z))
            if (steps !in PATH) return@reached
            val way = Vec3((x - origin.x).toDouble(), 0.0, (z - origin.z).toDouble()).normalize()
            if (way.dot(ahead) < AHEAD) return@reached
            for (dx in NUDGES) for (dz in NUDGES) lairs.add(Vec3(x + 0.5 + dx, Spots.FLOOR, z + 0.5 + dz))
        }
        lairs.shuffle(random)
        val found = ArrayList<Lair>()
        val weights = ArrayList<Double>()
        for (at in lairs) {
            if (found.size >= TRIED) break
            if (!tight(field::flags, Mth.floor(at.x), Mth.floor(at.z)) || !fits(at)) continue
            val corner = elbow(field, at, feet) ?: continue
            if (gaze.viewers.any { Spots.sees(gaze, it, at, anyTurn = true) }) continue
            val from = reveal(gaze, field, walked, at)?.takeIf { tight(field::flags, it.x, it.z) } ?: continue
            val reveal = sqrt(flat(from, at))
            found.add(Lair(at, reveal, from))
            weights.add(heading(victim, feet, moving, corner) * (1.0 + REVEAL_FAR - reveal) * (0.8 + 0.4 * random.nextDouble()))
        }
        return found.indices.maxByOrNull { weights[it] }?.let(found::get)
    }

    fun reveal(gaze: Gaze, field: Corridors, walked: List<Corridors.Column>, at: Vec3): Corridors.Column? {
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

    private fun heading(victim: Viewer, feet: Vec3, moving: Vec3, corner: Vec3): Double {
        val toward = Vec3(corner.x - feet.x, 0.0, corner.z - feet.z).normalize()
        val look = Vec3(victim.forward.x, 0.0, victim.forward.z).normalize()
        val aim = ((look.dot(toward) + 1.0) / 2.0).pow(LOOK_FOCUS)
        val walk = if (moving.horizontalDistanceSqr() < STILL * STILL) 1.0 else 1.0 + Vec3(moving.x, 0.0, moving.z).normalize().dot(toward)
        return aim * walk
    }

    fun elbow(field: Corridors, at: Vec3, feet: Vec3): Vec3? {
        val cellX = Math.floorDiv(Mth.floor(at.x), MazeLayout.CELL)
        val cellZ = Math.floorDiv(Mth.floor(at.z), MazeLayout.CELL)
        val turn = SIDES.minByOrNull { (dx, dz) ->
            val steps = field.steps((cellX + dx) * MazeLayout.CELL + INNER, (cellZ + dz) * MazeLayout.CELL + INNER)
            if (opens(field, cellX, cellZ, dx, dz)) steps else Corridors.UNREACHED
        } ?: return null
        val joinX = cellX + turn[0]
        val joinZ = cellZ + turn[1]
        if (field.steps(joinX * MazeLayout.CELL + INNER, joinZ * MazeLayout.CELL + INNER) == Corridors.UNREACHED) return null
        val open = SIDES.filter { (dx, dz) -> opens(field, joinX, joinZ, dx, dz) }
        if (open.size != 2) return null
        val back = open.firstOrNull { it[0] == -turn[0] && it[1] == -turn[1] } ?: return null
        val arrival = open.first { it !== back }
        if (arrival[0] * back[0] + arrival[1] * back[1] != 0) return null
        val centreX = joinX * MazeLayout.CELL + INNER + 0.5
        val centreZ = joinZ * MazeLayout.CELL + INNER + 0.5
        if ((feet.x - centreX) * arrival[0] + (feet.z - centreZ) * arrival[1] <= HALF_ROOM) return null
        return Vec3(centreX, feet.y, centreZ)
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
