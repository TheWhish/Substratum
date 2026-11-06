package xyz.thewhish.substratum.worldgen

class Corridors(private val layout: MazeLayout, val originX: Int, val originZ: Int, val radius: Int) {

    data class Column(val x: Int, val z: Int)

    private val size = radius * 2 + 1
    private val flags = IntArray(size * size) { UNKNOWN }
    private val steps = IntArray(size * size) { UNREACHED }

    init {
        require(radius in 1..MAX_RADIUS) { "corridor radius must be within 1..$MAX_RADIUS, got $radius" }
        spread()
    }

    fun steps(x: Int, z: Int): Int {
        val i = index(x, z)
        return if (i < 0) UNREACHED else steps[i]
    }

    fun flags(x: Int, z: Int): Int {
        val i = index(x, z)
        if (i < 0) return layout.columnAt(x, z)
        if (flags[i] == UNKNOWN) flags[i] = layout.columnAt(x, z)
        return flags[i]
    }

    fun roomy(x: Int, z: Int): Boolean {
        if (!walkable(flags(x, z))) return false
        for (dx in -1..1) for (dz in -1..1) if (flags(x + dx, z + dz) and MazeLayout.SOLID != 0) return false
        return true
    }

    fun reached(action: (x: Int, z: Int, steps: Int) -> Unit) {
        for (i in steps.indices) {
            if (steps[i] != UNREACHED) action(originX - radius + i / size, originZ - radius + i % size, steps[i])
        }
    }

    private fun spread() {
        if (!walkable(flags(originX, originZ))) return
        val queue = IntArray(size * size)
        var head = 0
        var tail = 0
        val start = index(originX, originZ)
        steps[start] = 0
        queue[tail++] = start
        while (head < tail) {
            val current = queue[head++]
            val step = steps[current]
            if (step == radius) continue
            val i = current / size
            val j = current % size
            for (d in 0 until 4) {
                val ni = i + DX[d]
                val nj = j + DZ[d]
                if (ni !in 0 until size || nj !in 0 until size) continue
                val next = ni * size + nj
                if (steps[next] != UNREACHED || !walkable(flags(originX - radius + ni, originZ - radius + nj))) continue
                steps[next] = step + 1
                queue[tail++] = next
            }
        }
    }

    private fun index(x: Int, z: Int): Int {
        val i = x - originX + radius
        val j = z - originZ + radius
        return if (i in 0 until size && j in 0 until size) i * size + j else -1
    }

    companion object {
        const val UNREACHED = Int.MAX_VALUE
        const val MAX_RADIUS = 128
        private const val UNKNOWN = -1
        private val DX = intArrayOf(1, -1, 0, 0)
        private val DZ = intArrayOf(0, 0, 1, -1)

        fun walkable(flags: Int): Boolean = flags and (MazeLayout.SOLID or MazeLayout.PIT) == 0
    }
}
