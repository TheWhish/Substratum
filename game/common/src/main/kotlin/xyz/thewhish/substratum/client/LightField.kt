package xyz.thewhish.substratum.client

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.phys.Vec3
import qouteall.imm_ptl.core.ClientWorldLoader
import qouteall.imm_ptl.core.chunk_loading.ImmPtlClientChunkMap
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.level.SubstratumLevels
import xyz.thewhish.substratum.registry.ModBlocks
import xyz.thewhish.substratum.worldgen.MazeChunkGenerator
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

object LightField {

    const val PROBE_Y = MazeChunkGenerator.FLOOR_Y + 1
    const val SHAFT_FADE = 0.6f

    private const val SIDE = 16
    private const val CELLS = SIDE * SIDE
    private const val LOW_SECTION = -1
    private const val HIGH_SECTION = 1
    private const val MIN_Y = LOW_SECTION shl 4
    private const val MAX_Y = (HIGH_SECTION shl 4) + 15
    private const val VERIFY_INTERVAL = 10
    private const val BUDGET_NANOS = 2_000_000L
    private const val TICK_NANOS = 50_000_000L
    private const val IN_FLIGHT = 4
    private const val NEIGHBOURS = 9

    private class Geometry(val chunk: LevelChunk, val sources: LongArray, val spans: IntArray)

    private class Lamps(val open: BooleanArray, val cells: IntArray, val strengths: FloatArray)

    private class Job(
        val cx: Int,
        val cz: Int,
        val generation: Int,
        val keys: LongArray,
        val stamps: IntArray,
        val open: BooleanArray,
        val known: Array<FloatArray?>,
        val missing: Array<Lamps?>
    )

    private class Result(val job: Job, val cells: FloatArray?, val computed: Array<FloatArray?>, val error: Throwable?)

