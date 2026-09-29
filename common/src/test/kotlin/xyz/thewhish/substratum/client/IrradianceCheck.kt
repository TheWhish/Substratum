package xyz.thewhish.substratum.client

import xyz.thewhish.substratum.worldgen.MazeChunkGenerator
import xyz.thewhish.substratum.worldgen.MazeLayout
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

private const val SIDE = 16
private const val CHUNKS = 12
private const val SIZE = SIDE * CHUNKS
private const val PIXELS = 6
private const val PROBE_Y = MazeChunkGenerator.FLOOR_Y + 1

private class Plan(val open: BooleanArray, val lamps: List<IntArray>, val dead: List<IntArray>, val darkZone: Int)

fun main() {
    val layout = MazeLayout(-3L)
    val plan = (0 until 64).map { planAt(layout, -SIZE / 2 + it * SIZE) }.maxBy { it.darkZone }
    val whole = FloatArray(SIZE * SIZE)
    for (lamp in plan.lamps) Irradiance.direct(plan.open, SIZE, lamp[0], lamp[1], lamp[2], whole, 0, 0, SIZE - 1, SIZE - 1, 1f)
    val field = Irradiance.spread(plan.open, SIZE, SIZE, whole)

    checkChunkWindow(plan, field)
    checkLampDelta(plan, field)
    val levels = IntArray(SIZE * SIZE) { if (plan.open[it]) Irradiance.level(field[it], Irradiance.dither(it / SIZE, PROBE_Y, it % SIZE)) else 0 }
    render(plan, levels)
    checkLevels(plan, field, levels)
}

private fun planAt(layout: MazeLayout, origin: Int): Plan {
    val open = BooleanArray(SIZE * SIZE)
    val lamps = ArrayList<IntArray>()
    val dead = ArrayList<IntArray>()
    var darkZone = 0
    for (i in 0 until SIZE) for (j in 0 until SIZE) {
        val column = layout.columnAt(origin + i, origin + j)
        if (column and MazeLayout.DARK_ZONE != 0) darkZone++
        open[i * SIZE + j] = column and MazeLayout.SOLID == 0
        if (column and MazeLayout.SOLID != 0) continue
        val lamp = intArrayOf(i, j, MazeLayout.ceilingOf(column))
        if (column and MazeLayout.LAMP != 0) lamps.add(lamp)
        else if (column and MazeLayout.DEAD_LAMP != 0) dead.add(lamp)
    }
    return Plan(open, lamps, dead, darkZone)
}

private fun checkChunkWindow(plan: Plan, field: FloatArray) {
    val direct = HashMap<Int, FloatArray>()
    fun directOf(cx: Int, cz: Int): FloatArray = direct.getOrPut(cx * CHUNKS + cz) {
        val size = SIDE + 2 * Irradiance.REACH
        val wx = cx * SIDE - Irradiance.REACH
        val wz = cz * SIDE - Irradiance.REACH
        val open = window(plan.open, wx, wz, size)
        val grid = FloatArray(size * size)
        for (lamp in plan.lamps) {
            val i = lamp[0] - wx
            val j = lamp[1] - wz
            if (i !in 0 until size || j !in 0 until size) continue
            val lo = Irradiance.REACH
            val hi = Irradiance.REACH + SIDE - 1
            Irradiance.direct(open, size, i, j, lamp[2], grid, lo, lo, hi, hi, 1f)
        }
        FloatArray(SIDE * SIDE) { grid[(it / SIDE + Irradiance.REACH) * size + it % SIDE + Irradiance.REACH] }
    }
    for (cx in 2 until CHUNKS - 2) for (cz in 2 until CHUNKS - 2) {
        val size = 3 * SIDE
        val open = window(plan.open, (cx - 1) * SIDE, (cz - 1) * SIDE, size)
        val energy = FloatArray(size * size)
        for (dx in 0..2) for (dz in 0..2) {
            val d = directOf(cx + dx - 1, cz + dz - 1)
            for (c in 0 until SIDE * SIDE) energy[(dx * SIDE + c / SIDE) * size + dz * SIDE + c % SIDE] = d[c]
        }
        val spread = Irradiance.spread(open, size, size, energy)
        for (c in 0 until SIDE * SIDE) {
            val local = spread[(c / SIDE + SIDE) * size + c % SIDE + SIDE]
            val global = field[(cx * SIDE + c / SIDE) * SIZE + cz * SIDE + c % SIDE]
            check(abs(local - global) < 1e-4f) { "chunk ($cx, $cz) cell $c: window $local, whole $global" }
        }
    }
}

private fun checkLampDelta(plan: Plan, field: FloatArray) {
    val r = Irradiance.LAMP_WINDOW
    val size = 2 * r + 1
    val lamp = plan.lamps.first { it[0] in r until SIZE - r && it[1] in r until SIZE - r }
    val without = FloatArray(SIZE * SIZE)
    for (other in plan.lamps) {
        if (other !== lamp) Irradiance.direct(plan.open, SIZE, other[0], other[1], other[2], without, 0, 0, SIZE - 1, SIZE - 1, 1f)
    }
    val base = Irradiance.spread(plan.open, SIZE, SIZE, without)
    val open = window(plan.open, lamp[0] - r, lamp[1] - r, size)
    val light = FloatArray(size * size)
    Irradiance.direct(open, size, r, r, lamp[2], light, 0, 0, size - 1, size - 1, 1f)
    val delta = Irradiance.spread(open, size, size, light)
    for (i in 0 until size) for (j in 0 until size) {
        val cell = (lamp[0] - r + i) * SIZE + lamp[1] - r + j
        val sum = base[cell] + delta[i * size + j]
        check(abs(sum - field[cell]) < 1e-4f) { "lamp delta off by ${sum - field[cell]} at ($i, $j)" }
    }
}

