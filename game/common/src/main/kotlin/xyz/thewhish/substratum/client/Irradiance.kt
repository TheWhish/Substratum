package xyz.thewhish.substratum.client

import xyz.thewhish.substratum.worldgen.MazeChunkGenerator
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

object Irradiance {

    const val REACH = 16
    const val SPREAD = 15
    const val LAMP_WINDOW = REACH + SPREAD + 1
    const val DARK = 1
    const val BRIGHT = 15

    private const val CORE = 5.0
    private const val TAPER = 4.0
    private const val EYE_Y = MazeChunkGenerator.FLOOR_Y + 2.5
    private const val NEAR = 6
    private const val BOUNCE = 1.0f
    private const val EXPOSURE = 0.6f
    private const val STEPS_PER_BLOCK = 2

    private val BAYER = floatArrayOf(0.125f, 0.625f, 0.875f, 0.375f)

    fun direct(
        open: BooleanArray,
        depth: Int,
        lampI: Int,
        lampJ: Int,
        lampY: Int,
        out: FloatArray,
        i0: Int,
        j0: Int,
        i1: Int,
        j1: Int,
        scale: Float
    ) {
        val h = lampY - EYE_Y
        for (i in maxOf(i0, lampI - REACH)..minOf(i1, lampI + REACH)) {
            for (j in maxOf(j0, lampJ - REACH)..minOf(j1, lampJ + REACH)) {
                val cell = i * depth + j
                if (!open[cell]) continue
                val di = (i - lampI).toDouble()
                val dj = (j - lampJ).toDouble()
                val d = sqrt(di * di + dj * dj + h * h)
                if (d >= REACH || !sees(open, depth, lampI, lampJ, i, j)) continue
                val t = ((REACH - d) / TAPER).coerceAtMost(1.0)
                out[cell] += scale * (t * t * (3 - 2 * t) / (1 + (d / CORE) * (d / CORE))).toFloat()
            }
        }
    }

    fun spread(open: BooleanArray, width: Int, depth: Int, light: FloatArray): FloatArray {
        var from = light.copyOf()
        var to = FloatArray(light.size)
        val near = FloatArray(light.size)
        for (pass in 1..SPREAD) {
            for (i in 0 until width) {
                for (j in 0 until depth) {
                    val cell = i * depth + j
                    if (!open[cell]) {
                        to[cell] = 0f
                        continue
                    }
                    var sum = from[cell]
                    var count = 1
                    if (i > 0 && open[cell - depth]) { sum += from[cell - depth]; count++ }
                    if (i < width - 1 && open[cell + depth]) { sum += from[cell + depth]; count++ }
                    if (j > 0 && open[cell - 1]) { sum += from[cell - 1]; count++ }
                    if (j < depth - 1 && open[cell + 1]) { sum += from[cell + 1]; count++ }
                    to[cell] = sum / count
                }
            }
            val swap = from
            from = to
            to = swap
            if (pass == NEAR) from.copyInto(near)
        }
        for (cell in near.indices) near[cell] += BOUNCE * from[cell]
        return near
    }

    fun lift(energy: Float): Float = (BRIGHT - DARK) * sqrt(1 - exp(-EXPOSURE * energy.coerceAtLeast(0f)))

    fun level(energy: Float, dither: Float): Int = minOf(BRIGHT, DARK + floor(lift(energy) + dither).toInt())

    fun dither(x: Int, y: Int, z: Int): Float = BAYER[(((x xor y) and 1) shl 1) or ((z xor y) and 1)]

    private fun sees(open: BooleanArray, depth: Int, ai: Int, aj: Int, bi: Int, bj: Int): Boolean {
        val di = bi - ai
        val dj = bj - aj
        val steps = ceil(sqrt((di * di + dj * dj).toDouble()) * STEPS_PER_BLOCK).toInt()
        for (k in 1 until steps) {
            val t = k.toDouble() / steps
            val i = floor(ai + 0.5 + di * t).toInt()
            val j = floor(aj + 0.5 + dj * t).toInt()
            if (!open[i * depth + j]) return false
        }
        return true
    }
}
