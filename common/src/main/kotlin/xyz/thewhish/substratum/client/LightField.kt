package xyz.thewhish.substratum.client

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.DataLayer
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.phys.Vec3
import qouteall.imm_ptl.core.ClientWorldLoader
import qouteall.imm_ptl.core.chunk_loading.ImmPtlClientChunkMap
import xyz.thewhish.substratum.level.SubstratumLevels
import xyz.thewhish.substratum.registry.ModBlocks
import xyz.thewhish.substratum.worldgen.MazeChunkGenerator
import kotlin.math.floor
import kotlin.math.roundToInt

object LightField {

    private const val SIDE = 16
    private const val LOW_SECTION = -1
    private const val HIGH_SECTION = 1
    private const val SECTIONS = HIGH_SECTION - LOW_SECTION + 1
    private const val MIN_Y = LOW_SECTION shl 4
    private const val MAX_Y = (HIGH_SECTION shl 4) + 15
    private const val PROBE_Y = MazeChunkGenerator.FLOOR_Y + 1
    private const val SHAFT_FADE = 0.6f
    private const val VERIFY_INTERVAL = 10
    private const val BUDGET_NANOS = 4_000_000L

    private val FADE = FloatArray(PROBE_Y - MIN_Y + 1) { Math.pow(SHAFT_FADE.toDouble(), it.toDouble()).toFloat() }

    private class Geometry(val chunk: LevelChunk, val sources: LongArray, val spans: IntArray)

    private var level: ClientLevel? = null
    private val arrivals = ArrayList<LevelChunk>()
    private val departures = ArrayList<LevelChunk>()
    private val geometry = HashMap<Long, Geometry>()
    private val direct = HashMap<Long, FloatArray>()
    private val field = HashMap<Long, FloatArray>()
    private val written = HashMap<Long, DataLayer?>()
    private val due = HashSet<Long>()
    private val sources = HashMap<Long, Float>()
    private val boosts = HashMap<Long, Float>()
    private var ticks = 0

    fun init() {
        ImmPtlClientChunkMap.clientChunkLoadSignal.connect { if (it.level.dimension() == SubstratumLevels.LEVEL_0) arrivals.add(it) }
        ImmPtlClientChunkMap.clientChunkUnloadSignal.connect { if (it.level.dimension() == SubstratumLevels.LEVEL_0) departures.add(it) }
    }

    fun tick(minecraft: Minecraft) {
        val current = levelZero(minecraft)
        if (current !== level) {
            geometry.clear()
            direct.clear()
            field.clear()
            written.clear()
            due.clear()
            sources.clear()
            boosts.clear()
            ticks = 0
            level = current
        }
        if (current == null) {
            arrivals.clear()
            departures.clear()
            return
        }
        ticks++
        receive(current)
        findStale(current)
        if (ticks % VERIFY_INTERVAL == 0) verify(current)
        val player = minecraft.player?.takeIf { it.level() === current }
        drain(current, player?.chunkPosition(), minOf(minecraft.options.effectiveRenderDistance, SubstratumLevels.SIGHT_CHUNKS))
    }

    private fun levelZero(minecraft: Minecraft): ClientLevel? {
        minecraft.level?.takeIf { it.dimension() == SubstratumLevels.LEVEL_0 }?.let { return it }
        if (!ClientWorldLoader.getIsInitialized()) return null
        return ClientWorldLoader.getClientWorlds().firstOrNull { it.dimension() == SubstratumLevels.LEVEL_0 }
    }

    fun forEachSource(action: (Long, Float) -> Unit) = sources.forEach(action)

    fun strengthAt(pos: Long): Float = sources[pos] ?: 0f

    fun sourcesNear(level: ClientLevel, center: BlockPos, range: Int, test: (BlockState) -> Boolean): List<BlockPos>? {
        if (level !== this.level) return null
        val limit = range.toLong() * range
        val found = ArrayList<BlockPos>()
        for (key in sources.keys) {
            val dx = (BlockPos.getX(key) - center.x).toLong()
            val dy = (BlockPos.getY(key) - center.y).toLong()
            val dz = (BlockPos.getZ(key) - center.z).toLong()
            if (dx * dx + dy * dy + dz * dz > limit) continue
            val pos = BlockPos.of(key)
            if (test(level.getBlockState(pos))) found.add(pos)
        }
        return found
    }

