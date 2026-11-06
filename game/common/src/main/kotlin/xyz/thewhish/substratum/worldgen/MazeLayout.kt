package xyz.thewhish.substratum.worldgen

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class MazeLayout(val seed: Long) {

    companion object {

        const val CELL = 8
        const val WALL = 3

        const val CENTRE = 5
        const val SECTOR_CELLS = 16

        const val SOLID = 1
        const val LAMP = 2
        const val PIT = 4

        const val PIT_ROOM = 8

        const val DARK_ZONE = 1 shl 13
        const val DEAD_LAMP = 1 shl 12
        const val CEILING_LOW = 7
        const val CEILING_HIGH = 11

        const val CEILING_TIGHT = 5

        const val CRAWL_MIN = 1
        const val CRAWL_MAX = 1

        private const val CRAWL_PROBE = 8

        private val STEP_X = intArrayOf(0, 1, 0, -1)
        private val STEP_Z = intArrayOf(-1, 0, 1, 0)

        fun ceilingOf(flags: Int): Int = (flags ushr 4) and 0xF

        fun topOf(flags: Int): Int = (flags ushr 8) and 0xF

        private const val SALT_SECTOR = 0x5EC7
        private const val SALT_ANOMALY = 0x3A17
        private const val SALT_SOAK = 0x7B29
        private const val SALT_LEAK = 0x2D63
        private const val SALT_PIT_EXIT_HOLE = 0x1C8E

        private const val SALT_PIT_HOLE_ROLE = 0x6B03

        private const val PIT_HOLE_EXIT_ODDS = 0x5555

        private const val ROOM_EMPTY = 0
        private const val ROOM_PILLARS = 1
        private const val ROOM_COLONNADE = 2
        private const val ROOM_ALCOVES = 3
        private const val ROOM_WALL_ROWS = 4
        private const val ROOM_CLOSETS = 5
        private const val ROOM_INNER = 6

        private const val ROOM_PIT_FIELD = 7

        private const val PIT_PITCH = 4
        const val PIT_HOLE = 3

        private const val DROP_HALF = PIT_HOLE / 2
        private const val CORRIDOR_HALF = (CELL - WALL) / 2

        private const val PIT_HALL_CHANCE = 0.4f
        private const val GRAND_ROOM_CHANCE = 0.24f

        private const val SALT_DARK = 0x4E16
        private const val DARK_DISTRICT = 32
        private const val DARK_ODDS = 0xB333
        private const val DARK_MARGIN = 6
        private const val DARK_RADIUS = 11
        private const val DARK_RADIUS_SPAN = 5
        private const val DARK_FADE = 3f
        private const val DARK_LAMPS = 0.07f
        private const val DARK_LAMPS_SPAN = 0.08f
        private const val DARK_BELOW = 0.5f
        private const val GRIME_RAMP = 2f
        private const val GRIME_ANOMALIES = 1.4f

        private const val DOOR_LOW = 32

        private const val NICHE_NEAR = 1
        private const val NICHE_FAR = 2

        private const val WALL_ANOMALY_RATE = 313
        private const val CARPET_ANOMALY_RATE = 252
        private const val CEILING_ANOMALY_RATE = 148

        private const val SALT_DIRT_THIN = 0x4E11
        private const val SALT_FLICKER = 0x4E12
        private const val FLICKER_RATE = 0x35E5
        private const val DARK_FLICKER_RATE = 0x5555
        private const val LIT_LAMPS = 0.95f
        private const val SALT_DEAD_LAMP = 0x4E13
        private const val DEAD_LAMP_RATE = 0x0CCD
        private const val SALT_DIM = 0x4E14
        private const val DIM_RATE = 0x1111
        private const val DARK_DIM_RATE = 0x8000

        private const val SALT_ALMOND = 0x4E15
        private const val ALMOND_RATE = 0x6000
        private const val DARK_ALMOND_RATE = 0xD000
        private const val HIDEOUT_DEPTH = 3

        private const val ANOMALY_PITCH = 4

        const val WALL_BASE_Y = 2

        const val ANCHOR_TOP = 0
        const val ANCHOR_BOTTOM = 1
        const val ANCHOR_MID = 2

        val WALL_ANOMALIES = arrayOf(
            Footprint(2, 2, 0, ANCHOR_TOP),
            Footprint(1, 3, 4, ANCHOR_TOP),
            Footprint(3, 2, 7, ANCHOR_TOP),
            Footprint(2, 2, 13, ANCHOR_BOTTOM),
            Footprint(3, 1, 17, ANCHOR_BOTTOM),
            Footprint(2, 2, 20, ANCHOR_MID),
            Footprint(1, 2, 24, ANCHOR_MID),
            Footprint(3, 1, 26, ANCHOR_MID)
        )

        val CARPET_ANOMALIES = arrayOf(
            Footprint(2, 2, 0, ANCHOR_MID),
            Footprint(3, 3, 4, ANCHOR_MID),
            Footprint(3, 2, 13, ANCHOR_MID),
            Footprint(2, 2, 19, ANCHOR_MID),
            Footprint(2, 3, 23, ANCHOR_MID)
        )

        val CEILING_ANOMALIES = arrayOf(
            Footprint(2, 2, 0, ANCHOR_MID),
            Footprint(2, 2, 4, ANCHOR_MID),
            Footprint(3, 3, 8, ANCHOR_MID)
        )

        private val WALL_ROLL = intArrayOf(0, 1, 1, 2, 3, 4, 4, 5, 6, 6, 7, 7)
        private val CARPET_ROLL = intArrayOf(0, 0, 1, 2, 2, 3, 4)
        private val CEILING_ROLL = intArrayOf(0, 0, 1, 2)

        fun footprintOf(kinds: Array<Footprint>, variant: Int): Footprint? =
            kinds.firstOrNull { variant >= it.base && variant < it.base + it.size }

        private const val MAX_CACHED_SECTORS = 2048

        private val GAMMA = 0x9E3779B97F4A7C15uL.toLong()
        private val MIX_A = 0xBF58476D1CE4E5B9uL.toLong()
        private val MIX_B = 0x94D049BB133111EBuL.toLong()
        private val KEY_A = 0xD1342543DE82EF95uL.toLong()
        private val KEY_B = 0xA24BAED4963EE407uL.toLong()
        private val KEY_C = 0x2545F4914F6CDD1DuL.toLong()

        private fun mix64(value: Long): Long {
            var z = value
            z = (z xor (z ushr 30)) * MIX_A
            z = (z xor (z ushr 27)) * MIX_B
            return z xor (z ushr 31)
        }

        private fun seedFor(worldSeed: Long, a: Int, b: Int, salt: Int): Long =
            mix64(mix64(mix64(worldSeed + salt * GAMMA) + a * KEY_A) + b * KEY_B)
    }

    class Footprint(val cols: Int, val rows: Int, val base: Int, val anchor: Int) {
        val size: Int get() = cols * rows

        fun rowAt(y: Int, top: Int): Int {
            val row = when (anchor) {
                ANCHOR_TOP -> top - 1 - y
                ANCHOR_BOTTOM -> rows + WALL_BASE_Y - 1 - y
                else -> rows + WALL_BASE_Y - y
            }
            return if (row in 0 until rows) row else -1
        }

        val minTop: Int get() = if (anchor == ANCHOR_MID) rows + WALL_BASE_Y + 1 else rows + WALL_BASE_Y
    }

    private class Rng(private var state: Long) {
        fun nextLong(): Long {
            state += GAMMA
            return mix64(state)
        }

        fun nextInt(bound: Int): Int = ((nextLong() ushr 33) % bound).toInt()

        fun nextFloat(): Float = (nextLong() ushr 40) * (1f / (1 shl 24))

        fun range(min: Float, max: Float): Float = min + nextFloat() * (max - min)
    }

    private class Recent(val key: Long, val sector: Sector)

    private val cache = ConcurrentHashMap<Long, Sector>()

    @Volatile
    private var recent: Recent? = null

    fun columnAt(x: Int, z: Int): Int {
        val cellX = Math.floorDiv(x, CELL)
        val cellZ = Math.floorDiv(z, CELL)
        val localX = Math.floorMod(x, CELL)
        val localZ = Math.floorMod(z, CELL)
        val onWestBand = localX < WALL
        val onNorthBand = localZ < WALL

        var flags = 0
        val sector = sectorOf(cellX, cellZ)
        val i = localIndex(cellX, cellZ)
        if (sector.dark[i]) flags = flags or DARK_ZONE
        var ceiling: Int
        var top: Int
        val pitField: Boolean

        if (onWestBand && onNorthBand) {
            val solid = vWall(cellX, cellZ) || vWall(cellX, cellZ - 1) ||
                hWall(cellX, cellZ) || hWall(cellX - 1, cellZ) || keepsPost(cellX, cellZ)
            if (solid) flags = flags or SOLID
            val a = cellCeiling(cellX - 1, cellZ - 1)
            val b = cellCeiling(cellX, cellZ - 1)
            val c = cellCeiling(cellX - 1, cellZ)
            val d = cellCeiling(cellX, cellZ)
            ceiling = minOf(a, b, c, d)
            top = maxOf(a, b, c, d)
            pitField = cellPit(cellX - 1, cellZ - 1) && cellPit(cellX, cellZ - 1) &&
                cellPit(cellX - 1, cellZ) && cellPit(cellX, cellZ)
        } else if (onWestBand) {
            var door = 0
            if (vWall(cellX, cellZ)) {
                flags = flags or SOLID
                if (inNiche(localZ) && localX == nicheFace(vNiche(cellX, cellZ))) flags = flags and SOLID.inv()
            } else {
                door = vDoor(cellX, cellZ)
                if (door != 0 && !inSlot(localZ, door)) flags = flags or SOLID
            }
            val a = cellCeiling(cellX - 1, cellZ)
            val b = cellCeiling(cellX, cellZ)
            ceiling = minOf(a, b)
            top = maxOf(a, b)
            if (door and DOOR_LOW != 0) ceiling = minOf(ceiling, CEILING_TIGHT)
            pitField = cellPit(cellX - 1, cellZ) && cellPit(cellX, cellZ)
        } else if (onNorthBand) {
            var door = 0
            if (hWall(cellX, cellZ)) {
                flags = flags or SOLID
                if (inNiche(localX) && localZ == nicheFace(hNiche(cellX, cellZ))) flags = flags and SOLID.inv()
            } else {
                door = hDoor(cellX, cellZ)
                if (door != 0 && !inSlot(localX, door)) flags = flags or SOLID
            }
            val a = cellCeiling(cellX, cellZ - 1)
            val b = cellCeiling(cellX, cellZ)
            ceiling = minOf(a, b)
            top = maxOf(a, b)
            if (door and DOOR_LOW != 0) ceiling = minOf(ceiling, CEILING_TIGHT)
            pitField = cellPit(cellX, cellZ - 1) && cellPit(cellX, cellZ)
        } else {
            ceiling = sector.ceiling[i].toInt()
            top = ceiling
            if (localX == CENTRE && localZ == CENTRE) {
                flags = flags or when {
                    sector.dark[i] -> if (sector.lamp[i]) LAMP else 0
                    ((seedFor(seed, x, z, SALT_DEAD_LAMP) ushr 17) and 0xFFFF) < DEAD_LAMP_RATE -> DEAD_LAMP
                    else -> LAMP
                }
            }
            pitField = sector.pit[i]
        }

        if (pitField) flags = flags or PIT_ROOM
        if (pitField && flags and SOLID == 0 && inPitLattice(x) && inPitLattice(z)) flags = flags or PIT

        return flags or (ceiling shl 4) or (top shl 8)
    }

    private fun inPitLattice(world: Int): Boolean = Math.floorMod(world, PIT_PITCH) < PIT_HOLE

    private fun inSlot(local: Int, door: Int): Boolean {
        val start = door and 7
        return local >= start && local < start + ((door ushr 3) and 3)
    }

    private fun inNiche(local: Int): Boolean = local in (CENTRE - 1)..(CENTRE + 1)

    private fun nicheFace(niche: Int): Int = when (niche) {
        NICHE_NEAR -> 0
        NICHE_FAR -> WALL - 1
        else -> -1
    }

    fun findFooting(x: Int, z: Int, radius: Int, lit: Boolean = false): IntArray {
        for (r in 0..radius) {
            for (dx in -r..r) {
                for (dz in -r..r) {
                    if (maxOf(abs(dx), abs(dz)) != r) continue
                    val flags = columnAt(x + dx, z + dz)
                    if (flags and (SOLID or PIT) != 0 || lit && flags and DARK_ZONE != 0) continue
                    return intArrayOf(x + dx, z + dz)
                }
            }
        }
        return intArrayOf(x, z)
    }

    fun corridorDrop(x: Int, z: Int, radius: Int): IntArray? {
        val originX = Math.floorDiv(x, CELL)
        val originZ = Math.floorDiv(z, CELL)
        for (r in 0..radius) {
            for (dx in -r..r) {
                for (dz in -r..r) {
                    if (maxOf(abs(dx), abs(dz)) != r) continue
                    val centreX = (originX + dx) * CELL + CENTRE
                    val centreZ = (originZ + dz) * CELL + CENTRE
                    if (isCorridorDrop(centreX, centreZ)) return intArrayOf(centreX, centreZ)
                }
            }
        }
        return null
    }

    fun isCorridorDrop(x: Int, z: Int): Boolean {
        for (dx in -DROP_HALF..DROP_HALF) {
            for (dz in -DROP_HALF..DROP_HALF) {
                val flags = columnAt(x + dx, z + dz)
                if (flags and (SOLID or PIT_ROOM) != 0) return false
                if (ceilingOf(flags) != CEILING_LOW || topOf(flags) != CEILING_LOW) return false
            }
        }
        return corridorAcross(x, z, 1, 0) || corridorAcross(x, z, 0, 1)
    }

    private fun corridorAcross(x: Int, z: Int, acrossX: Int, acrossZ: Int): Boolean {
        for (d in -DROP_HALF..DROP_HALF) {
            val alongX = x + acrossZ * d
            val alongZ = z + acrossX * d
            for (side in intArrayOf(-1, 1)) {
                val openX = alongX + acrossX * side * CORRIDOR_HALF
                val openZ = alongZ + acrossZ * side * CORRIDOR_HALF
                if (isSolid(openX, openZ)) return false
                if (!isSolid(openX + acrossX * side, openZ + acrossZ * side)) return false
            }
        }
        return true
    }

    fun crawlspace(x: Int, z: Int, alongX: Boolean?): IntArray? {
        for (r in 0..CRAWL_PROBE) {
            for (dx in -r..r) {
                for (dz in -r..r) {
                    if (maxOf(abs(dx), abs(dz)) != r) continue
                    if (!standable(x + dx, z + dz)) continue
                    for (d in 0 until 4) {
                        val ax = STEP_X[d]
                        val az = STEP_Z[d]
                        if (alongX != null && (ax != 0) != alongX) continue
                        for (length in CRAWL_MAX downTo CRAWL_MIN) {
                            if (!encloses(x + dx, z + dz, ax, az, length)) continue
                            return intArrayOf(x + dx, z + dz, ax, az, length)
                        }
                    }
                }
            }
        }
        return null
    }

    private fun encloses(mouthX: Int, mouthZ: Int, ax: Int, az: Int, length: Int): Boolean {
        if (!solid(mouthX + ax * (length + 1), mouthZ + az * (length + 1))) return false
        val pz = -ax
        for (i in 1..length) {
            val cx = mouthX + ax * i
            val cz = mouthZ + az * i
            if (!solid(cx, cz)) return false
            if (!solid(cx + az, cz + pz)) return false
            if (!solid(cx - az, cz - pz)) return false
        }
        return true
    }

    private fun solid(x: Int, z: Int): Boolean = columnAt(x, z) and SOLID != 0

    private fun standable(x: Int, z: Int): Boolean {
        val flags = columnAt(x, z)
        return flags and SOLID == 0 && flags and PIT == 0
    }

    fun connectivity(cellX: Int, cellZ: Int, radius: Int): IntArray {
        val n = radius * 2 + 1
        val component = IntArray(n * n)
        val stack = ArrayDeque<Int>()
        var components = 0
        var largest = 0
        var sealed = 0

        for (start in 0 until n * n) {
            if (component[start] != 0) continue
            components++
            var size = 0
            var touchesEdge = false
            component[start] = components
            stack.addLast(start)
            while (stack.isNotEmpty()) {
                val current = stack.removeLast()
                size++
                val i = current / n
                val j = current % n
                if (i == 0 || j == 0 || i == n - 1 || j == n - 1) touchesEdge = true
                val gx = cellX - radius + i
                val gz = cellZ - radius + j
                if (i > 0 && !vWall(gx, gz) && component[current - n] == 0) {
                    component[current - n] = components
                    stack.addLast(current - n)
                }
                if (i < n - 1 && !vWall(gx + 1, gz) && component[current + n] == 0) {
                    component[current + n] = components
                    stack.addLast(current + n)
                }
                if (j > 0 && !hWall(gx, gz) && component[current - 1] == 0) {
                    component[current - 1] = components
                    stack.addLast(current - 1)
                }
                if (j < n - 1 && !hWall(gx, gz + 1) && component[current + 1] == 0) {
                    component[current + 1] = components
                    stack.addLast(current + 1)
                }
            }
            if (size > largest) largest = size
            if (!touchesEdge) sealed++
        }
        return intArrayOf(n * n, largest, sealed)
    }

    private fun vWall(cellX: Int, cellZ: Int): Boolean = sectorOf(cellX, cellZ).v[localIndex(cellX, cellZ)]

    private fun hWall(cellX: Int, cellZ: Int): Boolean = sectorOf(cellX, cellZ).h[localIndex(cellX, cellZ)]

    private fun keepsPost(cellX: Int, cellZ: Int): Boolean =
        sectorOf(cellX, cellZ).post[localIndex(cellX, cellZ)]

    private fun vDoor(cellX: Int, cellZ: Int): Int =
        sectorOf(cellX, cellZ).vDoor[localIndex(cellX, cellZ)].toInt()

    private fun hDoor(cellX: Int, cellZ: Int): Int =
        sectorOf(cellX, cellZ).hDoor[localIndex(cellX, cellZ)].toInt()

    private fun vNiche(cellX: Int, cellZ: Int): Int =
        sectorOf(cellX, cellZ).vNiche[localIndex(cellX, cellZ)].toInt()

    private fun hNiche(cellX: Int, cellZ: Int): Int =
        sectorOf(cellX, cellZ).hNiche[localIndex(cellX, cellZ)].toInt()

    private fun cellPit(cellX: Int, cellZ: Int): Boolean =
        sectorOf(cellX, cellZ).pit[localIndex(cellX, cellZ)]

    private fun pitHallBounds(cellX: Int, cellZ: Int): IntArray? {
        val sector = sectorOf(cellX, cellZ)
        if (sector.pitMinX < 0) return null
        val sectorX = Math.floorDiv(cellX, SECTOR_CELLS)
        val sectorZ = Math.floorDiv(cellZ, SECTOR_CELLS)
        val worldMinX = (sectorX * SECTOR_CELLS + sector.pitMinX) * CELL
        val worldMinZ = (sectorZ * SECTOR_CELLS + sector.pitMinZ) * CELL
        val worldMaxX = (sectorX * SECTOR_CELLS + sector.pitMaxX) * CELL + CELL - 1
        val worldMaxZ = (sectorZ * SECTOR_CELLS + sector.pitMaxZ) * CELL + CELL - 1
        return intArrayOf(worldMinX, worldMinZ, worldMaxX, worldMaxZ)
    }

    private fun holeAnchorsAlong(lo: Int, hi: Int): IntArray {
        val list = ArrayList<Int>()
        var anchor = Math.floorDiv(lo, PIT_PITCH) * PIT_PITCH
        while (anchor <= hi) {
            if (anchor >= lo && anchor + PIT_HOLE - 1 <= hi) list.add(anchor)
            anchor += PIT_PITCH
        }
        return list.toIntArray()
    }

    fun pitExitHole(x: Int, z: Int): IntArray? {
        val bounds = pitHallBounds(Math.floorDiv(x, CELL), Math.floorDiv(z, CELL)) ?: return null
        val holes = pitHoleAnchors(bounds)
        if (holes.isEmpty()) return null
        val holeHash = seedFor(seed, bounds[0], bounds[1], SALT_PIT_EXIT_HOLE)
        return holes[((holeHash ushr 33) % holes.size).toInt()]
    }

    fun nearestExit(x: Int, z: Int, radius: Int): IntArray? {
        val sectorX = Math.floorDiv(Math.floorDiv(x, CELL), SECTOR_CELLS)
        val sectorZ = Math.floorDiv(Math.floorDiv(z, CELL), SECTOR_CELLS)
        val span = radius / (CELL * SECTOR_CELLS) + 1
        var best: IntArray? = null
        var bestDistance = radius.toLong() * radius
        for (sx in sectorX - span..sectorX + span) {
            for (sz in sectorZ - span..sectorZ + span) {
                val bounds = pitHallBounds(sx * SECTOR_CELLS, sz * SECTOR_CELLS) ?: continue
                val hole = pitExitHole(bounds[0], bounds[1]) ?: continue
                val dx = (hole[0] + PIT_HOLE / 2 - x).toLong()
                val dz = (hole[1] + PIT_HOLE / 2 - z).toLong()
                val distance = dx * dx + dz * dz
                if (distance > bestDistance) continue
                best = hole
                bestDistance = distance
            }
        }
        return best
    }

    fun pitHoleIsExit(x: Int, z: Int): Boolean {
        val exit = pitExitHole(x, z) ?: return false
        if (x == exit[0] && z == exit[1]) return true
        return ((seedFor(seed, x, z, SALT_PIT_HOLE_ROLE) ushr 17) and 0xFFFF) < PIT_HOLE_EXIT_ODDS
    }

    fun pitHoleAt(x: Int, z: Int): IntArray? {
        val anchorX = Math.floorDiv(x, PIT_PITCH) * PIT_PITCH
        val anchorZ = Math.floorDiv(z, PIT_PITCH) * PIT_PITCH
        if (x - anchorX >= PIT_HOLE || z - anchorZ >= PIT_HOLE || !isOpenHole(anchorX, anchorZ)) return null
        return intArrayOf(anchorX, anchorZ)
    }

    private fun pitHoleAnchors(bounds: IntArray): List<IntArray> {
        val xs = holeAnchorsAlong(bounds[0], bounds[2])
        val zs = holeAnchorsAlong(bounds[1], bounds[3])
        val holes = ArrayList<IntArray>()
        for (x in xs) for (z in zs) if (isOpenHole(x, z)) holes.add(intArrayOf(x, z))
        return holes
    }

    private fun isOpenHole(x: Int, z: Int): Boolean {
        for (i in 0 until PIT_HOLE) {
            for (j in 0 until PIT_HOLE) {
                if (columnAt(x + i, z + j) and PIT == 0) return false
            }
        }
        return true
    }

    fun wallAnomaly(x: Int, y: Int, z: Int, top: Int): Int {
        val anchorX = Math.floorDiv(x, ANOMALY_PITCH) * ANOMALY_PITCH
        val anchorZ = Math.floorDiv(z, ANOMALY_PITCH) * ANOMALY_PITCH
        val alongXHash = seedFor(seed, anchorX * 2, z, SALT_ANOMALY)
        val alongZHash = seedFor(seed, anchorZ * 2 + 1, x, SALT_ANOMALY)
        val xWants = anomalyRolls(alongXHash, WALL_ANOMALY_RATE, anchorX, z)
        val zWants = anomalyRolls(alongZHash, WALL_ANOMALY_RATE, x, anchorZ)
        if (!xWants && !zWants) return -1

        val xFires = xWants && !wallAnchorFires(anchorX - ANOMALY_PITCH, z, true) &&
            !wallAnchorFires(anchorX + ANOMALY_PITCH, z, true)
        val zFires = zWants && !wallAnchorFires(anchorZ - ANOMALY_PITCH, x, false) &&
            !wallAnchorFires(anchorZ + ANOMALY_PITCH, x, false)
        if (!xFires && !zFires) return -1

        val alongX = isSolid(x - 1, z) && isSolid(x + 1, z) && (isOpen(x, z - 1) || isOpen(x, z + 1))
        val alongZ = isSolid(x, z - 1) && isSolid(x, z + 1) && (isOpen(x - 1, z) || isOpen(x + 1, z))
        if (!alongX && !alongZ) return -1
        if (!((alongX && xFires) || (alongZ && zFires))) return -1

        val hash = if (alongX) alongXHash else alongZHash
        val run = if (alongX) x else z
        val perp = if (alongX) z else x
        val anchor = if (alongX) anchorX else anchorZ
        val kind = WALL_ANOMALIES[WALL_ROLL[((hash ushr 33) % WALL_ROLL.size).toInt()]]
        val offset = ((hash ushr 41) % (ANOMALY_PITCH - kind.cols + 1)).toInt()
        val rawCol = run - anchor - offset
        if (rawCol !in 0 until kind.cols) return -1

        val mirrored = if (alongX) isOpen(x, z - 1) && !isOpen(x, z + 1)
        else isOpen(x + 1, z) && !isOpen(x - 1, z)
        val col = if (mirrored) kind.cols - 1 - rawCol else rawCol

        if (!wallFootprintFits(anchor + offset, perp, kind, alongX, top, mirrored)) return -1
        val row = kind.rowAt(y, top)
        return if (row < 0) -1 else kind.base + row * kind.cols + col
    }

    private fun wallAnchorFires(anchor: Int, perp: Int, alongX: Boolean): Boolean {
        val hash = if (alongX) seedFor(seed, anchor * 2, perp, SALT_ANOMALY)
        else seedFor(seed, anchor * 2 + 1, perp, SALT_ANOMALY)
        return if (alongX) anomalyRolls(hash, WALL_ANOMALY_RATE, anchor, perp) else anomalyRolls(hash, WALL_ANOMALY_RATE, perp, anchor)
    }

    private fun anomalyRolls(hash: Long, rate: Int, x: Int, z: Int): Boolean {
        val roll = (hash ushr 17) and 0xFFFF
        val most = rate * GRIME_ANOMALIES
        return roll < most && roll < most * grimeAt(x, z)
    }

    private fun wallFootprintFits(
        start: Int,
        perp: Int,
        kind: Footprint,
        alongX: Boolean,
        top: Int,
        mirrored: Boolean
    ): Boolean {
        if (top < kind.minTop) return false
        if (!isSolidOn(alongX, start - 1, perp) || !isSolidOn(alongX, start + kind.cols, perp)) return false
        for (i in 0 until kind.cols) {
            val cx = if (alongX) start + i else perp
            val cz = if (alongX) perp else start + i
            val column = columnAt(cx, cz)
            if (column and SOLID == 0) return false
            if (topOf(column) != top) return false
            val exposedOnChosenSide = if (alongX) {
                if (mirrored) isOpen(cx, cz - 1) else isOpen(cx, cz + 1)
            } else {
                if (mirrored) isOpen(cx + 1, cz) else isOpen(cx - 1, cz)
            }
            if (!exposedOnChosenSide) return false
        }
        return true
    }

    fun carpetAnomaly(x: Int, z: Int): Int =
        flatAnomaly(x, z, SALT_SOAK, CARPET_ANOMALY_RATE, CARPET_ANOMALIES, CARPET_ROLL, -1)

    fun ceilingAnomaly(x: Int, z: Int, ceiling: Int): Int =
        flatAnomaly(x, z, SALT_LEAK, CEILING_ANOMALY_RATE, CEILING_ANOMALIES, CEILING_ROLL, ceiling)

    fun grimeAt(x: Int, z: Int): Float {
        val cellX = Math.floorDiv(x, CELL)
        val cellZ = Math.floorDiv(z, CELL)
        return sectorOf(cellX, cellZ).grime[localIndex(cellX, cellZ)]
    }

    fun dirtThinned(x: Int, y: Int, z: Int, grime: Float): Boolean =
        ((mix64(seedFor(seed, x, y, SALT_DIRT_THIN) + z * KEY_C) ushr 17) and 0xFFFF) >= grime * 0x10000

    fun lampFlickers(x: Int, z: Int, dark: Boolean): Boolean =
        ((seedFor(seed, x, z, SALT_FLICKER) ushr 17) and 0xFFFF) < if (dark) DARK_FLICKER_RATE else FLICKER_RATE

    fun lampDims(x: Int, z: Int, dark: Boolean): Boolean =
        ((seedFor(seed, x, z, SALT_DIM) ushr 17) and 0xFFFF) < if (dark) DARK_DIM_RATE else DIM_RATE

    fun almondWaterAt(x: Int, z: Int): Boolean {
        val localX = Math.floorMod(x, CELL)
        val localZ = Math.floorMod(z, CELL)
        if (localX < WALL || localZ < WALL) return false
        val cellX = Math.floorDiv(x, CELL)
        val cellZ = Math.floorDiv(z, CELL)
        val roll = seedFor(seed, cellX, cellZ, SALT_ALMOND)
        val rate = if (sectorOf(cellX, cellZ).dark[localIndex(cellX, cellZ)]) DARK_ALMOND_RATE else ALMOND_RATE
        if (((roll ushr 17) and 0xFFFF) >= rate) return false
        val mouth = openSide(cellX, cellZ)
        if (mouth < 0) return false
        val pick = roll ushr 33
        if (doorOn(cellX, cellZ, mouth) != 0) {
            val open = CELL - WALL
            return localX == WALL + (pick % open).toInt() && localZ == WALL + (pick / open % open).toInt() &&
                !cellPit(cellX, cellZ)
        }
        val across = WALL + 1 + (pick % 3).toInt()
        val depth = (pick / 3 % (CELL - WALL - 1)).toInt()
        val placed = when (mouth) {
            WEST -> localX == WALL + depth && localZ == across
            EAST -> localX == CELL - 1 - depth && localZ == across
            NORTH -> localZ == WALL + depth && localX == across
            else -> localZ == CELL - 1 - depth && localX == across
        }
        return placed && isHideout(cellX, cellZ, mouth)
    }

    internal fun isHideout(cellX: Int, cellZ: Int): Boolean {
        val mouth = openSide(cellX, cellZ)
        return mouth >= 0 && isHideout(cellX, cellZ, mouth)
    }

    private fun isHideout(cellX: Int, cellZ: Int, mouth: Int): Boolean =
        !cellPit(cellX, cellZ) && (doorOn(cellX, cellZ, mouth) != 0 || branchDepth(cellX, cellZ, mouth) >= HIDEOUT_DEPTH)

    private fun openSide(cellX: Int, cellZ: Int): Int {
        var side = -1
        for (dir in WEST..SOUTH) {
            if (!opens(cellX, cellZ, dir)) continue
            if (side >= 0) return -1
            side = dir
        }
        return side
    }

    private fun branchDepth(cellX: Int, cellZ: Int, mouth: Int): Int {
        var x = cellX
        var z = cellZ
        var dir = mouth
        var depth = 1
        while (depth < HIDEOUT_DEPTH) {
            x += stepX(dir)
            z += stepZ(dir)
            val back = dir xor 1
            var next = -1
            for (side in WEST..SOUTH) {
                if (side == back || !opens(x, z, side)) continue
                if (next >= 0) return depth
                next = side
            }
            if (next < 0) return depth
            dir = next
            depth++
        }
        return depth
    }

    private fun opens(cellX: Int, cellZ: Int, dir: Int): Boolean = when (dir) {
        WEST -> !vWall(cellX, cellZ)
        EAST -> !vWall(cellX + 1, cellZ)
        NORTH -> !hWall(cellX, cellZ)
        else -> !hWall(cellX, cellZ + 1)
    }

    private fun doorOn(cellX: Int, cellZ: Int, dir: Int): Int = when (dir) {
        WEST -> vDoor(cellX, cellZ)
        EAST -> vDoor(cellX + 1, cellZ)
        NORTH -> hDoor(cellX, cellZ)
        else -> hDoor(cellX, cellZ + 1)
    }

    private fun stepX(dir: Int): Int = when (dir) {
        WEST -> -1
        EAST -> 1
        else -> 0
    }

    private fun stepZ(dir: Int): Int = when (dir) {
        NORTH -> -1
        SOUTH -> 1
        else -> 0
    }

    private fun flatAnomaly(
        x: Int,
        z: Int,
        salt: Int,
        rate: Int,
        kinds: Array<Footprint>,
        roll: IntArray,
        ceiling: Int
    ): Int {
        val anchorX = Math.floorDiv(x, ANOMALY_PITCH) * ANOMALY_PITCH
        val anchorZ = Math.floorDiv(z, ANOMALY_PITCH) * ANOMALY_PITCH
        val hash = seedFor(seed, anchorX, anchorZ, salt)
        if (!anomalyRolls(hash, rate, anchorX, anchorZ)) return -1
        for (dx in -1..1) {
            for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                val nx = anchorX + dx * ANOMALY_PITCH
                val nz = anchorZ + dz * ANOMALY_PITCH
                if (anomalyRolls(seedFor(seed, nx, nz, salt), rate, nx, nz)) return -1
            }
        }
        val kind = kinds[roll[((hash ushr 33) % roll.size).toInt()]]
        val spanX = ANOMALY_PITCH - kind.cols + 1
        val slots = spanX * (ANOMALY_PITCH - kind.rows + 1)
        val first = ((hash ushr 41) % slots).toInt()
        for (step in 0 until slots) {
            val slot = (first + step) % slots
            val originX = anchorX + slot % spanX
            val originZ = anchorZ + slot / spanX
            if (!flatFootprintFits(originX, originZ, kind, ceiling)) continue
            val partX = x - originX
            val partZ = z - originZ
            if (partX !in 0 until kind.cols || partZ !in 0 until kind.rows) return -1
            return kind.base + partZ * kind.cols + partX
        }
        return -1
    }

    private fun flatFootprintFits(originX: Int, originZ: Int, kind: Footprint, ceiling: Int): Boolean {
        for (i in 0 until kind.cols) {
            for (j in 0 until kind.rows) {
                val column = columnAt(originX + i, originZ + j)
                if (column and (SOLID or PIT_ROOM) != 0) return false
                if (ceiling >= 0 && (ceilingOf(column) != ceiling || column and (LAMP or DEAD_LAMP) != 0)) return false
            }
        }
        return true
    }

    private fun isSolid(x: Int, z: Int): Boolean = columnAt(x, z) and SOLID != 0

    private fun isOpen(x: Int, z: Int): Boolean = columnAt(x, z) and SOLID == 0

    private fun isSolidOn(alongX: Boolean, run: Int, perp: Int): Boolean =
        if (alongX) isSolid(run, perp) else isSolid(perp, run)

    private fun cellCeiling(cellX: Int, cellZ: Int): Int =
        sectorOf(cellX, cellZ).ceiling[localIndex(cellX, cellZ)].toInt()

    private fun localIndex(cellX: Int, cellZ: Int): Int =
        Math.floorMod(cellX, SECTOR_CELLS) * SECTOR_CELLS + Math.floorMod(cellZ, SECTOR_CELLS)

    private fun sectorOf(cellX: Int, cellZ: Int): Sector {
        val sectorX = Math.floorDiv(cellX, SECTOR_CELLS)
        val sectorZ = Math.floorDiv(cellZ, SECTOR_CELLS)
        val key = (sectorX.toLong() shl 32) or (sectorZ.toLong() and 0xFFFF_FFFFL)
        val last = recent
        if (last != null && last.key == key) return last.sector
        val sector = cache[key] ?: run {
            if (cache.size > MAX_CACHED_SECTORS) cache.clear()
            cache.computeIfAbsent(key) { Sector.build(seed, sectorX, sectorZ) }
        }
        recent = Recent(key, sector)
        return sector
    }

    private class Plan(
        val roomCount: Int,
        val roomMin: Int,
        val roomMax: Int,
        val straightBias: Float,
        val loopChance: Float,
        val lampChance: Float,
        val tallChance: Float,
        val doorChance: Float,
        val lowDoorChance: Float,
        val nicheChance: Float,
    )

    private class Sector(
        val v: BooleanArray,
        val h: BooleanArray,
        val post: BooleanArray,
        val lamp: BooleanArray,
        val pit: BooleanArray,
        val ceiling: ByteArray,
        val pitMinX: Int,
        val pitMinZ: Int,
        val pitMaxX: Int,
        val pitMaxZ: Int,
        val dark: BooleanArray,
        val grime: FloatArray,
        val vDoor: ByteArray,
        val hDoor: ByteArray,
        val vNiche: ByteArray,
        val hNiche: ByteArray,
    ) {
        companion object {
            private const val N = SECTOR_CELLS

            fun build(worldSeed: Long, sectorX: Int, sectorZ: Int): Sector {
                val rng = Rng(seedFor(worldSeed, sectorX, sectorZ, SALT_SECTOR))
                val plan = rollPlan(rng)
                val v = BooleanArray(N * N) { true }
                val h = BooleanArray(N * N) { true }
                val post = BooleanArray(N * N)
                val lamp = BooleanArray(N * N)
                val pit = BooleanArray(N * N)
                val ceiling = ByteArray(N * N) { CEILING_LOW.toByte() }
                val vDoor = ByteArray(N * N)
                val hDoor = ByteArray(N * N)
                val vNiche = ByteArray(N * N)
                val hNiche = ByteArray(N * N)
                val roomOf = IntArray(N * N) { -1 }

                val rooms = placeRooms(rng, plan, roomOf)
                for (room in rooms) {
                    val tall = room.feature == ROOM_PIT_FIELD || rng.nextFloat() < plan.tallChance
                    if (!tall) continue
                    room.forEachCell { cx, cz -> ceiling[cx * N + cz] = CEILING_HIGH.toByte() }
                }
                carveCorridors(rng, plan.straightBias, v, h, roomOf, rooms)
                addLoops(rng, plan.loopChance, v, h)
                for (room in rooms) room.decorate(v, h, post, pit)
                for (room in rooms) {
                    if (room.feature == ROOM_PIT_FIELD) room.openPerimeter(rng, v, h)
                }
                openBorders(rng, v, h)
                pinchDoorways(rng, plan, v, h, vDoor, hDoor)
                cutNiches(rng, plan, v, h, vNiche, hNiche)
                val dark = BooleanArray(N * N)
                val grime = FloatArray(N * N)
                for (i in 0 until N * N) {
                    val cellX = sectorX * N + i / N
                    val cellZ = sectorZ * N + i % N
                    val chance = lampChance(worldSeed, cellX, cellZ, plan.lampChance)
                    dark[i] = chance < DARK_BELOW
                    if (dark[i]) grime[i] = grimeOf(worldSeed, cellX, cellZ, plan.lampChance)
                    lamp[i] = rng.nextFloat() < chance
                }

                val hall = rooms.firstOrNull { it.feature == ROOM_PIT_FIELD }
                return Sector(
                    v, h, post, lamp, pit, ceiling,
                    hall?.minX ?: -1, hall?.minZ ?: -1, hall?.maxX ?: -1, hall?.maxZ ?: -1,
                    dark, grime,
                    vDoor, hDoor, vNiche, hNiche
                )
            }

            private fun lampChance(worldSeed: Long, cellX: Int, cellZ: Int, lit: Float): Float {
                var chance = lit
                forDarkZones(worldSeed, cellX, cellZ) { inside, unlit ->
                    chance = minOf(chance, lit + (unlit - lit) * (inside / DARK_FADE).coerceIn(0f, 1f))
                }
                return chance
            }

            private fun grimeOf(worldSeed: Long, cellX: Int, cellZ: Int, lit: Float): Float {
                var grime = 0f
                forDarkZones(worldSeed, cellX, cellZ) { inside, unlit ->
                    val edge = DARK_FADE * (lit - DARK_BELOW) / (lit - unlit)
                    grime = maxOf(grime, ((inside - edge) / GRIME_RAMP).coerceIn(0f, 1f))
                }
                return grime
            }

            private inline fun forDarkZones(worldSeed: Long, cellX: Int, cellZ: Int, zone: (inside: Float, unlit: Float) -> Unit) {
                val districtX = Math.floorDiv(cellX, DARK_DISTRICT)
                val districtZ = Math.floorDiv(cellZ, DARK_DISTRICT)
                for (dx in -1..1) for (dz in -1..1) {
                    val hash = seedFor(worldSeed, districtX + dx, districtZ + dz, SALT_DARK)
                    if (((hash ushr 17) and 0xFFFF) >= DARK_ODDS) continue
                    val centreX = (districtX + dx) * DARK_DISTRICT + darkOffset(hash ushr 33)
                    val centreZ = (districtZ + dz) * DARK_DISTRICT + darkOffset(hash ushr 38)
                    val radius = DARK_RADIUS + (((hash ushr 43) and 0x3FF) % DARK_RADIUS_SPAN).toInt()
                    val distance = Math.hypot((cellX - centreX).toDouble(), (cellZ - centreZ).toDouble()).toFloat()
                    zone(radius - distance, DARK_LAMPS + ((hash ushr 53) and 0xFF) * (DARK_LAMPS_SPAN / 0xFF))
                }
            }

            private fun darkOffset(bits: Long): Int =
                DARK_MARGIN + (bits and 0x1F).toInt() * (DARK_DISTRICT - 2 * DARK_MARGIN) / DARK_DISTRICT

            private fun rollPlan(rng: Rng): Plan {
                if (rng.nextFloat() < GRAND_ROOM_CHANCE) {
                    return Plan(
                        1, 9, 12, rng.range(0.6f, 0.85f), rng.range(0.04f, 0.14f), LIT_LAMPS, 0.65f,
                        doorChance = rng.range(0f, 0.3f),
                        lowDoorChance = rng.range(0f, 0.4f),
                        nicheChance = rng.range(0f, 0.10f),
                    )
                }
                val spacious = rng.nextInt(5) < 2
                return Plan(
                    roomCount = 1 + rng.nextInt(8),
                    roomMin = 2,
                    roomMax = if (spacious) 5 + rng.nextInt(4) else 2 + rng.nextInt(3),
                    straightBias = rng.range(0.45f, 0.95f),
                    loopChance = rng.range(0.02f, 0.18f),
                    lampChance = LIT_LAMPS,
                    tallChance = rng.range(0.1f, 0.8f),
                    doorChance = rng.range(0f, 0.7f),
                    lowDoorChance = rng.range(0f, 0.5f),
                    nicheChance = rng.range(0f, 0.14f),
                )
            }

            private fun placeRooms(rng: Rng, plan: Plan, roomOf: IntArray): List<Room> {
                val rooms = ArrayList<Room>()
                rollPitHall(rng, rooms)
                val target = rooms.size + plan.roomCount
                var attempts = 0
                while (rooms.size < target && attempts < 24) {
                    attempts++
                    val width = plan.roomMin + rng.nextInt(plan.roomMax - plan.roomMin + 1)
                    val depth = plan.roomMin + rng.nextInt(plan.roomMax - plan.roomMin + 1)
                    if (width > N || depth > N) continue
                    val minX = rng.nextInt(N - width + 1)
                    val minZ = rng.nextInt(N - depth + 1)
                    val room = Room(minX, minZ, minX + width - 1, minZ + depth - 1, rollFeature(rng))
                    if (rooms.any { it.touches(room) }) continue
                    rooms.add(room)
                }

                for ((index, room) in rooms.withIndex()) {
                    room.forEachCell { cx, cz -> roomOf[cx * N + cz] = index }
                }
                return rooms
            }

            private fun rollPitHall(rng: Rng, rooms: MutableList<Room>) {
                if (rng.nextFloat() >= PIT_HALL_CHANCE) return
                val width = 5 + rng.nextInt(4)
                val depth = 5 + rng.nextInt(4)
                val minX = rng.nextInt(N - width + 1)
                val minZ = rng.nextInt(N - depth + 1)
                rooms.add(Room(minX, minZ, minX + width - 1, minZ + depth - 1, ROOM_PIT_FIELD))
            }

            private fun rollFeature(rng: Rng): Int = when (rng.nextInt(100)) {
                in 0 until 20 -> ROOM_EMPTY
                in 20 until 38 -> ROOM_PILLARS
                in 38 until 52 -> ROOM_COLONNADE
                in 52 until 64 -> ROOM_ALCOVES
                in 64 until 78 -> ROOM_WALL_ROWS
                in 78 until 92 -> ROOM_CLOSETS
                else -> ROOM_INNER
            }

            private fun pinchDoorways(
                rng: Rng,
                plan: Plan,
                v: BooleanArray,
                h: BooleanArray,
                vDoor: ByteArray,
                hDoor: ByteArray
            ) {
                if (plan.doorChance <= 0f) return
                for (cx in 1 until N) {
                    for (cz in 0 until N - 1) {
                        if (v[cx * N + cz]) continue
                        if (rng.nextFloat() >= plan.doorChance) continue
                        if (!h[cx * N + cz] || !h[(cx - 1) * N + cz]) continue
                        if (!h[cx * N + cz + 1] || !h[(cx - 1) * N + cz + 1]) continue
                        vDoor[cx * N + cz] = rollDoor(rng, plan)
                    }
                }
                for (cx in 0 until N - 1) {
                    for (cz in 1 until N) {
                        if (h[cx * N + cz]) continue
                        if (rng.nextFloat() >= plan.doorChance) continue
                        if (!v[cx * N + cz] || !v[cx * N + cz - 1]) continue
                        if (!v[(cx + 1) * N + cz] || !v[(cx + 1) * N + cz - 1]) continue
                        hDoor[cx * N + cz] = rollDoor(rng, plan)
                    }
                }
            }

            private fun rollDoor(rng: Rng, plan: Plan): Byte {
                val width = 1 + rng.nextInt(3)
                val start = 3 + rng.nextInt(6 - width)
                var door = (width shl 3) or start
                if (rng.nextFloat() < plan.lowDoorChance) door = door or DOOR_LOW
                return door.toByte()
            }

            private fun cutNiches(
                rng: Rng,
                plan: Plan,
                v: BooleanArray,
                h: BooleanArray,
                vNiche: ByteArray,
                hNiche: ByteArray
            ) {
                if (plan.nicheChance <= 0f) return
                for (i in 0 until N * N) {
                    if (v[i] && rng.nextFloat() < plan.nicheChance) vNiche[i] = rollNiche(rng)
                    if (h[i] && rng.nextFloat() < plan.nicheChance) hNiche[i] = rollNiche(rng)
                }
            }

            private fun rollNiche(rng: Rng): Byte =
                (if (rng.nextInt(2) == 0) NICHE_NEAR else NICHE_FAR).toByte()

            private fun carveCorridors(
                rng: Rng,
                straightBias: Float,
                v: BooleanArray,
                h: BooleanArray,
                roomOf: IntArray,
                rooms: List<Room>
            ) {
                val visited = BooleanArray(N * N)
                val stack = IntArray(N * N)
                val heading = IntArray(N * N)
                var top = 0

                fun push(cell: Int, dir: Int) {
                    stack[top] = cell
                    heading[top] = dir
                    top++
                }

                fun enter(cell: Int, dir: Int) {
                    val roomIndex = roomOf[cell]
                    if (roomIndex < 0) {
                        visited[cell] = true
                        push(cell, dir)
                        return
                    }
                    val room = rooms[roomIndex]
                    room.forEachCell { cx, cz ->
                        visited[cx * N + cz] = true
                        if (cx > room.minX) v[cx * N + cz] = false
                        if (cz > room.minZ) h[cx * N + cz] = false
                    }
                    push(cell, dir)
                    room.forEachCell { cx, cz ->
                        val other = cx * N + cz
                        if (other != cell) push(other, -1)
                    }
                }

                enter(rng.nextInt(N * N), -1)
                val candidates = IntArray(4)
                while (top > 0) {
                    val cell = stack[top - 1]
                    val dir = heading[top - 1]
                    val cx = cell / N
                    val cz = cell % N
                    var count = 0
                    if (cx > 0 && !visited[cell - N]) candidates[count++] = WEST
                    if (cx < N - 1 && !visited[cell + N]) candidates[count++] = EAST
                    if (cz > 0 && !visited[cell - 1]) candidates[count++] = NORTH
                    if (cz < N - 1 && !visited[cell + 1]) candidates[count++] = SOUTH
                    if (count == 0) {
                        top--
                        continue
                    }

                    var chosen = candidates[rng.nextInt(count)]
                    if (dir >= 0 && rng.nextFloat() < straightBias) {
                        for (i in 0 until count) if (candidates[i] == dir) chosen = dir
                    }

                    val next = when (chosen) {
                        WEST -> {
                            v[cx * N + cz] = false
                            cell - N
                        }

                        EAST -> {
                            v[(cx + 1) * N + cz] = false
                            cell + N
                        }

                        NORTH -> {
                            h[cx * N + cz] = false
                            cell - 1
                        }

                        else -> {
                            h[cx * N + cz + 1] = false
                            cell + 1
                        }
                    }
                    enter(next, chosen)
                }
            }

            private fun addLoops(rng: Rng, loopChance: Float, v: BooleanArray, h: BooleanArray) {
                for (cx in 1 until N) {
                    for (cz in 0 until N) {
                        if (v[cx * N + cz] && rng.nextFloat() < loopChance) v[cx * N + cz] = false
                    }
                }
                for (cx in 0 until N) {
                    for (cz in 1 until N) {
                        if (h[cx * N + cz] && rng.nextFloat() < loopChance) h[cx * N + cz] = false
                    }
                }
            }

            private fun openBorders(rng: Rng, v: BooleanArray, h: BooleanArray) {
                var vOpen = 0
                for (i in N until N * N) if (!v[i]) vOpen++
                var hOpen = 0
                for (cx in 0 until N) for (cz in 1 until N) if (!h[cx * N + cz]) hOpen++
                val interior = (N * (N - 1)).toFloat()
                openBorder(rng, v, vOpen / interior) { i -> i }
                openBorder(rng, h, hOpen / interior) { i -> i * N }
            }

            private inline fun openBorder(rng: Rng, walls: BooleanArray, rate: Float, index: (Int) -> Int) {
                var doors = 0
                for (i in 0 until N) {
                    if (rng.nextFloat() < rate) {
                        walls[index(i)] = false
                        doors++
                    }
                }
                if (doors == 0) walls[index(rng.nextInt(N))] = false
            }
        }
    }

    private class Room(val minX: Int, val minZ: Int, val maxX: Int, val maxZ: Int, val feature: Int) {

        fun touches(other: Room): Boolean =
            minX <= other.maxX + 1 && other.minX <= maxX + 1 &&
                minZ <= other.maxZ + 1 && other.minZ <= maxZ + 1

        inline fun forEachCell(action: (Int, Int) -> Unit) {
            for (cx in minX..maxX) for (cz in minZ..maxZ) action(cx, cz)
        }

        private inline fun forEachInnerCorner(action: (Int, Int) -> Unit) {
            for (cx in minX + 1..maxX) for (cz in minZ + 1..maxZ) action(cx, cz)
        }

        fun openPerimeter(rng: Rng, v: BooleanArray, h: BooleanArray) {
            val n = SECTOR_CELLS
            repeat(3 + rng.nextInt(4)) {
                when (rng.nextInt(4)) {
                    0 -> v[minX * n + (minZ + rng.nextInt(maxZ - minZ + 1))] = false
                    1 -> if (maxX + 1 < n) v[(maxX + 1) * n + (minZ + rng.nextInt(maxZ - minZ + 1))] = false
                    2 -> h[(minX + rng.nextInt(maxX - minX + 1)) * n + minZ] = false
                    else -> if (maxZ + 1 < n) h[(minX + rng.nextInt(maxX - minX + 1)) * n + maxZ + 1] = false
                }
            }
        }

        fun decorate(v: BooleanArray, h: BooleanArray, post: BooleanArray, pit: BooleanArray) {
            val n = SECTOR_CELLS
            when (feature) {
                ROOM_PILLARS -> forEachInnerCorner { cx, cz -> post[cx * n + cz] = true }

                ROOM_COLONNADE -> forEachInnerCorner { cx, cz ->
                    if ((cx - minX) % 2 == 1) post[cx * n + cz] = true
                }

                ROOM_ALCOVES -> forEachInnerCorner { cx, cz ->
                    if (cx == minX + 1 || cx == maxX || cz == minZ + 1 || cz == maxZ) post[cx * n + cz] = true
                }

                ROOM_WALL_ROWS -> wallRows(v, 2)

                ROOM_CLOSETS -> wallRows(v, 1)

                ROOM_PIT_FIELD -> forEachCell { cx, cz -> pit[cx * n + cz] = true }

                ROOM_INNER -> if (maxX - minX < 3 || maxZ - minZ < 3) {
                    forEachInnerCorner { cx, cz -> post[cx * n + cz] = true }
                } else {
                    innerRoom(v, h)
                }

                ROOM_EMPTY -> Unit
            }
        }

        private fun wallRows(v: BooleanArray, step: Int) {
            val n = SECTOR_CELLS
            for (cx in minX + 1..maxX) {
                if (step > 1 && (cx - minX) % step != 1) continue
                for (cz in minZ + 1..maxZ) v[cx * n + cz] = true
            }
        }

        private fun innerRoom(v: BooleanArray, h: BooleanArray) {
            val n = SECTOR_CELLS
            val innerMinX = minX + 1
            val innerMaxX = maxX - 1
            val innerMinZ = minZ + 1
            val innerMaxZ = maxZ - 1
            for (cz in innerMinZ..innerMaxZ) {
                v[innerMinX * n + cz] = true
                v[(innerMaxX + 1) * n + cz] = true
            }
            for (cx in innerMinX..innerMaxX) h[cx * n + (innerMaxZ + 1)] = true
        }
    }
}

private const val WEST = 0
private const val EAST = 1
private const val NORTH = 2
private const val SOUTH = 3
