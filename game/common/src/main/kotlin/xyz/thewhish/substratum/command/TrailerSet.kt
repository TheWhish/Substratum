package xyz.thewhish.substratum.command

import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.WallTorchBlock
import net.minecraft.world.level.block.state.BlockState
import xyz.thewhish.substratum.client.LampFlicker
import xyz.thewhish.substratum.level.SubstratumLevels
import xyz.thewhish.substratum.registry.ModBlocks
import xyz.thewhish.substratum.registry.ModBlocks.LampCondition
import xyz.thewhish.substratum.worldgen.MazeChunkGenerator
import xyz.thewhish.substratum.worldgen.MazeLayout

object TrailerSet {
    private const val SEARCH = 128
    private const val RUN = 9
    private const val TURNS = 2
    private const val LENGTH = 104
    private const val BEHIND = 3
    private const val OFFSET = 24
    private const val HALF = 2
    private const val SHELL = HALF + 1
    private const val SPACING = 2 * SHELL + 1
    private const val CEILING = MazeLayout.CEILING_LOW
    private const val FILL_TOP = MazeLayout.CEILING_HIGH + 2
    private const val LAMP_FIRST = 4
    private const val FLAGS = Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE
    private const val FLOOR_Y = MazeChunkGenerator.FLOOR_Y

    private val ON = LampCondition.ON
    private val FL = LampCondition.FLICKERING
    private val DI = LampCondition.DIM
    private val DE = LampCondition.DEAD

    private const val RESTLESS = 2
    private const val ATTEMPTS = 60

    private class Floor(
        val name: String,
        val grime: Float,
        val lamps: List<LampCondition>?,
        val flickers: Int = 0,
        val wallStains: Int = 0,
        val carpetStains: Int = 0,
        val ceilingStains: Int = 0
    )

    private val STONE = Floor("1 stone", 0f, null)
    private val FLOORS = listOf(
        Floor("2 clean", 0f, List(13) { ON }),
        Floor("3 worn", 0.35f, listOf(ON, ON, ON, ON, ON, DI, ON, ON, ON, ON, ON, ON, ON), 2, 4, 2, 1),
        Floor("4 failing", 0.7f, listOf(ON, DE, ON, DI, ON, DE, DI, ON, DE, ON, DI, ON, DE), 4, 9, 5, 3),
        Floor("5 dying", 1f, listOf(DE, DE, DI, DE, DE, DE, DI, DE, DE, DE, DE, DE, DE), 3, 15, 8, 5)
    )

    internal class Corridor(val alongX: Boolean, val sign: Int, val start: Int, val centre: Int, val base: Int = 0) {
        fun pos(along: Int, across: Int, y: Int): BlockPos =
            if (alongX) BlockPos(start + sign * along, base + y, centre + across) else BlockPos(centre + across, base + y, start + sign * along)

        fun shifted(by: Int) = Corridor(alongX, sign, start, centre + by, base)

        fun facing(across: Int): Direction = when {
            alongX && across > 0 -> Direction.NORTH
            alongX -> Direction.SOUTH
            across > 0 -> Direction.WEST
            else -> Direction.EAST
        }

        val yaw: Int get() = if (alongX) -90 * sign else if (sign > 0) 0 else 180
    }

    fun build(player: ServerPlayer): List<Component>? {
        val level = player.serverLevel()
        if (level.dimension() != SubstratumLevels.LEVEL_0) return buildStone(level, player)
        val layout = (level.chunkSource.generator as? MazeChunkGenerator)?.layout ?: return null
        val unseen = (level.server.playerList.viewDistance + 2) * 16 + LENGTH + OFFSET + FLOORS.size * SPACING
        val real = find(layout, player.blockX, player.blockZ, unseen / MazeLayout.CELL) ?: return null
        val random = RandomSource.create(real.start.toLong() * 31 + real.centre)
        val side = if (random.nextBoolean()) 1 else -1
        val lines = ArrayList<Component>()
        FLOORS.forEachIndexed { i, floor ->
            val corridor = real.shifted(side * (OFFSET + i * SPACING))
            carve(level, corridor, floor, random)
            lines.add(line(floor.name, corridor))
        }
        for (k in 0 until RUN) level.setBlock(real.pos(LAMP_FIRST + k * MazeLayout.CELL, 0, CEILING), ModBlocks.lamp(ON), FLAGS)
        lines.add(line("6 real", real))
        lines.add(camera(real))
        return lines
    }