    fun lampChanged(level: ClientLevel, pos: BlockPos) {
        if (level !== this.level || level.dimension() != SubstratumLevels.LEVEL_0) return
        observe(level, pos.asLong(), strength(pos.asLong(), level.getBlockState(pos)))
    }

    fun boost(level: ClientLevel, pos: BlockPos, factor: Float) {
        if (level !== this.level) return
        if (factor == 1f) boosts.remove(pos.asLong()) else boosts[pos.asLong()] = factor
        lampChanged(level, pos)
    }

    @JvmStatic
    fun entityLight(level: Level, entity: Entity, pos: BlockPos): Int {
        if (!level.isClientSide || level.dimension() != SubstratumLevels.LEVEL_0) return -1
        val eye = entity.eyePosition
        return smoothLight(level, if (BlockPos.containing(eye) == pos) eye else Vec3.atCenterOf(pos)).roundToInt()
    }

    fun smoothLight(level: Level, point: Vec3): Double {
        val bx = floor(point.x - 0.5).toInt()
        val by = floor(point.y - 0.5).toInt()
        val bz = floor(point.z - 0.5).toInt()
        val fx = point.x - 0.5 - bx
        val fy = point.y - 0.5 - by
        val fz = point.z - 0.5 - bz
        val cursor = BlockPos.MutableBlockPos()
        var sum = 0.0
        var weight = 0.0
        for (dx in 0..1) for (dy in 0..1) for (dz in 0..1) {
            cursor.set(bx + dx, by + dy, bz + dz)
            if (level.getBlockState(cursor).canOcclude()) continue
            val w = (if (dx == 0) 1 - fx else fx) * (if (dy == 0) 1 - fy else fy) * (if (dz == 0) 1 - fz else fz)
            sum += w * level.getBrightness(LightLayer.BLOCK, cursor)
            weight += w
        }
        return if (weight < 1e-4) level.getBrightness(LightLayer.BLOCK, BlockPos.containing(point)).toDouble() else sum / weight
    }

    private fun receive(level: ClientLevel) {
        for (chunk in departures) if (chunk.level === level && !loaded(level, chunk.pos.x, chunk.pos.z)) forget(chunk.pos)
        departures.clear()
        for (chunk in arrivals) {
            if (chunk.level === level && level.chunkSource.getChunk(chunk.pos.x, chunk.pos.z, ChunkStatus.FULL, false) === chunk) {
                geometry.remove(chunk.pos.toLong())
                invalidate(chunk.pos.x, chunk.pos.z)
            }
        }
        arrivals.clear()
    }

    private fun forget(pos: ChunkPos) {
        val key = pos.toLong()
        val known = geometry.remove(key)?.sources
        if (known != null) known.forEach(sources::remove)
        else sources.keys.removeIf { BlockPos.getX(it) shr 4 == pos.x && BlockPos.getZ(it) shr 4 == pos.z }
        direct.remove(key)
        field.remove(key)
        due.remove(key)
        for (sy in LOW_SECTION..HIGH_SECTION) written.remove(SectionPos.asLong(pos.x, sy, pos.z))
    }

    private fun findStale(level: ClientLevel) {
        if (field.isEmpty()) return
        val engine = level.lightEngine.getLayerListener(LightLayer.BLOCK)
        val stale = field.keys.filter { key ->
            val cx = ChunkPos.getX(key)
            val cz = ChunkPos.getZ(key)
            (0 until SECTIONS).any { written[SectionPos.asLong(cx, LOW_SECTION + it, cz)] !== engine.getDataLayerData(SectionPos.of(cx, LOW_SECTION + it, cz)) }
        }
        for (key in stale) invalidate(ChunkPos.getX(key), ChunkPos.getZ(key))
    }