private fun checkLevels(plan: Plan, field: FloatArray, stored: IntArray) {
    val levels = FloatArray(stored.size) { stored[it].toFloat() }
    val exact = FloatArray(SIZE * SIZE) { if (plan.open[it]) Irradiance.DARK + Irradiance.lift(field[it]) else 0f }
    var worst = 0f
    var error = 0.0
    var vertices = 0
    for (i in 1 until SIZE) for (j in 1 until SIZE) {
        val here = corner(plan, levels, i, j) ?: continue
        val truth = corner(plan, exact, i, j) ?: continue
        error += abs(here - truth)
        vertices++
        for ((a, b) in listOf(i + 1 to j, i to j + 1)) {
            val next = corner(plan, levels, a, b) ?: continue
            val nextTruth = corner(plan, exact, a, b) ?: continue
            worst = maxOf(worst, abs(here - next) - abs(truth - nextTruth))
        }
    }
    var dark = 0
    var open = 0
    for (cell in levels.indices) {
        if (!plan.open[cell]) continue
        open++
        check(stored[cell] in Irradiance.DARK..Irradiance.BRIGHT) { "open cell $cell stored outside ${Irradiance.DARK}..${Irradiance.BRIGHT}" }
        if (stored[cell] == Irradiance.DARK) dark++
    }
    val meanError = error / vertices
    val darkShare = dark.toDouble() / open
    println(
        "Irradiance: rounding adds at most ${"%.2f".format(worst)} levels between smoothed corners, mean corner error ${"%.3f".format(meanError)} levels, " +
            "${"%.1f".format(darkShare * 100)}% of the floor fully dark, ${plan.lamps.size} lit / ${plan.dead.size} dead lamps."
    )
    check(worst <= 1f) { "rounding adds a ${"%.2f".format(worst)}-level step between smoothed corners" }
    check(meanError < 0.2) { "dithered corners stray ${"%.3f".format(meanError)} levels from the exact field" }
    check(darkShare in 0.01..0.35) { "${"%.1f".format(darkShare * 100)}% of the floor is fully dark" }
}

private fun corner(plan: Plan, values: FloatArray, i: Int, j: Int): Float? {
    var sum = 0f
    var count = 0
    for (a in i - 1..i) for (b in j - 1..j) {
        if (a !in 0 until SIZE || b !in 0 until SIZE || !plan.open[a * SIZE + b]) continue
        sum += values[a * SIZE + b]
        count++
    }
    return if (count == 0) null else sum / count
}

private fun window(open: BooleanArray, wx: Int, wz: Int, size: Int): BooleanArray = BooleanArray(size * size) {
    val x = wx + it / size
    val z = wz + it % size
    x in 0 until SIZE && z in 0 until SIZE && open[x * SIZE + z]
}

private fun render(plan: Plan, levels: IntArray) {
    val image = BufferedImage(SIZE * PIXELS, SIZE * PIXELS, BufferedImage.TYPE_INT_RGB)
    val values = FloatArray(levels.size) { levels[it].toFloat() }
    for (i in 0 until SIZE) for (j in 0 until SIZE) {
        val openCell = plan.open[i * SIZE + j]
        val c00 = corner(plan, values, i, j) ?: 0f
        val c10 = corner(plan, values, i + 1, j) ?: 0f
        val c01 = corner(plan, values, i, j + 1) ?: 0f
        val c11 = corner(plan, values, i + 1, j + 1) ?: 0f
        for (u in 0 until PIXELS) for (v in 0 until PIXELS) {
            val fu = (u + 0.5f) / PIXELS
            val fv = (v + 0.5f) / PIXELS
            val level = (c00 * (1 - fu) + c10 * fu) * (1 - fv) + (c01 * (1 - fu) + c11 * fu) * fv
            val shade = if (openCell) 0.025f + 0.975f * ((level - Irradiance.DARK).coerceAtLeast(0f) / 14f).let { it * it } else 0.1f
            val grey = (Math.pow((shade * 0.7f).toDouble(), 1 / 2.2) * 255).toInt().coerceIn(0, 255)
            image.setRGB(i * PIXELS + u, j * PIXELS + v, (grey shl 16) or (grey * 15 / 16 shl 8) or (grey * 3 / 4))
        }
    }
    for (lamp in plan.lamps) image.setRGB(lamp[0] * PIXELS + PIXELS / 2, lamp[1] * PIXELS + PIXELS / 2, 0xFFFF40)
    for (lamp in plan.dead) image.setRGB(lamp[0] * PIXELS + PIXELS / 2, lamp[1] * PIXELS + PIXELS / 2, 0xFF2020)
    ImageIO.write(image, "png", File("irradiance.png").absoluteFile.also { println("Irradiance: wrote $it") })
}
