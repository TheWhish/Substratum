package xyz.thewhish.substratum.worldgen

import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.HolderLookup
import net.minecraft.server.level.WorldGenRegion
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.NoiseColumn
import net.minecraft.world.level.StructureManager
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeManager
import net.minecraft.world.level.biome.FixedBiomeSource
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.RandomState
import net.minecraft.world.level.levelgen.blending.Blender
import net.minecraft.world.level.levelgen.structure.StructureSet
import xyz.thewhish.substratum.level.Epoch
import xyz.thewhish.substratum.registry.ModBlocks
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream

class MazeChunkGenerator(val biome: Holder<Biome>) : ChunkGenerator(FixedBiomeSource(biome)) {

    @Volatile
    private var layout = MazeLayout(0L)

    override fun createState(
        structureSets: HolderLookup<StructureSet>,
        randomState: RandomState,
        seed: Long
    ): ChunkGeneratorStructureState {
        val salted = Epoch.salt(seed)
        if (layout.seed != salted) layout = MazeLayout(salted)
        return ChunkGeneratorStructureState.createForFlat(randomState, seed, biomeSource, Stream.empty())
    }

    override fun codec(): MapCodec<out ChunkGenerator> = CODEC

    override fun fillFromNoise(
        blender: Blender,
        randomState: RandomState,
        structureManager: StructureManager,
        chunk: ChunkAccess
    ): CompletableFuture<ChunkAccess> {
        val layout = this.layout
        val pos = chunk.pos
        val cursor = BlockPos.MutableBlockPos()
        for (x in 0 until 16) {
            for (z in 0 until 16) {
                val worldX = pos.minBlockX + x
                val worldZ = pos.minBlockZ + z
                val column = layout.columnAt(worldX, worldZ)

                val bottomY = if (column and MazeLayout.PIT_ROOM != 0) BARRIER_Y else SUBFLOOR_Y
                for (y in bottomY..TOP_Y) {
                    val state = blockAt(layout, worldX, y, worldZ, column)
                    if (!state.isAir) {
                        chunk.setBlockState(cursor.set(worldX, y, worldZ), state, false)
                    }
                }

                if (bottomY != BARRIER_Y) {
                    chunk.setBlockState(cursor.set(worldX, BARRIER_Y, worldZ), Palette.abyssFloor, false)
                }
            }
        }
        return CompletableFuture.completedFuture(chunk)
    }

    override fun buildSurface(
        level: WorldGenRegion,
        structureManager: StructureManager,
        randomState: RandomState,
        chunk: ChunkAccess
    ) = Unit

    override fun applyCarvers(
        level: WorldGenRegion,
        seed: Long,
        randomState: RandomState,
        biomeManager: BiomeManager,
        structureManager: StructureManager,
        chunk: ChunkAccess,
        step: GenerationStep.Carving
    ) = Unit

    override fun spawnOriginalMobs(level: WorldGenRegion) = Unit

    override fun getGenDepth(): Int = HEIGHT

    override fun getSeaLevel(): Int = 0

    override fun getMinY(): Int = MIN_Y

    override fun getSpawnHeight(level: LevelHeightAccessor): Int = FLOOR_Y + 1

    override fun getBaseHeight(
        x: Int,
        z: Int,
        type: Heightmap.Types,
        level: LevelHeightAccessor,
        randomState: RandomState
    ): Int = TOP_Y + 1

    override fun getBaseColumn(
        x: Int,
        z: Int,
        level: LevelHeightAccessor,
        randomState: RandomState
    ): NoiseColumn {
        val layout = this.layout
        val column = layout.columnAt(x, z)
        val states = Array(genDepth) { i -> blockAt(layout, x, minY + i, z, column) }
        return NoiseColumn(minY, states)
    }

    override fun addDebugScreenInfo(info: MutableList<String>, randomState: RandomState, pos: BlockPos) = Unit

    fun findFooting(x: Int, z: Int, radius: Int): IntArray = layout.findFooting(x, z, radius)

    fun crawlspace(x: Int, z: Int, alongX: Boolean?): IntArray? = layout.crawlspace(x, z, alongX)

    fun nearestExit(x: Int, z: Int, radius: Int): IntArray? = layout.nearestExit(x, z, radius)

    fun pitHoleIsExit(x: Int, z: Int): Boolean = layout.pitHoleIsExit(x, z)

    fun pitHoleAt(x: Int, z: Int): IntArray? = layout.pitHoleAt(x, z)

    fun corridorDrop(x: Int, z: Int, radius: Int): IntArray? = layout.corridorDrop(x, z, radius)