    private fun invalidate(cx: Int, cz: Int) {
        val key = ChunkPos.asLong(cx, cz)
        field.remove(key)
        geometry.remove(key)
        for (dx in -1..1) for (dz in -1..1) direct.remove(ChunkPos.asLong(cx + dx, cz + dz))
        for (dx in -2..2) for (dz in -2..2) due.add(ChunkPos.asLong(cx + dx, cz + dz))
    }

    private fun drain(level: ClientLevel, centre: ChunkPos?, radius: Int) {
        if (due.isEmpty()) return
        val start = System.nanoTime()
        val ready = ArrayList<Long>()
        for (key in due) {
            if (centre != null && distance(key, centre) > radius) continue
            if (ready(level, ChunkPos.getX(key), ChunkPos.getZ(key))) ready.add(key)
        }
        if (centre != null) ready.sortBy { distance(it, centre) }
        for (key in ready) {
            due.remove(key)
            finish(level, ChunkPos.getX(key), ChunkPos.getZ(key))
            if (System.nanoTime() - start > BUDGET_NANOS) return
        }
    }

    private fun distance(key: Long, centre: ChunkPos): Int =
        maxOf(Math.abs(ChunkPos.getX(key) - centre.x), Math.abs(ChunkPos.getZ(key) - centre.z))

    private fun ready(level: ClientLevel, cx: Int, cz: Int): Boolean =
        loaded(level, cx, cz) && level.lightEngine.lightOnInSection(SectionPos.of(cx, 0, cz))

    private fun verify(level: ClientLevel) {
        val changed = sources.entries.filter { (pos, strength) ->
            loaded(level, BlockPos.getX(pos) shr 4, BlockPos.getZ(pos) shr 4) && strength(pos, level.getBlockState(BlockPos.of(pos))) != strength
        }.map { it.key }
        for (pos in changed) observe(level, pos, strength(pos, level.getBlockState(BlockPos.of(pos))))
    }

    private fun loaded(level: ClientLevel, cx: Int, cz: Int): Boolean =
        level.chunkSource.getChunk(cx, cz, ChunkStatus.FULL, false) != null

    private fun strength(pos: Long, state: BlockState): Float =
        if (state.`is`(ModBlocks.lampBlock)) {
            state.getValue(ModBlocks.LAMP_CONDITION).brightness * (boosts[pos] ?: 1f)
        } else {
            state.lightEmission / 15f
        }

    private fun observe(level: ClientLevel, pos: Long, strength: Float) {
        val before = sources.put(pos, strength)
        if (before != null && before != strength) applySource(level, pos, strength - before)
    }

    private fun finish(level: ClientLevel, cx: Int, cz: Int) {
        val size = 3 * SIDE
        val wx = (cx - 1) shl 4
        val wz = (cz - 1) shl 4
        val open = openMap(level, wx, wz, size)
        val energy = FloatArray(size * size)
        for (dx in 0..2) for (dz in 0..2) {
            val nx = cx + dx - 1
            val nz = cz + dz - 1
            if (!loaded(level, nx, nz)) continue
            val cells = direct.getOrPut(ChunkPos.asLong(nx, nz)) { computeDirect(level, nx, nz) }
            for (c in 0 until SIDE * SIDE) energy[(dx * SIDE + c / SIDE) * size + dz * SIDE + c % SIDE] = cells[c]
        }
        val spread = Irradiance.spread(open, size, size, energy)
        val cells = FloatArray(SIDE * SIDE) { spread[(it / SIDE + SIDE) * size + it % SIDE + SIDE] }
        field[ChunkPos.asLong(cx, cz)] = cells
        write(level, cx, cz, cells, null, 0, 0, 0)
    }

