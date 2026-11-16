package xyz.thewhish.substratum.smiler

import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import xyz.thewhish.substratum.gaze.Gaze
import xyz.thewhish.substratum.gaze.Viewer
import xyz.thewhish.substratum.network.ViewportSync
import xyz.thewhish.substratum.worldgen.Corridors
import xyz.thewhish.substratum.worldgen.MazeLayout
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val VICTIMS = 400
private const val PEEKS = 6
private const val SUB_CELL = 0.4

private fun cramped(layout: MazeLayout, random: Random): Corridors.Column {
    while (true) {
        val x = random.nextInt(-600, 600)
        val z = random.nextInt(-600, 600)
        if (Corridors.walkable(layout.columnAt(x, z)) && Corners.tight(layout::columnAt, x, z)) return Corridors.Column(x, z)
    }
}

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

fun main() {
    val layout = MazeLayout(0x5311EL)
    val random = Random(11)
    var found = 0
    var nanos = 0L
    var nearest = Double.MAX_VALUE
    var farthest = 0.0
    var revealSum = 0.0
    repeat(VICTIMS) {
        val home = cramped(layout, random)
        val yaw = random.nextInt(4) * PI / 2 + random.nextDouble(-0.25, 0.25)
        val eye = eyeAt(home.x + 0.5, home.z + 0.5)
        val victim = Viewer(UUID.randomUUID(), eye, Vec3(sin(yaw), 0.0, cos(yaw)), ViewportSync.DEFAULT)
        val gaze = Gaze(layout, listOf(victim))
        val feet = Vec3(home.x + 0.5, Spots.FLOOR, home.z + 0.5)
        val started = System.nanoTime()
        val lair = Corners.find(gaze, layout, victim, feet, Vec3.ZERO, random) { fits(layout, it) }
        nanos += System.nanoTime() - started
        if (lair == null) return@repeat
        found++
        val at = lair.at
        check(fits(layout, at)) { "the lair at $at overlaps a wall" }
        check(!Spots.sees(gaze, victim, at, anyTurn = true)) { "the lair at $at is in plain sight" }
        check(lair.reveal in Corners.REVEAL_NEAR..Corners.REVEAL_FAR) { "the lair at $at is first seen from ${lair.reveal}" }
        val field = Corridors(layout, home.x, home.z, 40)
        check(Corners.tight(layout::columnAt, Mth.floor(at.x), Mth.floor(at.z)) && Corners.tight(layout::columnAt, lair.seenFrom.x, lair.seenFrom.z)) { "the lair at $at sits in an open hall" }
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
        nearest = minOf(nearest, lair.reveal)
        farthest = maxOf(farthest, lair.reveal)
        revealSum += lair.reveal
    }
    check(found >= VICTIMS / 10) { "ambush corners are too rare: $found of $VICTIMS" }
    println(
        "ambush: %d of %d players in corridors get a corner, first seen from %.1f..%.1f (mean %.1f) blocks, %.1f ms per search"
            .format(found, VICTIMS, nearest, farthest, revealSum / found, nanos / 1e6 / VICTIMS)
    )
}
