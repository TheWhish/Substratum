package xyz.thewhish.substratum.smiler

import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import xyz.thewhish.substratum.gaze.Gaze
import xyz.thewhish.substratum.gaze.Viewer
import xyz.thewhish.substratum.network.ViewportSync
import xyz.thewhish.substratum.worldgen.Corridors
import xyz.thewhish.substratum.worldgen.MazeLayout
import java.util.UUID
import kotlin.random.Random

private const val WALKERS = 120
private const val SPEED = 0.23
private const val HUNT_EVERY = 4
private const val GIVE_UP = 3 * 60 * 20
private const val AFTERWARDS = 20 * 20
private const val PEEKS = 6
private const val SUB_CELL = 0.4
private val SIDES = listOf(Vec3(1.0, 0.0, 0.0), Vec3(-1.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0), Vec3(0.0, 0.0, -1.0))

private fun fits(layout: MazeLayout, at: Vec3): Boolean {
    val top = Smiler.FACE_CENTRE + Smiler.FACE_HALF
    for (x in Mth.floor(at.x - Smiler.FACE_HALF)..Mth.floor(at.x + Smiler.FACE_HALF - 1.0E-6)) {
        for (z in Mth.floor(at.z - Smiler.FACE_HALF)..Mth.floor(at.z + Smiler.FACE_HALF - 1.0E-6)) {
            val column = layout.columnAt(x, z)
            if (!Corridors.walkable(column) || MazeLayout.ceilingOf(column) < at.y + top) return false
        }
    }
    return true
}

private fun eyeAt(x: Double, z: Double): Vec3 = Vec3(x, Gaze.FLOOR + Gaze.EYE, z)

private class Walker(val layout: MazeLayout, val random: Random) {
    var at: Vec3
    var heading: Vec3 = SIDES.first()
    private var target: Vec3

    init {
        while (true) {
            val cell = Vec3(random.nextInt(-80, 80) * 8 + 5.5, Spots.FLOOR, random.nextInt(-80, 80) * 8 + 5.5)
            if (!Corners.tight(layout::columnAt, Mth.floor(cell.x), Mth.floor(cell.z)) || ways(cell).isEmpty()) continue
            at = cell
            target = cell
            break
        }
    }

    private fun ways(from: Vec3): List<Vec3> = SIDES.filter { side ->
        (1..MazeLayout.CELL).all { Corridors.walkable(layout.columnAt(Mth.floor(from.x + side.x * it), Mth.floor(from.z + side.z * it))) }
    }

    fun step() {
        if (at.distanceToSqr(target) < 1.0E-6) {
            val ways = ways(at)
            val onward = ways.filter { it.dot(heading) > -0.5 }.ifEmpty { ways }
            heading = onward[random.nextInt(onward.size)]
            target = at.add(heading.scale(MazeLayout.CELL.toDouble()))
        }
        val left = target.subtract(at)
        at = if (left.length() <= SPEED) target else at.add(heading.scale(SPEED))
    }

    fun viewer(): Viewer = Viewer(UUID(0, 1), eyeAt(at.x, at.z), heading, ViewportSync.DEFAULT)
}

private fun validate(layout: MazeLayout, gaze: Gaze, victim: Viewer, lair: Corners.Lair, random: Random) {
    run {
        val at = lair.at
        check(fits(layout, at)) { "the lair at $at overlaps a wall" }
        check(!Spots.sees(gaze, victim, at, anyTurn = true)) { "the lair at $at is in plain sight" }
        check(lair.reveal in Corners.REVEAL_NEAR..Corners.REVEAL_FAR) { "the lair at $at is first seen from ${lair.reveal}" }
        check(Corners.tight(layout::columnAt, Mth.floor(at.x), Mth.floor(at.z)) && Corners.tight(layout::columnAt, lair.seenFrom.x, lair.seenFrom.z)) { "the lair at $at sits in an open hall" }
        val field = Corridors(layout, Mth.floor(victim.eye.x), Mth.floor(victim.eye.z), 40)
        val steps = field.steps(Mth.floor(at.x), Mth.floor(at.z))
        check(steps in Corners.PATH.first - 2..Corners.PATH.last + 3) { "the lair at $at is $steps steps away" }
        field.reached { x, z, walked ->
            if (walked >= steps) return@reached
            repeat(PEEKS) {
                val from = eyeAt(x + 0.5 + random.nextDouble(-SUB_CELL, SUB_CELL), z + 0.5 + random.nextDouble(-SUB_CELL, SUB_CELL))
                if (Spots.face(at, from).none { gaze.clear(from, it) }) return@repeat
                val away = Math.hypot(from.x - at.x, from.z - at.z)
                check(away <= Corners.REVEAL_FAR + 1.0) { "the lair at $at shows up from $away blocks on the way there" }
            }
        }
    }
}

fun main() {
    val layout = MazeLayout(0x5311EL)
    val random = Random(11)
    val waits = ArrayList<Int>()
    var met = 0
    var nanos = 0L
    var searches = 0
    var revealSum = 0.0
    var lairs = 0
    repeat(WALKERS) {
        val walker = Walker(layout, random)
        var tick = 0
        var corner: Corners.Lair? = null
        while (corner == null && tick < GIVE_UP) {
            walker.step()
            tick++
            if (tick % HUNT_EVERY != 0 || !Corners.tight(layout::columnAt, Mth.floor(walker.at.x), Mth.floor(walker.at.z))) continue
            val victim = walker.viewer()
            val gaze = Gaze(layout, listOf(victim))
            val started = System.nanoTime()
            corner = Corners.find(gaze, layout, victim, walker.at, walker.heading.scale(SPEED), random) { fits(layout, it) }
            nanos += System.nanoTime() - started
            searches++
            corner?.let { validate(layout, gaze, victim, it, random) }
        }
        if (corner == null) return@repeat
        waits.add(tick)
        revealSum += corner.reveal
        lairs++
        for (after in 0 until AFTERWARDS) {
            walker.step()
            val victim = walker.viewer()
            if (Spots.sees(Gaze(layout, listOf(victim)), victim, corner.at)) {
                met++
                break
            }
        }
    }
    waits.sort()
    val found = waits.size
    val median = if (found == 0) Double.NaN else waits[found / 2] / 20.0
    val quick = waits.count { it <= 60 * 20 }
    check(found >= WALKERS * 0.8) { "corners are too rare: only $found of $WALKERS walkers got one within ${GIVE_UP / 20} s" }
    check(met >= found * 0.7) { "lairs are badly placed: walkers met only $met of $found" }
    println(
        "ambush: %d of %d walkers got a corner (median %.1f s, %d within a minute), met %d of them; first seen from %.1f blocks on average, %.2f ms per search"
            .format(found, WALKERS, median, quick, met, revealSum / lairs, nanos / 1e6 / searches)
    )
}
