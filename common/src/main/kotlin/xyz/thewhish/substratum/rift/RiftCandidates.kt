package xyz.thewhish.substratum.rift

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.SectionPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BlockTags
import net.minecraft.util.RandomSource
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import xyz.thewhish.substratum.level.SubstratumLevels

object RiftCandidates {

    data class Candidate(val lower: BlockPos, val mouth: Direction, val weight: Double = 1.0)

    private const val HOME_WEIGHT = 50
    private const val MINING_WEIGHT = 30
    private const val FAMILIAR_WEIGHT = 25
    private const val RESERVE_WEIGHT = 10

    private const val HOME_RADIUS = 32
    private const val HOME_REACH = 48
    private const val HOME_DOWN = 6
    private const val HOME_UP = 16

    private const val FAMILIAR_RADIUS = 14
    private const val FAMILIAR_DOWN = 3
    private const val FAMILIAR_UP = 6

    private const val MINING_RADIUS = 2

    private const val RESERVE_MIN_DIST = 8
    private const val RESERVE_MAX_DIST = 16
    private const val RESERVE_MAX_LIGHT = 7
    private const val RESERVE_DOWN = 3
    private const val RESERVE_UP = 6

    private const val OPEN_SKY_LIGHT = 8
    private const val BUILT_WEIGHT = 4.0

    private const val MINING_FRESH_TICKS = 20 * 30
    private const val MINING_MIN_DEPTH = 8

    private const val SAMPLE_ATTEMPTS = 48

    private const val POOL_CAP = 8

    private const val TRUNK_WEIGHT = 2.0

    private const val MAX_AIR_GAP = 4

    fun pick(level: ServerLevel, player: ServerPlayer, random: RandomSource): Candidate? {
        val outdoors = isOutdoors(level, player.blockPosition())
        val sources = buildList<Pair<Int, () -> Candidate?>> {
            add(HOME_WEIGHT to { homeSource(level, player, random, outdoors) })
            RiftSpawnData.lastMined(player, level.dimension())?.let { (pos, tick) ->
                if (level.server.tickCount - tick <= MINING_FRESH_TICKS && isColumnLoaded(level, pos.x, pos.z) &&
                    level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.x, pos.z) - pos.y >= MINING_MIN_DEPTH
                ) add(MINING_WEIGHT to { miningSource(level, pos, random, outdoors) })
            }
            add(FAMILIAR_WEIGHT to { familiarSource(level, player, random, outdoors) })
            add(RESERVE_WEIGHT to { reserveSource(level, player, random, outdoors) })
        }.toMutableList()

