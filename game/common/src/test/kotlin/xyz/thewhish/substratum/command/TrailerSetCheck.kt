package xyz.thewhish.substratum.command

import xyz.thewhish.substratum.worldgen.MazeLayout

fun main() {
    var found = 0
    var farthest = 0
    repeat(12) { i ->
        val layout = MazeLayout(11L + i * 9_999_991L)
        val origin = MazeLayout.CENTRE + i * 1_000
        val corridor = TrailerSet.find(layout, origin, -origin, 48) ?: return@repeat
        found++
        val start = corridor.pos(0, 0, 0)
        check(maxOf(Math.abs(start.x - origin), Math.abs(start.z + origin)) >= 48 * MazeLayout.CELL - MazeLayout.CELL) { "seed $i: corridor is inside the loaded range" }
        farthest = maxOf(farthest, Math.hypot((start.x - origin).toDouble(), (start.z + origin).toDouble()).toInt())
        fun column(along: Int) = corridor.pos(along, 0, 0).let { layout.columnAt(it.x, it.z) }
        for (k in 0 until 9) {
            val lamp = column(4 + k * MazeLayout.CELL)
            check(lamp and MazeLayout.LAMP != 0 && lamp and (MazeLayout.SOLID or MazeLayout.DARK_ZONE) == 0) { "seed $i: run cell $k is not a lit open lamp cell" }
            check(MazeLayout.ceilingOf(lamp) == MazeLayout.CEILING_LOW) { "seed $i: run cell $k has a different ceiling" }
        }
        for (along in 0 until 9 * MazeLayout.CELL - 1) check(column(along) and MazeLayout.SOLID == 0) { "seed $i: wall inside the run at $along" }
    }
    check(found >= 11) { "only $found of 12 starts found a long straight lit corridor" }
    println("trailer: long straight lit corridor found from $found of 12 starts, beyond 384 blocks, farthest $farthest blocks away")
}
