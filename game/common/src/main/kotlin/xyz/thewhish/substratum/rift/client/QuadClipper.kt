package xyz.thewhish.substratum.rift.client

import net.minecraft.client.renderer.block.model.BakedQuad
import net.minecraft.core.Direction

object QuadClipper {

    private const val STRIDE = 8
    private const val COLOUR = 3
    private const val U = 4
    private const val V = 5

    private const val EPS = 1.0e-5f

    fun clip(quad: BakedQuad, axis: Direction.Axis, plane: Float, keepLess: Boolean, out: MutableList<BakedQuad>) {
        val source = quad.vertices
        val offset = axis.ordinal
        val distance = FloatArray(4)
        var kept = 0
        for (i in 0 until 4) {
            val coordinate = Float.fromBits(source[i * STRIDE + offset])
            distance[i] = if (keepLess) plane - coordinate else coordinate - plane
            if (distance[i] >= -EPS) kept++
        }
        if (kept == 0) return
        if (kept == 4) {
            out.add(quad)
            return
        }

        val polygon = IntArray(6 * STRIDE)
        var count = 0
        for (i in 0 until 4) {
            val j = (i + 1) and 3
            if (distance[i] >= -EPS) {
                source.copyInto(polygon, count * STRIDE, i * STRIDE, i * STRIDE + STRIDE)
                count++
            }
            if (crosses(distance[i], distance[j])) {
                interpolate(source, i, j, distance[i] / (distance[i] - distance[j]), polygon, count)
                count++
            }
        }
        if (count < 3) return
        fan(polygon, count, quad, out)
    }

    fun translate(quad: BakedQuad, axis: Direction.Axis, delta: Float): BakedQuad {
        val vertices = quad.vertices.copyOf()
        val offset = axis.ordinal
        for (i in 0 until 4) {
            val at = i * STRIDE + offset
            vertices[at] = (Float.fromBits(vertices[at]) + delta).toRawBits()
        }
        return BakedQuad(vertices, quad.tintIndex, quad.direction, quad.sprite, quad.isShade)
    }

    private fun crosses(from: Float, to: Float): Boolean =
        (from > EPS && to < -EPS) || (from < -EPS && to > EPS)

    private fun interpolate(source: IntArray, from: Int, to: Int, t: Float, target: IntArray, at: Int) {
        source.copyInto(target, at * STRIDE, from * STRIDE, from * STRIDE + STRIDE)
        for (component in intArrayOf(0, 1, 2, U, V)) {
            val a = Float.fromBits(source[from * STRIDE + component])
            val b = Float.fromBits(source[to * STRIDE + component])
            target[at * STRIDE + component] = (a + (b - a) * t).toRawBits()
        }
        target[at * STRIDE + COLOUR] = source[from * STRIDE + COLOUR]
    }

    private fun fan(polygon: IntArray, count: Int, source: BakedQuad, out: MutableList<BakedQuad>) {
        var i = 1
        while (i < count - 1) {
            val vertices = IntArray(4 * STRIDE)
            copy(polygon, 0, vertices, 0)
            copy(polygon, i, vertices, 1)
            copy(polygon, i + 1, vertices, 2)
            copy(polygon, if (i + 2 < count) i + 2 else i + 1, vertices, 3)
            out.add(BakedQuad(vertices, source.tintIndex, source.direction, source.sprite, source.isShade))
            i += 2
        }
    }

    private fun copy(from: IntArray, at: Int, to: IntArray, into: Int) =
        from.copyInto(to, into * STRIDE, at * STRIDE, at * STRIDE + STRIDE)
}
