package xyz.thewhish.substratum.smiler

import net.minecraft.world.phys.Vec3
import xyz.thewhish.substratum.gaze.Gaze
import xyz.thewhish.substratum.gaze.Viewer
import xyz.thewhish.substratum.worldgen.Corridors
import xyz.thewhish.substratum.worldgen.MazeLayout
import kotlin.math.sqrt
import kotlin.random.Random

object Spots {

    enum class Approach { AHEAD, CORNER, BEHIND }

    class Spot(val at: Vec3, val sight: Double)

    class Scene(
        val gaze: Gaze,
        val feet: Vec3,
        val victim: Viewer,
        val crowd: List<Vec3>,
        val smilers: List<Vec3>,
    )

    const val FLOOR = Gaze.FLOOR
    const val SPACING = 24.0
    const val CLOSEST = 12.0
    private const val ROOM = 4.0
    private const val CROWD_GAP = 16.0
    private const val LOOKS = 8
    private const val TRIED = 160
    private const val SHARED = 0.4
    private const val CENTRELINE = 2.0

    fun at(x: Int, z: Int): Vec3 = Vec3(x + 0.5, FLOOR, z + 0.5)

    fun face(position: Vec3, eye: Vec3): List<Vec3> {
        val centre = position.add(0.0, Smiler.FACE_CENTRE, 0.0)
        val side = Vec3(centre.z - eye.z, 0.0, eye.x - centre.x).normalize().scale(Smiler.FACE_HALF)
        return listOf(centre, centre.add(side), centre.subtract(side), centre.add(0.0, Smiler.FACE_HALF, 0.0), centre.add(0.0, -Smiler.FACE_HALF, 0.0))
    }

    fun sees(gaze: Gaze, viewer: Viewer, spot: Vec3, anyTurn: Boolean = false): Boolean =
        gaze.sees(viewer, Gaze.Patch(face(spot, viewer.eye)), glow = true, anyTurn = anyTurn)

    private fun unseen(scene: Scene, spot: Vec3, anyTurn: Boolean): Boolean = scene.gaze.viewers.none { sees(scene.gaze, it, spot, anyTurn) }

    fun origin(layout: MazeLayout, x: Int, z: Int): Corridors.Column? {
        for (dx in NEAREST) for (dz in NEAREST) {
            if (Corridors.walkable(layout.columnAt(x + dx, z + dz))) return Corridors.Column(x + dx, z + dz)
        }
        return null
    }

    fun fieldRadius(band: IntRange): Int = band.last + band.last / 4 + Gaze.SOON.last

    fun find(
        scene: Scene,
        field: Corridors,
        band: IntRange,
        approach: Approach,
        random: Random,
        weigh: (Int, Int) -> Double,
    ): Spot? {
        val corner = approach == Approach.CORNER
        val looks = if (corner) scene.gaze.soon(scene.victim, field).shuffled(random).take(LOOKS) else emptyList()
        if (corner && looks.isEmpty()) return null
        val slack = if (corner) Gaze.SOON.last.toDouble() else 0.0
        val nearest = maxOf(band.first - slack, CLOSEST + ROOM)
        val farthest = band.last + slack
        val pool = ArrayList<Corridors.Column>()
        field.reached { x, z, _ ->
            val spot = at(x, z)
            val away = flat(spot, scene.feet)
            if (away in nearest * nearest..farthest * farthest && field.roomy(x, z) && spaced(scene, spot) && framing(scene.victim, approach, spot)) {
                pool.add(Corridors.Column(x, z))
            }
        }
        pool.shuffle(random)
        val spots = ArrayList<Spot>()
        val weights = ArrayList<Double>()
        for (column in pool.take(TRIED)) {
            val spot = at(column.x, column.z)
            val fit = fit(scene, approach, spot)
            if (fit <= 0.0) continue
            val sight = sight(scene, spot, looks) ?: continue
            if (sight < band.first || sight > band.last) continue
            val weight = fit * centreline(column) * weigh(column.x, column.z)
            if (weight <= 0.0) continue
            spots.add(Spot(spot, sight))
            weights.add(weight)
        }
        return pick(spots, weights, random)
    }

    private fun sight(scene: Scene, spot: Vec3, looks: List<Vec3>): Double? {
        if (looks.isEmpty()) return sqrt(flat(spot, scene.feet))
        val centre = centreOf(spot)
        return looks.filter { scene.gaze.clear(it, centre) }.minOfOrNull { sqrt(flat(spot, it)) }
    }

    fun <T> pick(items: List<T>, weights: List<Double>, random: Random): T? {
        if (items.isEmpty()) return null
        var roll = random.nextDouble() * weights.sum()
        for (i in items.indices) {
            roll -= weights[i]
            if (roll < 0.0) return items[i]
        }
        return items.last()
    }

    private fun spaced(scene: Scene, spot: Vec3): Boolean =
        scene.crowd.all { flat(spot, it) >= CROWD_GAP * CROWD_GAP } &&
            scene.smilers.all { flat(spot, it) >= SPACING * SPACING }

    private fun flat(a: Vec3, b: Vec3): Double {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return dx * dx + dz * dz
    }

    private fun framing(victim: Viewer, approach: Approach, spot: Vec3): Boolean = when (approach) {
        Approach.AHEAD -> victim.onScreen(centreOf(spot))
        Approach.BEHIND -> !victim.frames(centreOf(spot))
        Approach.CORNER -> true
    }

    private fun fit(scene: Scene, approach: Approach, spot: Vec3): Double {
        val gaze = scene.gaze
        val victim = scene.victim
        val centre = centreOf(spot)
        return when (approach) {
            Approach.AHEAD -> {
                if (!sees(gaze, victim, spot)) return 0.0
                val shared = gaze.viewers.any { it.uuid != victim.uuid && sees(gaze, it, spot) }
                (1.0 + 2.0 * victim.centrality(centre)) * if (shared) SHARED else 1.0
            }
            Approach.BEHIND -> {
                if (!gaze.clear(victim.eye, centre) || !unseen(scene, spot, anyTurn = false)) return 0.0
                val away = Vec3(centre.x - victim.eye.x, 0.0, centre.z - victim.eye.z).normalize()
                val look = Vec3(victim.forward.x, 0.0, victim.forward.z).normalize()
                1.0 + 2.0 * maxOf(0.0, -away.dot(look))
            }
            Approach.CORNER -> {
                if (gaze.clear(victim.eye, centre) || !unseen(scene, spot, anyTurn = true)) 0.0 else 1.0
            }
        }
    }

    private fun centreline(column: Corridors.Column): Double {
        val onLine = Math.floorMod(column.x, MazeLayout.CELL) == MazeLayout.CENTRE || Math.floorMod(column.z, MazeLayout.CELL) == MazeLayout.CENTRE
        return if (onLine) CENTRELINE else 1.0
    }

    private fun centreOf(spot: Vec3): Vec3 = spot.add(0.0, Smiler.FACE_CENTRE, 0.0)

    private val NEAREST = intArrayOf(0, 1, -1)
}
