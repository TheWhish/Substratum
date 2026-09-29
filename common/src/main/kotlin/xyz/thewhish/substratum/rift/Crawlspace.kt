package xyz.thewhish.substratum.rift

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import xyz.thewhish.substratum.worldgen.MazeChunkGenerator

object Crawlspace {

    data class Site(val mouth: BlockPos, val deadEnd: BlockPos, val out: Direction, val length: Int) {
        fun corridor(): List<BlockPos> {
            val step = out.opposite
            return (1..length).map { i -> mouth.offset(step.stepX * i, 0, step.stepZ * i) }
        }
    }

    private const val FOOTING_RADIUS = 32

    private const val FRESH_MIN = 2_000
    private const val FRESH_SPAN = 8_000
    private const val FRESH_ATTEMPTS = 8

    private const val Y = MazeChunkGenerator.FLOOR_Y + 1

    class Plan internal constructor(
        private val mouthX: Int,
        private val mouthZ: Int,
        private val ax: Int,
        private val az: Int,
        private val length: Int,
        private val along: Direction
    ) {
        val mouth: BlockPos get() = BlockPos(mouthX, Y, mouthZ)

        internal fun carve(level: ServerLevel, through: Boolean): Site? {
            val cut = ArrayList<BlockPos>(length)
            for (i in 1..length) {
                val pos = BlockPos(mouthX + ax * i, Y, mouthZ + az * i)
                if (drive(level, pos, along, cut, through)) continue
                for (done in cut) Rifts.close(level, done)
                return null
            }
            val mouth = BlockPos(mouthX, Y, mouthZ)
            return Site(mouth, mouth.offset(ax * length, 0, az * length), along, length)
        }
    }

    fun plan(level: ServerLevel, x: Int, z: Int, axis: Direction.Axis?): Plan? {
        val generator = level.chunkSource.generator as? MazeChunkGenerator ?: return null
        val footing = generator.findFooting(x, z, FOOTING_RADIUS)
        val site = generator.crawlspace(footing[0], footing[1], axis?.let { it == Direction.Axis.X })
            ?: return null

        val ax = site[2]
        val az = site[3]
        val along = facing(-ax, -az)
        return Plan(site[0], site[1], ax, az, site[4], along)
    }

    fun openFresh(level: ServerLevel, axis: Direction.Axis, random: RandomSource, through: Boolean): Site? {
        repeat(FRESH_ATTEMPTS) {
            val x = (FRESH_MIN + random.nextInt(FRESH_SPAN)) * if (random.nextBoolean()) 1 else -1
            val z = (FRESH_MIN + random.nextInt(FRESH_SPAN)) * if (random.nextBoolean()) 1 else -1
            plan(level, x, z, axis)?.carve(level, through)?.let { return it }
        }
        return null
    }

    private fun drive(
        level: ServerLevel,
        pos: BlockPos,
        along: Direction,
        cut: MutableList<BlockPos>,
        through: Boolean
    ): Boolean {
        if (isRift(level, pos, along.axis)) return isRift(level, pos.above(), along.axis)
        if (!Rifts.cut(level, pos, along, through)) return false
        cut += pos
        return true
    }

    private fun isRift(level: ServerLevel, pos: BlockPos, axis: Direction.Axis): Boolean {
        val state = level.getBlockState(pos)
        return state.block is RiftCutBlock && RiftCutBlock.axisOf(state) == axis
    }

    private fun facing(dx: Int, dz: Int): Direction = when {
        dx > 0 -> Direction.EAST
        dx < 0 -> Direction.WEST
        dz > 0 -> Direction.SOUTH
        else -> Direction.NORTH
    }
}