    private fun buildStone(level: ServerLevel, player: ServerPlayer): List<Component> {
        val facing = player.direction
        val alongX = facing.axis == Direction.Axis.X
        val start = if (alongX) player.blockX else player.blockZ
        val centre = if (alongX) player.blockZ else player.blockX
        val corridor = Corridor(alongX, facing.axisDirection.step, start, centre, player.blockY - FLOOR_Y - 1)
        carve(level, corridor, STONE, RandomSource.create(start.toLong() * 31 + centre))
        return listOf(line(STONE.name, corridor), camera(corridor))
    }

    internal fun find(layout: MazeLayout, x: Int, z: Int, nearest: Int): Corridor? {
        val cellX = Math.floorDiv(x, MazeLayout.CELL)
        val cellZ = Math.floorDiv(z, MazeLayout.CELL)
        for (r in nearest..SEARCH) for (dx in -r..r) for (dz in -r..r) {
            if (maxOf(Math.abs(dx), Math.abs(dz)) != r) continue
            for (dir in Direction.Plane.HORIZONTAL) {
                straight(layout, cellX + dx, cellZ + dz, dir)?.let { return it }
            }
        }
        return null
    }

    private fun straight(layout: MazeLayout, cellX: Int, cellZ: Int, dir: Direction): Corridor? {
        val alongX = dir.axis == Direction.Axis.X
        val sign = dir.axisDirection.step
        val cellAlong = if (alongX) cellX else cellZ
        val cellAcross = if (alongX) cellZ else cellX
        val centre = cellAcross * MazeLayout.CELL + MazeLayout.CENTRE
        fun column(along: Int, across: Int = centre) = if (alongX) layout.columnAt(along, across) else layout.columnAt(across, along)
        fun band(k: Int) = maxOf(cellAlong + sign * k, cellAlong + sign * (k + 1)) * MazeLayout.CELL + 1
        var turns = 0
        for (k in 0 until RUN) {
            val along = (cellAlong + sign * k) * MazeLayout.CELL + MazeLayout.CENTRE
            val flags = column(along)
            if (flags and (MazeLayout.SOLID or MazeLayout.PIT_ROOM or MazeLayout.DARK_ZONE or MazeLayout.DEAD_LAMP) != 0) return null
            if (flags and MazeLayout.LAMP == 0 || MazeLayout.ceilingOf(flags) != CEILING) return null
            val lampX = if (alongX) along else centre
            val lampZ = if (alongX) centre else along
            if (layout.lampFlickers(lampX, lampZ, false) || layout.lampDims(lampX, lampZ, false)) return null
            if (column(along, cellAcross * MazeLayout.CELL + 1) and MazeLayout.SOLID == 0) turns++
            if (column(along, (cellAcross + 1) * MazeLayout.CELL + 1) and MazeLayout.SOLID == 0) turns++
        }
        if (turns < TURNS) return null
        for (k in -1 until RUN - 1) if (column(band(k)) and MazeLayout.SOLID != 0) return null
        return Corridor(alongX, sign, band(-1), centre)
    }