    private fun computeDirect(level: ClientLevel, cx: Int, cz: Int): FloatArray {
        val reach = Irradiance.REACH
        val size = SIDE + 2 * reach
        val wx = (cx shl 4) - reach
        val wz = (cz shl 4) - reach
        val open = openMap(level, wx, wz, size)
        val grid = FloatArray(size * size)
        for (gx in (wx shr 4)..((wx + size - 1) shr 4)) for (gz in (wz shr 4)..((wz + size - 1) shr 4)) {
            for (pos in geometry(level, gx, gz)?.sources ?: continue) {
                val strength = strength(pos, level.getBlockState(BlockPos.of(pos)))
                observe(level, pos, strength)
                if (strength <= 0f) continue
                val i = BlockPos.getX(pos) - wx
                val j = BlockPos.getZ(pos) - wz
                Irradiance.direct(open, size, i, j, BlockPos.getY(pos), grid, reach, reach, reach + SIDE - 1, reach + SIDE - 1, strength)
            }
        }
        return FloatArray(SIDE * SIDE) { grid[(it / SIDE + reach) * size + it % SIDE + reach] }
    }

    private fun applySource(level: ClientLevel, pos: Long, delta: Float) {
        val r = Irradiance.LAMP_WINDOW
        val size = 2 * r + 1
        val wx = BlockPos.getX(pos) - r
        val wz = BlockPos.getZ(pos) - r
        val open = openMap(level, wx, wz, size)
        val light = FloatArray(size * size)
        Irradiance.direct(open, size, r, r, BlockPos.getY(pos), light, 0, 0, size - 1, size - 1, delta)
        addInto(direct, light, wx, wz, size)
        val spread = Irradiance.spread(open, size, size, light)
        for (key in addInto(field, spread, wx, wz, size)) {
            write(level, ChunkPos.getX(key), ChunkPos.getZ(key), field.getValue(key), spread, wx, wz, size)
        }
    }

    private fun addInto(target: HashMap<Long, FloatArray>, light: FloatArray, wx: Int, wz: Int, size: Int): List<Long> {
        val touched = ArrayList<Long>()
        for (cx in (wx shr 4)..((wx + size - 1) shr 4)) for (cz in (wz shr 4)..((wz + size - 1) shr 4)) {
            val key = ChunkPos.asLong(cx, cz)
            val cells = target[key] ?: continue
            touched.add(key)
            for (c in 0 until SIDE * SIDE) {
                val a = (cx shl 4) + c / SIDE - wx
                val b = (cz shl 4) + c % SIDE - wz
                if (a in 0 until size && b in 0 until size) cells[c] += light[a * size + b]
            }
        }
        return touched
    }

    private fun openMap(level: ClientLevel, wx: Int, wz: Int, size: Int): BooleanArray {
        val open = BooleanArray(size * size)
        for (cx in (wx shr 4)..((wx + size - 1) shr 4)) for (cz in (wz shr 4)..((wz + size - 1) shr 4)) {
            val spans = geometry(level, cx, cz)?.spans ?: continue
            for (c in 0 until SIDE * SIDE) {
                val a = (cx shl 4) + c / SIDE - wx
                val b = (cz shl 4) + c % SIDE - wz
                if (a in 0 until size && b in 0 until size) open[a * size + b] = spans[c] >= 0
            }
        }
        return open
    }

