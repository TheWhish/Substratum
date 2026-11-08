package xyz.thewhish.substratum.level

import xyz.thewhish.substratum.gaze.Gaze
import xyz.thewhish.substratum.worldgen.Corridors
import xyz.thewhish.substratum.worldgen.MazeLayout
import java.util.Random

private const val SEEDS = 200

fun main() {
    var deadEnds = 0
    var relaxed = 0
    var slowest = 0L
    var total = 0L
    val random = Random(41)
    repeat(SEEDS) {
        val seed = random.nextLong()
        val layout = MazeLayout(seed)
        val hintX = random.nextInt(20_000) - 10_000
        val hintZ = random.nextInt(20_000) - 10_000
        val started = System.nanoTime()
        val points = SpawnPlan.plan(layout, hintX, hintZ, Random(seed))
        val spent = System.nanoTime() - started
        total += spent
        slowest = maxOf(slowest, spent)
        val where = "seed $seed near ($hintX, $hintZ)"
        check(points.size == SpawnPlan.TEAM) { "$where: ${points.size} points" }
        check(points.toSet().size == SpawnPlan.TEAM) { "$where: points repeat, the plan fell back: $points" }
        val sight = Gaze(layout, emptyList())
        for ((index, point) in points.withIndex()) {
            val flags = layout.columnAt(point.x, point.z)
            check(Corridors.walkable(flags)) { "$where: $point is not walkable" }
            check(flags and (MazeLayout.PIT_ROOM or MazeLayout.DARK_ZONE) == 0) { "$where: $point is in a pit room or a dark zone" }
            checkNoDarkNear(layout, point, where)
            checkFacesOpen(layout, point, where)
            if (SpawnPlan.deadEnd(layout, point.x, point.z)) deadEnds++
            val reach = Corridors(layout, point.x, point.z, SpawnPlan.MEET)
            for (other in points.drop(index + 1)) {
                val steps = reach.steps(other.x, other.z)
                check(steps <= SpawnPlan.MEET) { "$where: $point and $other are $steps steps apart, more than the ring allows" }
                check(steps >= SpawnPlan.APART_RELAXED) { "$where: $point and $other are only $steps steps apart" }
                if (steps < SpawnPlan.APART) relaxed++
                check(!sight.clear(SpawnPlan.eye(point), SpawnPlan.eye(other))) { "$where: $point sees $other" }
            }
        }
    }
    val pairs = SEEDS * SpawnPlan.TEAM * (SpawnPlan.TEAM - 1) / 2
    check(relaxed * 10 < pairs) { "$relaxed of $pairs pairs needed the relaxed distance" }
    println(
        "spawn: $SEEDS plans, ${deadEnds * 100 / (SEEDS * SpawnPlan.TEAM)}% in dead ends, $relaxed/$pairs pairs relaxed, " +
            "%.1f ms average, %.1f ms slowest".format(total / SEEDS / 1e6, slowest / 1e6)
    )
}

private fun checkNoDarkNear(layout: MazeLayout, point: Corridors.Column, where: String) {
    val margin = SpawnPlan.DARK_MARGIN
    for (x in point.x - margin..point.x + margin) {
        for (z in point.z - margin..point.z + margin) {
            check(layout.columnAt(x, z) and MazeLayout.DARK_ZONE == 0) { "$where: $point has a dark zone at ($x, $z)" }
        }
    }
}

private fun checkFacesOpen(layout: MazeLayout, point: Corridors.Column, where: String) {
    val yaw = Math.toRadians(SpawnPlan.facing(layout, point).toDouble())
    val aheadX = point.x + Math.round(-Math.sin(yaw) * 3).toInt()
    val aheadZ = point.z + Math.round(Math.cos(yaw) * 3).toInt()
    check(Corridors.walkable(layout.columnAt(aheadX, aheadZ))) { "$where: $point faces a wall at ($aheadX, $aheadZ)" }
}