    private fun carve(level: ServerLevel, corridor: Corridor, floor: Floor, random: RandomSource) {
        val stone = floor.lamps == null
        for (along in -BEHIND..LENGTH) for (across in -SHELL..SHELL) {
            val wall = along == -BEHIND || along == LENGTH || Math.abs(across) > HALF
            for (y in MazeChunkGenerator.SUBFLOOR_Y..FILL_TOP) {
                val state = when {
                    stone -> if (wall || y <= FLOOR_Y || y >= CEILING) stone(random) else Blocks.AIR.defaultBlockState()
                    y == MazeChunkGenerator.SUBFLOOR_Y || y > CEILING -> ModBlocks.SUB_FLOOR.get().defaultBlockState()
                    y == CEILING -> ModBlocks.ceilingTile(clean(floor, random))
                    y == FLOOR_Y -> ModBlocks.dampCarpet(clean(floor, random))
                    !wall -> Blocks.AIR.defaultBlockState()
                    y == FLOOR_Y + 1 -> ModBlocks.wallpaperBottom(clean(floor, random))
                    y == CEILING - 1 -> ModBlocks.wallpaperTop(clean(floor, random))
                    else -> ModBlocks.wallpaper(clean(floor, random))
                }
                level.setBlock(corridor.pos(along, across, y), state, FLAGS)
            }
        }
        val lamps = (LAMP_FIRST until LENGTH step MazeLayout.CELL).toList()
        if (floor.lamps == null) {
            lamps.forEachIndexed { index, along ->
                val across = if (index % 2 == 0) HALF else -HALF
                val torch = Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, corridor.facing(across))
                level.setBlock(corridor.pos(along, across, FLOOR_Y + 3), torch, FLAGS)
            }
            return
        }
        val restless = lamps.indices.filter { level.server.isDedicatedServer || LampFlicker.temperament(corridor.pos(lamps[it], 0, CEILING).asLong()) == RESTLESS }
        val flickering = restless.take(floor.flickers).toSet()
        lamps.forEachIndexed { index, along ->
            val condition = if (index in flickering) FL else floor.lamps[index % floor.lamps.size]
            level.setBlock(corridor.pos(along, 0, CEILING), ModBlocks.lamp(condition), FLAGS)
        }
        stainWalls(level, corridor, floor.wallStains, random)
        stainFlat(level, corridor, FLOOR_Y, floor.carpetStains, MazeLayout.CARPET_ANOMALIES, random, ModBlocks::carpetAnomaly)
        stainFlat(level, corridor, CEILING, floor.ceilingStains, MazeLayout.CEILING_ANOMALIES, random, ModBlocks::ceilingAnomaly)
    }

    private fun stainWalls(level: ServerLevel, corridor: Corridor, count: Int, random: RandomSource) {
        val taken = HashSet<Pair<Int, Int>>()
        var placed = 0
        repeat(ATTEMPTS) {
            if (placed == count) return
            val kind = MazeLayout.WALL_ANOMALIES[random.nextInt(MazeLayout.WALL_ANOMALIES.size)]
            val side = if (random.nextBoolean()) 1 else -1
            val origin = 1 + random.nextInt(LENGTH - kind.cols - 1)
            if ((origin - 1..origin + kind.cols).any { side to it in taken }) return@repeat
            for (c in 0 until kind.cols) taken.add(side to origin + c)
            val mirrored = if (corridor.alongX) side > 0 else side < 0
            for (c in 0 until kind.cols) {
                val raw = if (corridor.sign > 0) c else kind.cols - 1 - c
                val col = if (mirrored) kind.cols - 1 - raw else raw
                for (y in FLOOR_Y + 1 until CEILING) {
                    val row = kind.rowAt(y, CEILING)
                    if (row >= 0) level.setBlock(corridor.pos(origin + c, side * SHELL, y), ModBlocks.wallAnomaly(kind.base + row * kind.cols + col), FLAGS)
                }
            }
            placed++
        }
    }

    private fun stainFlat(
        level: ServerLevel,
        corridor: Corridor,
        y: Int,
        count: Int,
        kinds: Array<MazeLayout.Footprint>,
        random: RandomSource,
        block: (Int) -> BlockState
    ) {
        val taken = HashSet<BlockPos>()
        var placed = 0
        repeat(ATTEMPTS) {
            if (placed == count) return
            val kind = kinds[random.nextInt(kinds.size)]
            val spanAlong = if (corridor.alongX) kind.cols else kind.rows
            val spanAcross = if (corridor.alongX) kind.rows else kind.cols
            val along = random.nextInt(LENGTH - spanAlong)
            val across = -HALF + random.nextInt(2 * HALF + 2 - spanAcross)
            val cells = (along until along + spanAlong).flatMap { a -> (across until across + spanAcross).map { b -> a to b } }
            if (y == CEILING && cells.any { (a, b) -> b == 0 && Math.floorMod(a - LAMP_FIRST, MazeLayout.CELL) == 0 }) return@repeat
            val spots = cells.map { (a, b) -> corridor.pos(a, b, y) }
            if (spots.any { spot -> Direction.Plane.HORIZONTAL.any { spot.relative(it) in taken } || spot in taken }) return@repeat
            taken.addAll(spots)
            val minX = spots.minOf { it.x }
            val minZ = spots.minOf { it.z }
            for (spot in spots) level.setBlock(spot, block(kind.base + (spot.z - minZ) * kind.cols + spot.x - minX), FLAGS)
            placed++
        }
    }

    private fun clean(floor: Floor, random: RandomSource): Boolean = random.nextFloat() >= floor.grime

    private fun stone(random: RandomSource): BlockState = when (random.nextInt(10)) {
        0 -> Blocks.ANDESITE
        1 -> Blocks.COBBLESTONE
        2 -> Blocks.TUFF
        else -> Blocks.STONE
    }.defaultBlockState()

    private fun camera(corridor: Corridor): Component =
        Component.literal("Camera: Y ${corridor.base + CEILING - 0.4} down to Y ${corridor.base + FLOOR_Y + 1.15}")

    private fun line(name: String, corridor: Corridor): MutableComponent {
        val feet = corridor.pos(0, 0, FLOOR_Y + 1)
        val command = "/tp @s ${feet.x + 0.5} ${feet.y} ${feet.z + 0.5} ${corridor.yaw} 0"
        return Component.literal("[$name] camera X ${feet.x + 0.5} Z ${feet.z + 0.5} yaw ${corridor.yaw}").withStyle {
            it.withColor(ChatFormatting.GREEN).withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
        }
    }
}
