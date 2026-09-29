package xyz.thewhish.substratum.level

import net.minecraft.core.BlockPos
import xyz.thewhish.substratum.registry.ModBlocks.LampCondition
import xyz.thewhish.substratum.worldgen.MazeChunkGenerator
import xyz.thewhish.substratum.worldgen.MazeLayout

private const val EVENTS = 60
private const val LAMPS = 400

fun main() {
    val shares = HashMap<LampCondition?, Int>()
    val timeline = IntArray((Blackouts.DURATION / 100).toInt())
    var lamps = 0
    var outLast = 0L
    var othersLast = 0L
    var outCount = 0
    var otherCount = 0
    repeat(EVENTS) { n ->
        val event = Blackouts.Event(n, 500_000L + n * 91, n * 0x9E3779B97F4AL + 3)
        repeat(LAMPS) { i ->
            val pos = BlockPos(i * 37 - n * 11, 5, i * 53 + n * 7)
            lamps++
            check(Blackouts.role(event, pos, -1) == null) { "lamp changes before the blackout starts" }
            check(Blackouts.role(event, pos, Blackouts.DURATION) == null) { "lamp still changed after the blackout ends" }
            val reach = Blackouts.reach(event, pos, false)
            check(reach in Blackouts.RADIUS - 5..Blackouts.RADIUS && Blackouts.reach(event, pos, true) == reach + Blackouts.HYSTERESIS) { "bad edge $reach" }
            val active = (0 until Blackouts.DURATION).filter { Blackouts.role(event, pos, it) != null }
            val role = active.firstOrNull()?.let { Blackouts.role(event, pos, it) }
            shares.merge(role, 1, Int::plus)
            if (role == null) return@repeat
            check(active.last() - active.first() + 1 == active.size.toLong()) { "lamp at $pos does not have one continuous change" }
            check(active.all { Blackouts.role(event, pos, it) == role }) { "lamp at $pos switches role mid-blackout" }
            check(active.first() < 300) { "lamp at $pos joins after the 15 s ramp" }
            if (role == LampCondition.OUT) {
                outLast += active.last()
                outCount++
            } else {
                othersLast += active.last()
                otherCount++
            }
            active.forEach { timeline[(it / 100).toInt()]++ }
        }
    }
    val flicker = shares.getValue(LampCondition.FLICKERING).toDouble() / lamps
    val out = shares.getValue(LampCondition.OUT).toDouble() / lamps
    val dim = shares.getValue(LampCondition.DIM).toDouble() / lamps
    val calm = shares.getValue(null).toDouble() / lamps
    check(flicker in 0.27..0.33 && out in 0.17..0.23 && dim in 0.22..0.28 && calm in 0.22..0.28) { "role shares off: $flicker $out $dim $calm" }
    check(outLast / outCount > othersLast / otherCount) { "dead-out lamps must come back last" }
    val affected = timeline.map { it / (lamps * 100.0) }
    check(affected.first() < 0.5 && affected[5] > 0.74 && affected.last() < 0.3) { "blackout does not rise, hold and fade: $affected" }
    val lamp = BlockPos(10, 5, 0)
    check(Blackouts.within(0.0, 1.0, 0.0, lamp, 12) && !Blackouts.within(0.0, 1.0, 0.0, lamp, 9)) { "circle test broken" }
    check(!Blackouts.within(0.0, 1.0 - Blackouts.HEIGHT - 5, 0.0, lamp, 12)) { "blackout leaks to another floor" }
    val feet = (MazeChunkGenerator.FLOOR_Y + 1).toDouble()
    check(Blackouts.within(0.0, feet, 0.0, BlockPos(3, MazeLayout.CEILING_HIGH, 0), 12)) { "lamps on a tall-room ceiling are left out of the blackout" }
    println(
        "Blackouts: all checks passed - %.0f%% flicker / %.0f%% out / %.0f%% dim / %.0f%% untouched; share of lamps changed by 5 s: %s"
            .format(flicker * 100, out * 100, dim * 100, calm * 100, affected.joinToString(" ") { "%.0f".format(it * 100) })
    )
}