        while (sources.isNotEmpty()) {
            val totalWeight = sources.sumOf { it.first }
            var roll = random.nextInt(totalWeight)
            var index = 0
            for (i in sources.indices) {
                if (roll < sources[i].first) {
                    index = i
                    break
                }
                roll -= sources[i].first
            }
            val (_, sampler) = sources.removeAt(index)
            sampler()?.let { return it }
        }
        return null
    }

    private fun homeSource(level: ServerLevel, player: ServerPlayer, random: RandomSource, outdoors: Boolean): Candidate? {
        if (player.respawnDimension != level.dimension()) return null
        val center = player.respawnPosition ?: return null
        if (!center.closerThan(player.blockPosition(), HOME_REACH.toDouble())) return null
        return sampleCandidate(level, random, center, HOME_RADIUS, HOME_DOWN, HOME_UP, naturalFilter = true, outdoors)
    }

    private fun miningSource(level: ServerLevel, pos: BlockPos, random: RandomSource, outdoors: Boolean): Candidate? =
        sampleCandidate(level, random, pos, MINING_RADIUS, MINING_RADIUS, MINING_RADIUS, naturalFilter = false, outdoors)

    private fun familiarSource(level: ServerLevel, player: ServerPlayer, random: RandomSource, outdoors: Boolean): Candidate? =
        sampleCandidate(level, random, player.blockPosition(), FAMILIAR_RADIUS, FAMILIAR_DOWN, FAMILIAR_UP, naturalFilter = false, outdoors)

    private fun reserveSource(level: ServerLevel, player: ServerPlayer, random: RandomSource, outdoors: Boolean): Candidate? {
        val origin = player.blockPosition()
        val pool = ArrayList<Candidate>(POOL_CAP)
        repeat(SAMPLE_ATTEMPTS) {
            if (pool.size >= POOL_CAP) return@repeat
            val angle = random.nextDouble() * 2.0 * Math.PI
            val dist = RESERVE_MIN_DIST + random.nextInt(RESERVE_MAX_DIST - RESERVE_MIN_DIST + 1)
            val x = origin.x + (Math.cos(angle) * dist).toInt()
            val z = origin.z + (Math.sin(angle) * dist).toInt()
            if (!isColumnLoaded(level, x, z)) return@repeat
            val minY = (level.minBuildHeight).coerceAtLeast(origin.y - RESERVE_DOWN)
            val maxY = (level.maxBuildHeight - 2).coerceAtMost(origin.y + RESERVE_UP)
            for (y in minY..maxY) {
                val lower = BlockPos(x, y, z)
                if (level.getBrightness(LightLayer.BLOCK, lower) > RESERVE_MAX_LIGHT) continue
                val candidate = candidateAt(level, lower, random, naturalFilter = false, outdoors) ?: continue
                pool.add(candidate)
                break
            }
        }
        return weightedPick(pool, random)
    }

    private fun sampleCandidate(
        level: ServerLevel,
        random: RandomSource,
        center: BlockPos,
        radius: Int,
        downMargin: Int,
        upSpan: Int,
        naturalFilter: Boolean,
        outdoors: Boolean
    ): Candidate? {
        val pool = ArrayList<Candidate>(POOL_CAP)
        val minY = (level.minBuildHeight).coerceAtLeast(center.y - downMargin)
        val maxY = (level.maxBuildHeight - 2).coerceAtMost(center.y + upSpan)
        repeat(SAMPLE_ATTEMPTS) {
            if (pool.size >= POOL_CAP) return@repeat
            val dx = random.nextInt(radius * 2 + 1) - radius
            val dz = random.nextInt(radius * 2 + 1) - radius
            if (dx * dx + dz * dz > radius * radius) return@repeat
            val x = center.x + dx
            val z = center.z + dz
            if (!isColumnLoaded(level, x, z)) return@repeat
            for (y in minY..maxY) {
                val candidate = candidateAt(level, BlockPos(x, y, z), random, naturalFilter, outdoors) ?: continue
                pool.add(candidate)
                break
            }
        }
        return weightedPick(pool, random)
    }

    private fun weightedPick(pool: List<Candidate>, random: RandomSource): Candidate? {
        if (pool.isEmpty()) return null
        var roll = random.nextDouble() * pool.sumOf { it.weight }
        for (candidate in pool) {
            roll -= candidate.weight
            if (roll <= 0.0) return candidate
        }
        return pool.last()
    }

    private fun candidateAt(level: ServerLevel, lower: BlockPos, random: RandomSource, naturalFilter: Boolean, outdoors: Boolean): Candidate? {
        if (level.dimension() == SubstratumLevels.LEVEL_0) return null
        val upper = lower.above()
        if (!Rifts.canHost(level, lower) || !Rifts.canHost(level, upper)) return null
        val lowerState = level.getBlockState(lower)
        val upperState = level.getBlockState(upper)
        if (lowerState.`is`(BlockTags.LEAVES) || upperState.`is`(BlockTags.LEAVES)) return null
        val trunk = lowerState.`is`(BlockTags.LOGS)
        if (trunk != upperState.`is`(BlockTags.LOGS)) return null
        if (naturalFilter && looksNatural(lowerState)) return null
        if (!isSupported(level, lower)) return null
        val weight = when {
            trunk -> TRUNK_WEIGHT
            !looksNatural(lowerState) && !looksNatural(upperState) -> BUILT_WEIGHT
            else -> 1.0
        }
        for (mouth in shuffledHorizontal(random)) {
            if (isApproachable(level, lower, mouth, outdoors)) return Candidate(lower, mouth, weight)
        }
        return null
    }

    private fun isApproachable(level: ServerLevel, lower: BlockPos, mouth: Direction, outdoors: Boolean): Boolean {
        if (!isOpenFace(level, lower, mouth) || !isOpenFace(level, lower.above(), mouth)) return false
        val front = lower.relative(mouth)
        val floor = front.below()
        return level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP) && isOutdoors(level, front) == outdoors
    }

    private fun isOutdoors(level: ServerLevel, pos: BlockPos): Boolean =
        level.getBrightness(LightLayer.SKY, pos) >= OPEN_SKY_LIGHT

    private fun looksNatural(state: BlockState): Boolean =
        NATURAL.any(state::`is`) || state.`is`(Blocks.GRAVEL) || state.`is`(Blocks.CLAY) || state.`is`(Blocks.SANDSTONE) ||
            state.`is`(Blocks.RED_SANDSTONE) || state.`is`(Blocks.MUD) || state.`is`(Blocks.OBSIDIAN)

    private val NATURAL = listOf(
        BlockTags.DIRT, BlockTags.BASE_STONE_OVERWORLD, BlockTags.BASE_STONE_NETHER, BlockTags.SAND, BlockTags.TERRACOTTA,
        BlockTags.ICE, BlockTags.SNOW, BlockTags.NYLIUM, BlockTags.COAL_ORES, BlockTags.IRON_ORES, BlockTags.COPPER_ORES,
        BlockTags.GOLD_ORES, BlockTags.REDSTONE_ORES, BlockTags.LAPIS_ORES, BlockTags.DIAMOND_ORES, BlockTags.EMERALD_ORES
    )

    private fun isSupported(level: ServerLevel, lower: BlockPos): Boolean =
        (1..MAX_AIR_GAP).any { depth ->
            val below = lower.below(depth)
            val state = level.getBlockState(below)
            !state.`is`(BlockTags.LEAVES) && state.isCollisionShapeFullBlock(level, below)
        }

    private fun isOpenFace(level: ServerLevel, pos: BlockPos, dir: Direction): Boolean {
        val neighbor = pos.relative(dir)
        val state = level.getBlockState(neighbor)
        return state.fluidState.isEmpty && !state.isCollisionShapeFullBlock(level, neighbor)
    }

    private fun isColumnLoaded(level: ServerLevel, x: Int, z: Int): Boolean =
        isLoaded(level, x - 1, z - 1) && isLoaded(level, x + 1, z + 1) &&
            isLoaded(level, x - 1, z + 1) && isLoaded(level, x + 1, z - 1)

    private fun isLoaded(level: ServerLevel, x: Int, z: Int): Boolean =
        level.chunkSource.getChunkNow(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z)) != null

    private fun shuffledHorizontal(random: RandomSource): MutableList<Direction> {
        val dirs = Direction.Plane.HORIZONTAL.toMutableList()
        for (i in dirs.size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = dirs[i]
            dirs[i] = dirs[j]
            dirs[j] = tmp
        }
        return dirs
    }
}