    private fun blockAt(layout: MazeLayout, x: Int, y: Int, z: Int, column: Int): BlockState {

        if (y == BARRIER_Y) return Palette.abyssFloor
        val pitRoom = column and MazeLayout.PIT_ROOM != 0
        if (y < FLOOR_Y) {
            if (!pitRoom) {
                return if (y == SUBFLOOR_Y) Palette.subFloor else Palette.air
            }
            if (y < SHAFT_BOTTOM_Y) return Palette.air

            val depth = FLOOR_Y - y
            if (column and MazeLayout.PIT == 0) {
                return Palette.shaftFade[minOf(depth, ModBlocks.SHAFT_FADE_VARIANTS) - 1]
            }
            return if (depth == ModBlocks.SHAFT_FADE_VARIANTS) Palette.pitVoid else Palette.air
        }
        val top = MazeLayout.topOf(column)
        if (y > top) return Palette.subFloor

        val solid = column and MazeLayout.SOLID != 0
        if (y == FLOOR_Y) {
            if (!solid) {
                if (column and MazeLayout.PIT != 0) return Palette.air
                val soak = layout.carpetAnomaly(x, z)
                if (soak >= 0) return Palette.carpetAnomaly[soak]
            }
            val clean = pitRoom || dirtThinned(layout, column, x, FLOOR_Y, z)
            return if (clean) Palette.dampCarpetClean else Palette.dampCarpet
        }

        val ceiling = MazeLayout.ceilingOf(column)
        val ceilingClean = pitRoom || dirtThinned(layout, column, x, ceiling, z)
        val tile = if (ceilingClean) Palette.ceilingTileClean else Palette.ceilingTile
        if (!solid) {
            if (y < ceiling) return Palette.air
            if (y == ceiling) {
                if (column and MazeLayout.LAMP != 0) {
                    val dark = column and MazeLayout.DARK_ZONE != 0
                    return when {
                        layout.lampFlickers(x, z, dark) -> Palette.lampFlickering
                        layout.lampDims(x, z, dark) -> Palette.lampDim
                        else -> Palette.lamp
                    }
                }
                val leak = layout.ceilingAnomaly(x, z, ceiling)
                if (leak >= 0) return Palette.ceilingAnomaly[leak]
                if (column and MazeLayout.DEAD_LAMP != 0) return Palette.lampDead
                return tile
            }
        }

        if (y == top) return tile
        val anomaly = layout.wallAnomaly(x, y, z, top)
        if (anomaly >= 0) return Palette.wallAnomaly[anomaly]
        val clean = dirtThinned(layout, column, x, y, z)
        return when (y) {
            top - 1 -> if (clean) Palette.wallpaperTopClean else Palette.wallpaperTop
            FLOOR_Y + 1 -> if (clean) Palette.wallpaperBottomClean else Palette.wallpaperBottom
            else -> if (clean) Palette.wallpaperClean else Palette.wallpaper
        }
    }

    private fun dirtThinned(layout: MazeLayout, column: Int, x: Int, y: Int, z: Int): Boolean =
        layout.dirtThinned(x, y, z, column and MazeLayout.DARK_ZONE != 0)

    private object Palette {
        val air: BlockState = Blocks.AIR.defaultBlockState()
        val abyssFloor: BlockState = ModBlocks.ABYSS_FLOOR.get().defaultBlockState()
        val subFloor: BlockState = ModBlocks.SUB_FLOOR.get().defaultBlockState()
        val dampCarpet: BlockState = ModBlocks.dampCarpet(clean = false)
        val dampCarpetClean: BlockState = ModBlocks.dampCarpet(clean = true)
        val ceilingTile: BlockState = ModBlocks.ceilingTile(clean = false)
        val ceilingTileClean: BlockState = ModBlocks.ceilingTile(clean = true)
        val lamp: BlockState = ModBlocks.lamp(ModBlocks.LampCondition.ON)
        val lampFlickering: BlockState = ModBlocks.lamp(ModBlocks.LampCondition.FLICKERING)
        val lampDim: BlockState = ModBlocks.lamp(ModBlocks.LampCondition.DIM)
        val lampDead: BlockState = ModBlocks.lamp(ModBlocks.LampCondition.DEAD)
        val wallpaper: BlockState = ModBlocks.wallpaper(clean = false)
        val wallpaperClean: BlockState = ModBlocks.wallpaper(clean = true)
        val wallpaperTop: BlockState = ModBlocks.wallpaperTop(clean = false)
        val wallpaperTopClean: BlockState = ModBlocks.wallpaperTop(clean = true)
        val wallpaperBottom: BlockState = ModBlocks.wallpaperBottom(clean = false)
        val wallpaperBottomClean: BlockState = ModBlocks.wallpaperBottom(clean = true)
        val wallAnomaly: Array<BlockState> = Array(ModBlocks.WALL_VARIANTS) { ModBlocks.wallAnomaly(it) }
        val carpetAnomaly: Array<BlockState> = Array(ModBlocks.CARPET_VARIANTS) { ModBlocks.carpetAnomaly(it) }
        val ceilingAnomaly: Array<BlockState> = Array(ModBlocks.CEILING_VARIANTS) { ModBlocks.ceilingAnomaly(it) }
        val shaftFade: Array<BlockState> = Array(ModBlocks.SHAFT_FADE_VARIANTS) { ModBlocks.shaftFade(it + 1) }
        val pitVoid: BlockState = ModBlocks.PIT_VOID.get().defaultBlockState()
    }

    companion object {
        const val SUBFLOOR_Y = 0
        const val FLOOR_Y = 1
        private const val SHAFT_DEPTH = 37
        const val SHAFT_BOTTOM_Y = FLOOR_Y - SHAFT_DEPTH
        const val BARRIER_Y = SHAFT_BOTTOM_Y - 6

        private const val MIN_Y = -48
        private const val HEIGHT = 64
        private const val TOP_Y = MIN_Y + HEIGHT - 1

        val CODEC: MapCodec<MazeChunkGenerator> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Biome.CODEC.fieldOf("biome").forGetter(MazeChunkGenerator::biome)
            ).apply(instance, ::MazeChunkGenerator)
        }
    }
}
