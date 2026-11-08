package xyz.thewhish.substratum.level

import net.minecraft.world.phys.Vec3
import xyz.thewhish.substratum.gaze.Gaze
import xyz.thewhish.substratum.worldgen.Corridors
import xyz.thewhish.substratum.worldgen.MazeLayout
import java.util.Random

object SpawnPlan {
    const val TEAM = 4
    private const val RING_LAST = 45
    val RING = 15..RING_LAST
    const val DARK_MARGIN = 20
    const val APART = 45
    const val APART_RELAXED = 30
    const val MEET = 75

    private const val CENTRE_TRIES = 12
    private const val CENTRE_STEP = 96
    private const val FOOTING_RADIUS = 64
    private const val DEAD_END_OPEN = 8
    private const val DEAD_END_SHUT = 3
    private const val FIELD = RING_LAST + DARK_MARGIN
    private const val FIRST_TRIES = 24

    fun plan(layout: MazeLayout, hintX: Int, hintZ: Int, random: Random): List<Corridors.Column> {
        val areas = List(CENTRE_TRIES) { attempt -> centre(layout, hintX, hintZ, attempt, random) }
            .map { centre -> lazy { candidates(layout, centre, random) } }
        val sight = Gaze(layout, emptyList())
        val reach = HashMap<Corridors.Column, Corridors>()
        for (apart in intArrayOf(APART, APART_RELAXED)) {
            for (area in areas) {
                val candidates = area.value
                if (candidates.size < TEAM) continue
                for (first in candidates.take(FIRST_TRIES)) {
                    choose(layout, first, candidates, apart, sight, reach)?.let { return it }
                }
            }
        }
        val fallback = layout.findFooting(hintX, hintZ, FOOTING_RADIUS, lit = true)
        return List(TEAM) { Corridors.Column(fallback[0], fallback[1]) }
    }

    fun deadEnd(layout: MazeLayout, x: Int, z: Int): Boolean {
        var open = 0
        var shut = 0
        for (d in 0 until 4) {
            val run = run(layout, x, z, DX[d], DZ[d])
            if (run >= DEAD_END_OPEN) open++ else if (run <= DEAD_END_SHUT) shut++
        }
        return open == 1 && shut == 3
    }

    fun facing(layout: MazeLayout, point: Corridors.Column): Float {
        val open = (0 until 4).maxBy { run(layout, point.x, point.z, DX[it], DZ[it]) }
        return YAW[open]
    }

    fun eye(point: Corridors.Column): Vec3 = Vec3(point.x + 0.5, Gaze.FLOOR + Gaze.EYE, point.z + 0.5)

    private fun centre(layout: MazeLayout, hintX: Int, hintZ: Int, attempt: Int, random: Random): Corridors.Column {
        val angle = random.nextDouble() * 2.0 * Math.PI
        val reach = CENTRE_STEP * attempt
        val x = hintX + (Math.cos(angle) * reach).toInt()
        val z = hintZ + (Math.sin(angle) * reach).toInt()
        val footing = layout.findFooting(x, z, FOOTING_RADIUS, lit = true)
        return Corridors.Column(footing[0], footing[1])
    }

    private fun candidates(layout: MazeLayout, centre: Corridors.Column, random: Random): List<Corridors.Column> {
        val field = Corridors(layout, centre.x, centre.z, FIELD)
        val dark = DarkWindow(field)
        val found = ArrayList<Corridors.Column>()
        field.reached { x, z, steps ->
            if (steps in RING && suitable(field, x, z) && !dark.near(x, z)) found.add(Corridors.Column(x, z))
        }
        found.shuffle(random)
        found.sortBy { if (deadEnd(layout, it.x, it.z)) 0 else 1 }
        return found
    }

    private fun choose(
        layout: MazeLayout,
        first: Corridors.Column,
        candidates: List<Corridors.Column>,
        apart: Int,
        sight: Gaze,
        reach: MutableMap<Corridors.Column, Corridors>,
    ): List<Corridors.Column>? {
        val chosen = arrayListOf(first)
        for (candidate in candidates) {
            if (chosen.any { !fits(layout, it, candidate, apart, sight, reach) }) continue
            chosen.add(candidate)
            if (chosen.size == TEAM) return chosen
        }
        return null
    }

    private fun fits(
        layout: MazeLayout,
        placed: Corridors.Column,
        candidate: Corridors.Column,
        apart: Int,
        sight: Gaze,
        reach: MutableMap<Corridors.Column, Corridors>,
    ): Boolean {
        val steps = reach.getOrPut(placed) { Corridors(layout, placed.x, placed.z, MEET) }.steps(candidate.x, candidate.z)
        return steps in apart..MEET && !sight.clear(eye(placed), eye(candidate))
    }

    private fun suitable(field: Corridors, x: Int, z: Int): Boolean =
        field.roomy(x, z) && field.flags(x, z) and (MazeLayout.PIT_ROOM or MazeLayout.DARK_ZONE) == 0

    private fun run(layout: MazeLayout, x: Int, z: Int, dx: Int, dz: Int): Int {
        var length = 0
        while (length < DEAD_END_OPEN && Corridors.walkable(layout.columnAt(x + dx * (length + 1), z + dz * (length + 1)))) length++
        return length
    }

    private class DarkWindow(private val field: Corridors) {
        private val size = field.radius * 2 + 1
        private val sums = IntArray((size + 1) * (size + 1))

        init {
            for (i in 0 until size) {
                for (j in 0 until size) {
                    val x = field.originX - field.radius + i
                    val z = field.originZ - field.radius + j
                    val dark = if (field.flags(x, z) and MazeLayout.DARK_ZONE != 0) 1 else 0
                    sums[(i + 1) * (size + 1) + j + 1] = dark + sums[i * (size + 1) + j + 1] + sums[(i + 1) * (size + 1) + j] - sums[i * (size + 1) + j]
                }
            }
        }

        fun near(x: Int, z: Int): Boolean {
            val i0 = (x - DARK_MARGIN - field.originX + field.radius).coerceIn(0, size)
            val j0 = (z - DARK_MARGIN - field.originZ + field.radius).coerceIn(0, size)
            val i1 = (x + DARK_MARGIN + 1 - field.originX + field.radius).coerceIn(0, size)
            val j1 = (z + DARK_MARGIN + 1 - field.originZ + field.radius).coerceIn(0, size)
            val row = size + 1
            return sums[i1 * row + j1] - sums[i0 * row + j1] - sums[i1 * row + j0] + sums[i0 * row + j0] > 0
        }
    }

    private val DX = intArrayOf(1, -1, 0, 0)
    private val DZ = intArrayOf(0, 0, 1, -1)
    private val YAW = floatArrayOf(-90f, 90f, 0f, 180f)
}