    private var level: ClientLevel? = null
    private val arrivals = ArrayList<LevelChunk>()
    private val departures = ArrayList<LevelChunk>()
    private val geometry = HashMap<Long, Geometry>()
    private val direct = HashMap<Long, FloatArray>()
    private val field = HashMap<Long, FloatArray>()
    private val stamps = HashMap<Long, Int>()
    private val due = HashSet<Long>()
    private val running = HashSet<Long>()
    private val sources = HashMap<Long, Float>()
    private val boosts = HashMap<Long, Float>()
    private val finished = ConcurrentLinkedQueue<Result>()
    private val worker: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "Substratum light").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    }
    private var generation = 0
    private var ticks = 0
    private var tickedAt = 0L

    fun init() {
        ImmPtlClientChunkMap.clientChunkLoadSignal.connect { if (it.level.dimension() == SubstratumLevels.LEVEL_0) arrivals.add(it) }
        ImmPtlClientChunkMap.clientChunkUnloadSignal.connect { if (it.level.dimension() == SubstratumLevels.LEVEL_0) departures.add(it) }
    }

    fun tick(minecraft: Minecraft) {
        tickedAt = System.nanoTime()
        update(minecraft, false)
    }

    fun stalled(): Boolean = System.nanoTime() - tickedAt > TICK_NANOS

    fun refresh(minecraft: Minecraft) = update(minecraft, true)

    fun fieldAt(cx: Int, cz: Int): FloatArray? = field[ChunkPos.asLong(cx, cz)]

    fun spansAt(level: ClientLevel, cx: Int, cz: Int): IntArray? =
        if (level === this.level) geometry(level, cx, cz)?.spans else null

    fun open(span: Int, y: Int): Boolean = span >= 0 && y in bottom(span)..top(span)

    private fun update(minecraft: Minecraft, settle: Boolean) {
        val current = levelZero(minecraft)
        if (current !== level) {
            geometry.clear()
            direct.clear()
            field.clear()
            stamps.clear()
            due.clear()
            running.clear()
            finished.clear()
            sources.clear()
            boosts.clear()
            generation++
            ticks = 0
            level = current
        }
        if (current == null) {
            arrivals.clear()
            departures.clear()
            return
        }
        ticks++
        collect(current)
        receive(current)
        if (ticks % VERIFY_INTERVAL == 0) verify(current)
        val player = minecraft.player?.takeIf { it.level() === current }
        drain(current, player?.chunkPosition(), minOf(minecraft.options.effectiveRenderDistance, SubstratumLevels.SIGHT_CHUNKS), settle)
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
        val point = if (BlockPos.containing(eye) == pos) eye else Vec3.atCenterOf(pos)
        val beams = (Irradiance.BRIGHT - Irradiance.DARK) * Flashlights.light(level, entity, point)
        return (smoothLight(level, point) + beams).roundToInt().coerceAtMost(Irradiance.BRIGHT)
    }

    fun smoothLight(level: Level, point: Vec3): Double = fieldLight(level, point) ?: vanillaLight(level, point)

    private fun fieldLight(level: Level, point: Vec3): Double? {
        if (level !== this.level) return null
        val x = point.x - 0.5
        val z = point.z - 0.5
        val bx = floor(x).toInt()
        val bz = floor(z).toInt()
        val fx = x - bx
        val fz = z - bz
        var sum = 0.0
        var weight = 0.0
        for (dx in 0..1) for (dz in 0..1) {
            val cx = (bx + dx) shr 4
            val cz = (bz + dz) shr 4
            val key = ChunkPos.asLong(cx, cz)
            val cells = field[key] ?: continue
            val spans = geometry[key]?.spans ?: continue
            val cell = ((bx + dx) and 15) * SIDE + ((bz + dz) and 15)
            if (spans[cell] < 0) continue
            val w = (if (dx == 0) 1 - fx else fx) * (if (dz == 0) 1 - fz else fz)
            sum += w * cells[cell]
            weight += w
        }
        if (weight < 1e-5) return null
        val energy = sum / weight * SHAFT_FADE.toDouble().pow((PROBE_Y - point.y).coerceAtLeast(0.0))
        return Irradiance.DARK + Irradiance.lift(energy.toFloat()).toDouble()
    }

    private fun vanillaLight(level: Level, point: Vec3): Double {
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
        stamps.remove(key)
        due.remove(key)
    }

    private fun invalidate(cx: Int, cz: Int) {
        field.remove(ChunkPos.asLong(cx, cz))
        geometry.remove(ChunkPos.asLong(cx, cz))
        for (dx in -1..1) for (dz in -1..1) direct.remove(ChunkPos.asLong(cx + dx, cz + dz))
        for (dx in -2..2) for (dz in -2..2) {
            val key = ChunkPos.asLong(cx + dx, cz + dz)
            due.add(key)
            touch(key)
        }
        LightVolume.markDirty(cx, cz)
    }

    private fun touch(key: Long) {
        stamps[key] = (stamps[key] ?: 0) + 1
    }

    private fun drain(level: ClientLevel, centre: ChunkPos?, radius: Int, settle: Boolean) {
        if (due.isEmpty()) return
        val start = System.nanoTime()
        val ready = ArrayList<Long>()
        for (key in due) {
            if (key in running || centre != null && distance(key, centre) > radius) continue
            if (loaded(level, ChunkPos.getX(key), ChunkPos.getZ(key))) ready.add(key)
        }
        if (centre != null) ready.sortBy { distance(it, centre) }
        for (key in ready) {
            if (!settle && running.size >= IN_FLIGHT) return
            due.remove(key)
            running.add(key)
            val job = prepare(level, ChunkPos.getX(key), ChunkPos.getZ(key))
            if (settle) {
                finish(level, compute(job))
            } else {
                worker.execute { finished.add(compute(job)) }
                if (System.nanoTime() - start > BUDGET_NANOS) return
            }
        }
    }

    private fun collect(level: ClientLevel) {
        while (true) finish(level, finished.poll() ?: return)
    }

    private fun finish(level: ClientLevel, result: Result) {
        val job = result.job
        if (job.generation != generation) return
        val key = ChunkPos.asLong(job.cx, job.cz)
        running.remove(key)
        val cells = result.cells
        if (cells == null) {
            Substratum.LOGGER.error("Level 0 light for chunk ({}, {}) failed, leaving it unlit", job.cx, job.cz, result.error)
            return
        }
        if (!loaded(level, job.cx, job.cz)) return
        if (job.keys.indices.any { (stamps[job.keys[it]] ?: 0) != job.stamps[it] }) {
            due.add(key)
            return
        }
        for (n in 0 until NEIGHBOURS) result.computed[n]?.let { direct.putIfAbsent(job.keys[n], it) }
        field[key] = cells
        LightVolume.markDirty(job.cx, job.cz)
    }

    private fun prepare(level: ClientLevel, cx: Int, cz: Int): Job {
        val size = 3 * SIDE
        val keys = LongArray(NEIGHBOURS) { ChunkPos.asLong(cx + it / 3 - 1, cz + it % 3 - 1) }
        val missing = arrayOfNulls<Lamps>(NEIGHBOURS)
        for (n in 0 until NEIGHBOURS) {
            val nx = cx + n / 3 - 1
            val nz = cz + n % 3 - 1
            if (keys[n] !in direct && loaded(level, nx, nz)) missing[n] = lamps(level, nx, nz)
        }
        val known = Array(NEIGHBOURS) { direct[keys[it]]?.copyOf() }
        val open = openMap(level, (cx - 1) shl 4, (cz - 1) shl 4, size)
        return Job(cx, cz, generation, keys, IntArray(NEIGHBOURS) { stamps[keys[it]] ?: 0 }, open, known, missing)
    }

    private fun lamps(level: ClientLevel, cx: Int, cz: Int): Lamps {
        val reach = Irradiance.REACH
        val size = SIDE + 2 * reach
        val wx = (cx shl 4) - reach
        val wz = (cz shl 4) - reach
        val open = openMap(level, wx, wz, size)
        val cells = ArrayList<Int>()
        val strengths = ArrayList<Float>()
        for (gx in (wx shr 4)..((wx + size - 1) shr 4)) for (gz in (wz shr 4)..((wz + size - 1) shr 4)) {
            for (pos in geometry(level, gx, gz)?.sources ?: continue) {
                val strength = strength(pos, level.getBlockState(BlockPos.of(pos)))
                observe(level, pos, strength)
                if (strength <= 0f) continue
                cells += BlockPos.getX(pos) - wx
                cells += BlockPos.getZ(pos) - wz
                cells += BlockPos.getY(pos)
                strengths += strength
            }
        }
        return Lamps(open, cells.toIntArray(), strengths.toFloatArray())
    }

    private fun compute(job: Job): Result {
        val computed = arrayOfNulls<FloatArray>(NEIGHBOURS)
        return try {
            val size = 3 * SIDE
            val energy = FloatArray(size * size)
            for (n in 0 until NEIGHBOURS) {
                val cells = job.known[n] ?: job.missing[n]?.let { lit(it).also { light -> computed[n] = light } } ?: continue
                val dx = n / 3
                val dz = n % 3
                for (c in 0 until CELLS) energy[(dx * SIDE + c / SIDE) * size + dz * SIDE + c % SIDE] = cells[c]
            }
            val spread = Irradiance.spread(job.open, size, size, energy)
            Result(job, FloatArray(CELLS) { spread[(it / SIDE + SIDE) * size + it % SIDE + SIDE] }, computed, null)
        } catch (e: Throwable) {
            Result(job, null, computed, e)
        }
    }

    private fun lit(lamps: Lamps): FloatArray {
        val reach = Irradiance.REACH
        val size = SIDE + 2 * reach
        val grid = FloatArray(size * size)
        for (k in lamps.strengths.indices) {
            val i = lamps.cells[3 * k]
            val j = lamps.cells[3 * k + 1]
            Irradiance.direct(lamps.open, size, i, j, lamps.cells[3 * k + 2], grid, reach, reach, reach + SIDE - 1, reach + SIDE - 1, lamps.strengths[k])
        }
        return FloatArray(CELLS) { grid[(it / SIDE + reach) * size + it % SIDE + reach] }
    }

    private fun distance(key: Long, centre: ChunkPos): Int =
        maxOf(Math.abs(ChunkPos.getX(key) - centre.x), Math.abs(ChunkPos.getZ(key) - centre.z))

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

    private fun applySource(level: ClientLevel, pos: Long, delta: Float) {
        val r = Irradiance.LAMP_WINDOW
        val size = 2 * r + 1
        val wx = BlockPos.getX(pos) - r
        val wz = BlockPos.getZ(pos) - r
        for (cx in (wx shr 4)..((wx + size - 1) shr 4)) for (cz in (wz shr 4)..((wz + size - 1) shr 4)) touch(ChunkPos.asLong(cx, cz))
        val open = openMap(level, wx, wz, size)
        val light = FloatArray(size * size)
        Irradiance.direct(open, size, r, r, BlockPos.getY(pos), light, 0, 0, size - 1, size - 1, delta)
        addInto(direct, light, wx, wz, size)
        for (key in addInto(field, Irradiance.spread(open, size, size, light), wx, wz, size)) {
            LightVolume.markDirty(ChunkPos.getX(key), ChunkPos.getZ(key))
        }
    }

    private fun addInto(target: HashMap<Long, FloatArray>, light: FloatArray, wx: Int, wz: Int, size: Int): List<Long> {
        val touched = ArrayList<Long>()
        for (cx in (wx shr 4)..((wx + size - 1) shr 4)) for (cz in (wz shr 4)..((wz + size - 1) shr 4)) {
            val key = ChunkPos.asLong(cx, cz)
            val cells = target[key] ?: continue
            touched.add(key)
            for (c in 0 until CELLS) {
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
            for (c in 0 until CELLS) {
                val a = (cx shl 4) + c / SIDE - wx
                val b = (cz shl 4) + c % SIDE - wz
                if (a in 0 until size && b in 0 until size) open[a * size + b] = spans[c] >= 0
            }
        }
        return open
    }

    private fun geometry(level: ClientLevel, cx: Int, cz: Int): Geometry? {
        val chunk = level.chunkSource.getChunk(cx, cz, ChunkStatus.FULL, false) ?: return null
        val key = ChunkPos.asLong(cx, cz)
        geometry[key]?.takeIf { it.chunk === chunk }?.let { return it }
        val spans = IntArray(CELLS) { span(chunk, it / SIDE, it % SIDE) }
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