    private fun write(level: ClientLevel, cx: Int, cz: Int, energy: FloatArray, changed: FloatArray?, wx: Int, wz: Int, size: Int) {
        val spans = geometry(level, cx, cz)?.spans ?: return
        val engine = level.lightEngine.getLayerListener(LightLayer.BLOCK)
        val keys = LongArray(SECTIONS) { SectionPos.asLong(cx, LOW_SECTION + it, cz) }
        val layers = Array(SECTIONS) { engine.getDataLayerData(SectionPos.of(cx, LOW_SECTION + it, cz)) }
        if (changed != null && layers.indices.any { layers[it] !== written[keys[it]] }) return
        val dirty = HashSet<Long>()
        for (c in 0 until SIDE * SIDE) {
            val x = (cx shl 4) + c / SIDE
            val z = (cz shl 4) + c % SIDE
            val span = spans[c]
            if (changed != null && (span < 0 || x - wx !in 0 until size || z - wz !in 0 until size || changed[(x - wx) * size + z - wz] == 0f)) continue
            val from = if (changed == null) MIN_Y else bottom(span)
            val to = if (changed == null) MAX_Y else top(span)
            for (y in from..to) {
                val layer = layers[(y shr 4) - LOW_SECTION] ?: continue
                val value = if (span >= 0 && y in bottom(span)..top(span)) {
                    Irradiance.level(energy[c] * FADE[(PROBE_Y - y).coerceAtLeast(0)], Irradiance.dither(x, y, z))
                } else {
                    0
                }
                if (layer.get(x and 15, y and 15, z and 15) == value) continue
                layer.set(x and 15, y and 15, z and 15, value)
                markAround(dirty, x, y, z)
            }
        }
        if (changed == null) for (it in 0 until SECTIONS) written[keys[it]] = layers[it]
        if (dirty.isEmpty()) return
        LightVolume.markDirty(cx, cz)
        val renderer = ClientWorldLoader.getWorldRenderer(level.dimension())
        for (key in dirty) renderer.setSectionDirty(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key))
    }

    private fun markAround(dirty: HashSet<Long>, x: Int, y: Int, z: Int) {
        for (dx in low(x)..high(x)) for (dy in low(y)..high(y)) for (dz in low(z)..high(z)) {
            dirty.add(SectionPos.asLong((x shr 4) + dx, (y shr 4) + dy, (z shr 4) + dz))
        }
    }

    private fun low(coordinate: Int): Int = if (coordinate and 15 == 0) -1 else 0

    private fun high(coordinate: Int): Int = if (coordinate and 15 == 15) 1 else 0

    private fun geometry(level: ClientLevel, cx: Int, cz: Int): Geometry? {
        val chunk = level.chunkSource.getChunk(cx, cz, ChunkStatus.FULL, false) ?: return null
        val key = ChunkPos.asLong(cx, cz)
        geometry[key]?.takeIf { it.chunk === chunk }?.let { return it }
        val spans = IntArray(SIDE * SIDE) { span(chunk, it / SIDE, it % SIDE) }
        return Geometry(chunk, sourcesIn(chunk, spans), spans).also { geometry[key] = it }
    }

    private fun span(chunk: LevelChunk, lx: Int, lz: Int): Int {
        if (opaque(chunk, lx, PROBE_Y, lz)) return -1
        var top = PROBE_Y
        while (top < MAX_Y && !opaque(chunk, lx, top + 1, lz)) top++
        var bottom = PROBE_Y
        while (bottom > MIN_Y && !opaque(chunk, lx, bottom - 1, lz)) bottom--
        return ((top - MIN_Y) shl 8) or (bottom - MIN_Y)
    }

    private fun opaque(chunk: LevelChunk, lx: Int, y: Int, lz: Int): Boolean {
        val index = chunk.getSectionIndexFromSectionY(y shr 4)
        return index in chunk.sections.indices && chunk.sections[index].getBlockState(lx, y and 15, lz).canOcclude()
    }

    private fun top(span: Int): Int = (span ushr 8) + MIN_Y

    private fun bottom(span: Int): Int = (span and 0xFF) + MIN_Y

    private fun sourcesIn(chunk: LevelChunk, spans: IntArray): LongArray {
        val lamp = ModBlocks.lampBlock
        val found = ArrayList<Long>()
        for (sy in LOW_SECTION..HIGH_SECTION) {
            val section = chunk.sections.getOrNull(chunk.getSectionIndexFromSectionY(sy)) ?: continue
            if (section.hasOnlyAir() || !section.maybeHas { it.lightEmission > 0 || it.`is`(lamp) }) continue
            for (lx in 0 until SIDE) for (ly in 0 until SIDE) for (lz in 0 until SIDE) {
                val state = section.getBlockState(lx, ly, lz)
                if (state.lightEmission <= 0 && !state.`is`(lamp)) continue
                val span = spans[lx * SIDE + lz]
                val y = (sy shl 4) + ly
                if (span < 0 || y !in bottom(span) - 1..top(span) + 1) continue
                found.add(BlockPos.asLong(chunk.pos.minBlockX + lx, y, chunk.pos.minBlockZ + lz))
            }
        }
        return found.toLongArray()
    }
}
