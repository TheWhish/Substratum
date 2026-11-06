package xyz.thewhish.substratum.smiler

import net.minecraft.world.phys.Vec3
import xyz.thewhish.substratum.gaze.Gaze
import xyz.thewhish.substratum.gaze.Viewer
import xyz.thewhish.substratum.network.ViewportSync
import xyz.thewhish.substratum.worldgen.Corridors
import xyz.thewhish.substratum.worldgen.MazeLayout
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

private const val FIELDS = 40
private const val VICTIMS = 300
private const val FIELD_RADIUS = 48
private val STEPS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

private fun walkable(layout: MazeLayout, random: Random): Corridors.Column {
    while (true) {
        val x = random.nextInt(-400, 400)
        val z = random.nextInt(-400, 400)
        if (Corridors.walkable(layout.columnAt(x, z))) return Corridors.Column(x, z)
    }
}

private fun checkCorridors() {
    val layout = MazeLayout(0x5311EL)
    val random = Random(3)
    var nanos = 0L
    repeat(FIELDS) {
        val origin = walkable(layout, random)
        val started = System.nanoTime()
        val field = Corridors(layout, origin.x, origin.z, FIELD_RADIUS)
        nanos += System.nanoTime() - started
        check(field.steps(origin.x, origin.z) == 0) { "the origin of a corridor field is not at zero" }
        field.reached { x, z, steps ->
            check(Corridors.walkable(layout.columnAt(x, z))) { "the field reached a wall or a hole at $x, $z" }
            for ((dx, dz) in STEPS) {
                val next = field.steps(x + dx, z + dz)
                val open = Corridors.walkable(layout.columnAt(x + dx, z + dz))
                if (open && steps < FIELD_RADIUS) check(next != Corridors.UNREACHED && abs(next - steps) <= 1) { "uneven field at $x, $z" }
            }
        }
    }
    println("corridors: %.2f ms per field of radius %d".format(nanos / 1e6 / FIELDS, FIELD_RADIUS))
}

private fun lookingAt(eye: Vec3, random: Random): Viewer {
    val yaw = random.nextDouble(0.0, 2 * PI)
    val forward = Vec3(sin(yaw), random.nextDouble(-0.2, 0.2), cos(yaw)).normalize()
    return Viewer(UUID.randomUUID(), eye, forward, ViewportSync.DEFAULT)
}

private fun eyeAt(column: Corridors.Column): Vec3 = Vec3(column.x + 0.5, Gaze.FLOOR + Gaze.EYE, column.z + 0.5)

private fun checkSpots() {
    val layout = MazeLayout(0x5311EL)
    val random = Random(9)
    val band = 64..72
    val counts = IntArray(Spots.Approach.entries.size)
    var nanos = 0L
    repeat(VICTIMS) { round ->
        val home = walkable(layout, random)
        val victim = lookingAt(eyeAt(home), random)
        val field = Corridors(layout, home.x, home.z, Spots.fieldRadius(band))
        val friend = if (round % 2 == 0) null else {
            val near = ArrayList<Corridors.Column>()
            field.reached { x, z, steps -> if (steps in 3..10) near.add(Corridors.Column(x, z)) }
            near.randomOrNull(random)
        }
        val friendView = friend?.let { lookingAt(eyeAt(it), random) }
        val views = listOfNotNull(victim, friendView)
        val crowd = listOfNotNull(friend?.let { Spots.at(it.x, it.z) })
        val gaze = Gaze(layout, views)
        val feet = Spots.at(home.x, home.z)
        val scene = Spots.Scene(gaze, feet, victim, crowd, emptyList())
        for (approach in Spots.Approach.entries) {
            val started = System.nanoTime()
            val found = Spots.find(scene, field, band, approach, random) { _, _ -> 1.0 }
            nanos += System.nanoTime() - started
            if (found == null) continue
            counts[approach.ordinal]++
            val spot = found.at
            val x = floor(spot.x).toInt()
            val z = floor(spot.z).toInt()
            check(found.sight in band.first.toDouble()..band.last.toDouble()) { "$approach spot $x, $z is seen from ${found.sight} blocks" }
            check(approach == Spots.Approach.CORNER || abs(found.sight - spot.distanceTo(feet)) < 1.0e-9) { "$approach spot $x, $z is not measured from the player" }
            check(field.roomy(x, z)) { "$approach spot $x, $z touches a wall" }
            check(Spots.sees(gaze, victim, spot) == (approach == Spots.Approach.AHEAD)) { "$approach spot $x, $z has the wrong visibility" }
            check(approach != Spots.Approach.CORNER || !Spots.sees(gaze, victim, spot, anyTurn = true)) { "corner spot $x, $z shows up after a step aside" }
            check(spot.distanceTo(feet) >= Spots.CLOSEST) { "$approach spot $x, $z is closer than ${Spots.CLOSEST} blocks" }
            crowd.forEach { check(spot.distanceTo(it) >= 16.0) { "$approach spot $x, $z stands next to another player" } }
            if (approach != Spots.Approach.AHEAD) {
                check(friendView == null || !Spots.sees(gaze, friendView, spot)) { "quiet $approach spot $x, $z is in view of another player" }
            }
        }
    }
    check(counts.all { it >= VICTIMS / 10 }) { "spots are too rare: ${counts.toList()}" }
    println(
        "spots: ahead ${counts[0]}, corner ${counts[1]}, behind ${counts[2]} of $VICTIMS, %.2f ms per search"
            .format(nanos / 1e6 / (VICTIMS * Spots.Approach.entries.size))
    )
}

fun main() {
    checkCorridors()
    checkSpots()
    println("smiler: ok")
}
