package xyz.thewhish.substratum.rift

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.SectionPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.FluidTags
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.NoiseColumn
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap

object SafeLanding {

    fun find(level: ServerLevel, center: BlockPos, minDist: Int, maxDist: Int, attempts: Int, random: RandomSource): BlockPos? {
        findInRing(level, center, minDist, maxDist, attempts, random)?.let { return it }
        return findInRing(level, center, maxDist, maxDist * 2, attempts, random)
    }

    private fun findInRing(level: ServerLevel, center: BlockPos, minDist: Int, maxDist: Int, attempts: Int, random: RandomSource): BlockPos? {
        repeat(attempts) {
            val angle = random.nextDouble() * 2.0 * Math.PI
            val dist = minDist + random.nextInt(maxDist - minDist + 1)
            val x = center.x + (Math.cos(angle) * dist).toInt()
            val z = center.z + (Math.sin(angle) * dist).toInt()
            if (!level.worldBorder.isWithinBounds(x.toDouble(), z.toDouble())) return@repeat
            val loaded = level.chunkSource.getChunkNow(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))
            val found = if (loaded != null) fromWorld(level, x, z) else fromNoise(level, x, z)
            if (found != null) return found
        }
        return null
    }

    fun settle(level: ServerLevel, column: BlockPos): BlockPos? {
        val chunk = level.getChunk(column).pos
        fromWorld(level, column.x, column.z)?.let { return it }
        for (x in chunk.minBlockX..chunk.maxBlockX) {
            for (z in chunk.minBlockZ..chunk.maxBlockZ) fromWorld(level, x, z)?.let { return it }
        }
        return null
    }

    fun isClear(level: ServerLevel, feet: BlockPos): Boolean =
        level.noCollision(EntityType.PLAYER.getSpawnAABB(feet.x + 0.5, feet.y.toDouble(), feet.z + 0.5))

    private fun fromWorld(level: ServerLevel, x: Int, z: Int): BlockPos? {
        val feet = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos(x, 0, z))
        if (feet.y <= level.minBuildHeight + 1 || !level.worldBorder.isWithinBounds(feet)) return null
        val ground = level.getBlockState(feet.below())
        if (!ground.isFaceSturdy(level, feet.below(), Direction.UP) || isHazard(ground)) return null
        if (isHazard(level.getBlockState(feet)) || isHazard(level.getBlockState(feet.above()))) return null
        return feet.takeIf { isClear(level, it) }
    }

    private fun isHazard(state: BlockState): Boolean =
        !state.fluidState.isEmpty || EntityType.PLAYER.isBlockDangerous(state)

    private fun fromNoise(level: ServerLevel, x: Int, z: Int): BlockPos? {
        val column = level.chunkSource.generator.getBaseColumn(x, z, level, level.chunkSource.randomState())
        val y = surfaceY(column, level.minBuildHeight, level.maxBuildHeight) ?: return null
        return BlockPos(x, y, z).takeIf {
            isSafe(column.getBlock(y - 1), column.getBlock(y), column.getBlock(y + 1))
        }
    }

    private fun surfaceY(column: NoiseColumn, minY: Int, maxY: Int): Int? {
        for (y in maxY - 3 downTo minY + 1) {
            if (!column.getBlock(y).isAir) return y + 1
        }
        return null
    }

    private fun isSafe(ground: BlockState, feet: BlockState, head: BlockState): Boolean {
        if (ground.fluidState.`is`(FluidTags.LAVA)) return false
        if (!ground.fluidState.isEmpty) return false
        if (ground.isAir) return false
        return feet.isAir && head.isAir
    }
}
