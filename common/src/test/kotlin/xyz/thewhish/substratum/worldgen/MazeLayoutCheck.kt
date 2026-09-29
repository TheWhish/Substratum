package xyz.thewhish.substratum.worldgen

private const val WINDOW = 288
private const val SEEDS = 24

fun main(args: Array<String>) {
    val tally = Tally()
    repeat(SEEDS) { i ->
        val seed = -3L + i * 7_777_777L
        val layout = MazeLayout(seed)
        checkDeterminism(seed)
        checkCellConnectivity(layout, seed)
        checkNoSealedPockets(layout, seed)
        checkFooting(layout, seed)
        checkCrawlspace(layout, seed, tally)
        checkCorridorDrops(layout, seed, tally)
        checkNearestExit(layout, seed)
        checkNoSeamGrid(layout, seed)
        checkShape(layout, seed, tally)
        countCorridorDetail(layout, tally)
        countAnomalies(layout, tally)
    }
    check(tally.pits > 0) { "no floor holes anywhere — room variety is gone" }
    check(tally.holeColumns > 0) { "no floor hole resolves to an open lattice hole — pits have nowhere to lead" }
    check(tally.pillars > 0) { "no square pillars anywhere — room variety is gone" }
    check(tally.tallCeilings > 0) { "no tall rooms anywhere — room variety is gone" }
    check(tally.darkStretches > 0) { "no unlit stretches anywhere — lamp variety is gone" }
    check(tally.doorways > 0) { "no pinched doorways anywhere — corridor detail is gone" }
    check(tally.squeezes > 0) { "no low passages anywhere — corridor detail is gone" }
    check(tally.niches > 0) { "no wall niches anywhere — corridor detail is gone" }
    check(tally.anomalies > 0) { "no multi-block wall anomalies anywhere" }
    check(tally.soaks > 0) { "no multi-block carpet stains anywhere" }
    check(tally.leaks > 0) { "no multi-tile ceiling stains anywhere" }
    check(tally.flickeringLamps > 0) { "no flickering lamps anywhere" }
    check(tally.deadLamps > 0) { "no dead lamp fixtures anywhere" }
    check(tally.dimLamps > 0) { "no dim lamps anywhere" }
    val dropHit = tally.dropHits.toDouble() / tally.dropTries
    check(dropHit > 0.95) { "only ${dropHit.pct()}% of teleport anchors found a corridor to fall into" }
    val crawlHit = tally.crawlHits.toDouble() / tally.crawlTries
    check(crawlHit > 0.5) { "only ${crawlHit.pct()}% of footings can host a crawlspace — rifts have nowhere to open" }
    val crawlAxisHit = tally.crawlAxisHits.toDouble() / tally.crawlAxisTries
    check(crawlAxisHit > 0.5) {
        "only ${crawlAxisHit.pct()}% of footings can host a crawlspace on a named axis — half the rifts have nowhere to arrive"
    }
    val pitShare = tally.pits.toDouble() / (SEEDS * (WINDOW - 2) * (WINDOW - 2))
    check(pitShare in 0.002..0.06) { "floor holes are ${pitShare.pct()}% of all ground — not a rare hall" }
    println(
        "MazeLayout: all checks passed over $SEEDS seeds, ${WINDOW}x$WINDOW blocks each " +
            "(open ${tally.minOpen.pct()}..${tally.maxOpen.pct()}%)."
    )
    println(
        "  per window: ${tally.doorways / SEEDS} doorways (${tally.squeezes / SEEDS} squeeze columns), " +
            "${tally.niches / SEEDS} niches, holes ${"%.2f".format(pitShare * 100)}% of the ground."
    )
    println("  corridor drops: ${dropHit.pct()}% of teleport anchors.")
    println("  crawlspace sites: ${crawlHit.pct()}% of sampled footings, ${crawlAxisHit.pct()}% on a named axis.")
    println(
        "  damage per window: ${"%.1f".format(tally.anomalies.toDouble() / SEEDS)} wall-anomaly columns, " +
            "${"%.1f".format(tally.soaks.toDouble() / SEEDS)} stained carpet blocks, " +
            "${"%.1f".format(tally.leaks.toDouble() / SEEDS)} stained tiles."
    )
    println(
        "  lamps per window: ${tally.litLamps / SEEDS} lit, ${tally.flickeringLamps / SEEDS} flickering, " +
            "${tally.dimLamps / SEEDS} dim, ${tally.deadLamps / SEEDS} dead."
    )
    val map = args.firstOrNull { it.startsWith("--map") }
    if (map != null) printMap(MazeLayout(map.substringAfter("=", "-3").toLong()))
}

private fun checkNearestExit(layout: MazeLayout, seed: Long) {
    val random = java.util.Random(seed)
    repeat(8) {
        val x = random.nextInt(20_000) - 10_000
        val z = random.nextInt(20_000) - 10_000
        val hole = checkNotNull(layout.nearestExit(x, z, 1024)) { "seed $seed: no exit hole within 1024 blocks of ($x, $z)" }
        check(layout.pitHoleIsExit(hole[0], hole[1])) { "seed $seed: nearest exit (${hole[0]}, ${hole[1]}) is not an exit" }
        val dx = (hole[0] - x).toLong()
        val dz = (hole[1] - z).toLong()
        check(dx * dx + dz * dz <= 1030L * 1030L) { "seed $seed: nearest exit (${hole[0]}, ${hole[1]}) is outside the radius" }
        val near = layout.nearestExit(hole[0], hole[1], 16)
        check(near != null && near.contentEquals(hole)) { "seed $seed: the exit at (${hole[0]}, ${hole[1]}) is not nearest to itself" }
    }
}

private fun checkCorridorDrops(layout: MazeLayout, seed: Long, tally: Tally) {
    val random = java.util.Random(seed)
    repeat(DROP_SAMPLES) {
        val x = random.nextInt(DROP_SPREAD * 2) - DROP_SPREAD
        val z = random.nextInt(DROP_SPREAD * 2) - DROP_SPREAD
        tally.dropTries++
        val drop = layout.corridorDrop(x, z, DROP_RADIUS_CELLS) ?: return@repeat
        tally.dropHits++
        val (dx, dz) = drop[0] to drop[1]
        check(Math.floorMod(dx, MazeLayout.CELL) == MazeLayout.CENTRE && Math.floorMod(dz, MazeLayout.CELL) == MazeLayout.CENTRE) {
            "seed $seed: drop ($dx, $dz) is off the corridor centre line"
        }
        for (i in -1..1) for (j in -1..1) {
            val column = layout.columnAt(dx + i, dz + j)
            check(column and (MazeLayout.SOLID or MazeLayout.PIT_ROOM) == 0) { "seed $seed: drop ($dx, $dz) lands on wall or hole hall" }
            check(MazeLayout.ceilingOf(column) == MazeLayout.CEILING_LOW) { "seed $seed: drop ($dx, $dz) is under a non-corridor ceiling" }
        }
        val solid = { x: Int, z: Int -> layout.columnAt(x, z) and MazeLayout.SOLID != 0 }
        val walledX = solid(dx - 3, dz) && solid(dx + 3, dz) && !solid(dx - 2, dz) && !solid(dx + 2, dz)
        val walledZ = solid(dx, dz - 3) && solid(dx, dz + 3) && !solid(dx, dz - 2) && !solid(dx, dz + 2)
        check(walledX || walledZ) { "seed $seed: drop ($dx, $dz) is not between two corridor walls" }
    }
}

private const val DROP_SAMPLES = 64
private const val DROP_SPREAD = 50_000
private const val DROP_RADIUS_CELLS = 8

private class Tally {
    var pits = 0
    var holeColumns = 0
    var dropTries = 0
    var dropHits = 0
    var pillars = 0
    var tallCeilings = 0
    var darkStretches = 0
    var doorways = 0
    var squeezes = 0
    var niches = 0
    var anomalies = 0
    var leaks = 0
    var soaks = 0
    var litLamps = 0
    var flickeringLamps = 0
    var deadLamps = 0
    var dimLamps = 0
    var crawlTries = 0
    var crawlHits = 0
    var crawlAxisTries = 0
    var crawlAxisHits = 0
    var minOpen = 1.0
    var maxOpen = 0.0
}

private fun Double.pct(): Int = Math.round(this * 100).toInt()

private const val MAP_SIZE = 176

private fun printMap(layout: MazeLayout) {
    val origin = -MAP_SIZE / 2
    println()
    for (z in origin until origin + MAP_SIZE) {
        val row = StringBuilder()
        for (x in origin until origin + MAP_SIZE) {
            val column = layout.columnAt(x, z)
            val ceiling = MazeLayout.ceilingOf(column)
            val tall = ceiling > MazeLayout.CEILING_LOW
            row.append(
                when {
                    column and MazeLayout.SOLID != 0 -> if (tall) '@' else '#'
                    column and MazeLayout.PIT != 0 -> 'O'
                    ceiling == MazeLayout.CEILING_TIGHT -> '='
                    column and MazeLayout.LAMP != 0 -> 'o'
                    tall -> ':'
                    else -> ' '
                }
            )
        }
        println(row)
    }
}

private fun checkDeterminism(seed: Long) {
    val a = MazeLayout(seed)
    val b = MazeLayout(seed)
    for (x in -40..40) {
        for (z in -40..40) {
            check(a.columnAt(x, z) == b.columnAt(x, z)) { "seed $seed: column ($x, $z) is not deterministic" }
        }
    }
}

private fun checkCellConnectivity(layout: MazeLayout, seed: Long) {
    val radius = WINDOW / MazeLayout.CELL / 2
    val report = layout.connectivity(0, 0, radius)
    check(report[2] == 0) { "seed $seed: ${report[2]} sealed cell pocket(s) in the window" }
    check(report[1] > report[0] * 9 / 10) { "seed $seed: largest component only ${report[1]}/${report[0]} cells" }
}

private fun checkNoSealedPockets(layout: MazeLayout, seed: Long) {
    val n = WINDOW
    val origin = -n / 2
    val open = BooleanArray(n * n)
    for (i in 0 until n) {
        for (j in 0 until n) {
            val column = layout.columnAt(origin + i, origin + j)
            open[i * n + j] = column and (MazeLayout.SOLID or MazeLayout.PIT) == 0
        }
    }

    val component = IntArray(n * n)
    val stack = ArrayDeque<Int>()
    var components = 0
    for (start in 0 until n * n) {
        if (!open[start] || component[start] != 0) continue
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
            if (i > 0) visit(open, component, stack, current - n, components)
            if (i < n - 1) visit(open, component, stack, current + n, components)
            if (j > 0) visit(open, component, stack, current - 1, components)
            if (j < n - 1) visit(open, component, stack, current + 1, components)
        }
        check(touchesEdge) {
            "seed $seed: sealed pocket of $size blocks with no exit, first block at " +
                "(${origin + start / n}, ${origin + start % n})"
        }
    }
    check(components > 0) { "seed $seed: no open space at all" }
}

private fun visit(open: BooleanArray, component: IntArray, stack: ArrayDeque<Int>, next: Int, id: Int) {
    if (open[next] && component[next] == 0) {
        component[next] = id
        stack.addLast(next)
    }
}

private fun checkFooting(layout: MazeLayout, seed: Long) {
    val footing = layout.findFooting(MazeLayout.CENTRE, MazeLayout.CENTRE, 32)
    val flags = layout.columnAt(footing[0], footing[1])
    check(flags and MazeLayout.SOLID == 0) { "seed $seed: spawn footing is inside a wall" }
    check(flags and MazeLayout.PIT == 0) { "seed $seed: spawn footing is over a hole" }
}

private fun checkCrawlspace(layout: MazeLayout, seed: Long, tally: Tally) {
    val step = 37
    for (bx in -4..4) {
        for (bz in -4..4) {
            val footing = layout.findFooting(bx * step, bz * step, 32)
            for (alongX in listOf(null, true, false)) {
                val site = layout.crawlspace(footing[0], footing[1], alongX)
                if (alongX == null) {
                    tally.crawlTries++
                    if (site != null) tally.crawlHits++
                } else {
                    tally.crawlAxisTries++
                    if (site != null) tally.crawlAxisHits++
                }
                if (site == null) continue

                val mouthX = site[0]
                val mouthZ = site[1]
                val ax = site[2]
                val az = site[3]
                val length = site[4]
                val where = "seed $seed at (${footing[0]}, ${footing[1]}) along=$alongX"

                check(length in MazeLayout.CRAWL_MIN..MazeLayout.CRAWL_MAX) {
                    "$where: crawlspace is $length blocks long"
                }
                check(Math.abs(ax) + Math.abs(az) == 1) { "$where: the channel runs diagonally" }
                if (alongX != null) {
                    check((ax != 0) == alongX) { "$where: the channel ignored the axis it was given" }
                }

                val mouth = layout.columnAt(mouthX, mouthZ)
                check(mouth and MazeLayout.SOLID == 0) { "$where: the crawlspace has no way out" }
                check(mouth and MazeLayout.PIT == 0) { "$where: the crawlspace lets out over a hole" }

                checkSolid(layout, mouthX + ax * (length + 1), mouthZ + az * (length + 1), "$where: far end is open")
                for (i in 1..length) {
                    val cx = mouthX + ax * i
                    val cz = mouthZ + az * i
                    checkSolid(layout, cx, cz, "$where: channel column $i is not wall")
                    checkSolid(layout, cx + az, cz - ax, "$where: channel column $i opens sideways")
                    checkSolid(layout, cx - az, cz + ax, "$where: channel column $i opens sideways")
                }
            }
        }
    }
}

private fun checkSolid(layout: MazeLayout, x: Int, z: Int, message: String) {
    check(layout.columnAt(x, z) and MazeLayout.SOLID != 0) { message }
}

private fun checkNoSeamGrid(layout: MazeLayout, seed: Long) {
    val reach = 6 * MazeLayout.SECTOR_CELLS
    var borderSolid = 0
    var borderTotal = 0
    var interiorSolid = 0
    var interiorTotal = 0

    for (cellX in -reach until reach) {
        for (cellZ in -reach until reach) {
            val solid = layout.columnAt(cellX * MazeLayout.CELL, cellZ * MazeLayout.CELL + MazeLayout.CENTRE) and
                MazeLayout.SOLID != 0
            if (Math.floorMod(cellX, MazeLayout.SECTOR_CELLS) == 0) {
                borderTotal++
                if (solid) borderSolid++
            } else {
                interiorTotal++
                if (solid) interiorSolid++
            }
        }
    }

    val border = borderSolid.toDouble() / borderTotal
    val interior = interiorSolid.toDouble() / interiorTotal
    check(Math.abs(border - interior) < 0.08) {
        "seed $seed: sector seams are visible — walls solid ${border.pct()}% on border lines " +
            "vs ${interior.pct()}% inside"
    }
}

private fun checkShape(layout: MazeLayout, seed: Long, tally: Tally) {
    val n = WINDOW
    val origin = -n / 2
    var open = 0
    var posts = 0
    var lamps = 0
    var litCells = 0
    var darkCells = 0
    for (i in 1 until n - 1) {
        for (j in 1 until n - 1) {
            val x = origin + i
            val z = origin + j
            val column = layout.columnAt(x, z)

            val ceiling = MazeLayout.ceilingOf(column)
            val top = MazeLayout.topOf(column)
            check(
                ceiling == MazeLayout.CEILING_LOW || ceiling == MazeLayout.CEILING_HIGH ||
                    ceiling == MazeLayout.CEILING_TIGHT
            ) { "seed $seed: column ($x, $z) has ceiling $ceiling" }
            check(top >= ceiling) { "seed $seed: column ($x, $z) has top $top below ceiling $ceiling" }
            if (ceiling == MazeLayout.CEILING_HIGH) tally.tallCeilings++

            if (column and MazeLayout.PIT != 0) {
                tally.pits++
                checkPitHole(layout, seed, x, z, tally)
            }
            if (column and MazeLayout.LAMP != 0) {
                lamps++
                check(Math.floorMod(x, MazeLayout.CELL) == MazeLayout.CENTRE) {
                    "seed $seed: lamp at ($x, $z) is off the 8-block centre line"
                }
            }
            val centre = Math.floorMod(x, MazeLayout.CELL) == MazeLayout.CENTRE &&
                Math.floorMod(z, MazeLayout.CELL) == MazeLayout.CENTRE
            val lit = column and MazeLayout.LAMP != 0
            val dead = column and MazeLayout.DEAD_LAMP != 0
            check(!(lit && dead) && (centre || !dead)) { "seed $seed: lamp fixture at ($x, $z) is doubled or off-centre" }
            if (centre && column and (MazeLayout.SOLID or MazeLayout.DARK_ZONE) == 0) {
                check(lit || dead) { "seed $seed: lamp grid has a bare ceiling tile at ($x, $z)" }
            }
            if (lit || dead) {
                check(layout.ceilingAnomaly(x, z, ceiling) < 0) { "seed $seed: ceiling stain covers the lamp fixture at ($x, $z)" }
            }
            if (centre) {
                if (lit) litCells++ else darkCells++
                when {
                    dead -> tally.deadLamps++
                    !lit -> {}
                    layout.lampFlickers(x, z, column and MazeLayout.DARK_ZONE != 0) -> tally.flickeringLamps++
                    layout.lampDims(x, z, column and MazeLayout.DARK_ZONE != 0) -> tally.dimLamps++
                    else -> tally.litLamps++
                }
            }

            if (column and MazeLayout.SOLID == 0) {
                open++
                continue
            }
            val lonely = !solid(layout, x - 1, z) && !solid(layout, x + 1, z) &&
                !solid(layout, x, z - 1) && !solid(layout, x, z + 1)
            if (lonely) posts++
            if (isSquarePillar(layout, x, z)) tally.pillars++
        }
    }

    val total = (n - 2) * (n - 2)
    val openRatio = open.toDouble() / total
    check(posts == 0) { "seed $seed: $posts free-standing 1x1 posts" }
    check(openRatio in 0.45..0.88) { "seed $seed: open ratio $openRatio outside the corridor-maze range" }
    check(lamps > 0) { "seed $seed: no lamps placed" }
    val litFraction = litCells.toDouble() / (litCells + darkCells)
    check(litFraction in 0.55..0.98) { "seed $seed: $litFraction of centre lines lit" }
    if (darkCells > 0) tally.darkStretches++
    tally.minOpen = minOf(tally.minOpen, openRatio)
    tally.maxOpen = maxOf(tally.maxOpen, openRatio)
}

private fun checkPitHole(layout: MazeLayout, seed: Long, x: Int, z: Int, tally: Tally) {
    val hole = layout.pitHoleAt(x, z) ?: return
    tally.holeColumns++
    check(x - hole[0] in 0 until MazeLayout.PIT_HOLE && z - hole[1] in 0 until MazeLayout.PIT_HOLE) {
        "seed $seed: hole column ($x, $z) maps to hole (${hole[0]}, ${hole[1]}) that does not cover it"
    }
    for (i in 0 until MazeLayout.PIT_HOLE) for (j in 0 until MazeLayout.PIT_HOLE) {
        check(layout.columnAt(hole[0] + i, hole[1] + j) and MazeLayout.PIT != 0) {
            "seed $seed: hole (${hole[0]}, ${hole[1]}) has a closed column"
        }
        check(layout.pitHoleAt(hole[0] + i, hole[1] + j)?.contentEquals(hole) == true) {
            "seed $seed: hole (${hole[0]}, ${hole[1]}) does not answer for all of its columns"
        }
    }
}

private fun solid(layout: MazeLayout, x: Int, z: Int): Boolean =
    layout.columnAt(x, z) and MazeLayout.SOLID != 0

private fun countCorridorDetail(layout: MazeLayout, tally: Tally) {
    val cells = WINDOW / MazeLayout.CELL / 2
    for (cellX in -cells until cells) {
        for (cellZ in -cells until cells) {
            val bx = cellX * MazeLayout.CELL
            val bz = cellZ * MazeLayout.CELL

            var open = 0
            var blocked = 0
            for (local in MazeLayout.WALL until MazeLayout.CELL) {
                val column = layout.columnAt(bx + 1, bz + local)
                if (column and MazeLayout.SOLID != 0) blocked++ else open++
                if (MazeLayout.ceilingOf(column) == MazeLayout.CEILING_TIGHT) tally.squeezes++
            }
            if (open > 0 && blocked > 0) tally.doorways++

            open = 0
            blocked = 0
            for (local in MazeLayout.WALL until MazeLayout.CELL) {
                val column = layout.columnAt(bx + local, bz + 1)
                if (column and MazeLayout.SOLID != 0) blocked++ else open++
                if (MazeLayout.ceilingOf(column) == MazeLayout.CEILING_TIGHT) tally.squeezes++
            }
            if (open > 0 && blocked > 0) tally.doorways++

            val mid = MazeLayout.CENTRE
            if (solid(layout, bx + 1, bz + mid) &&
                (!solid(layout, bx, bz + mid) || !solid(layout, bx + MazeLayout.WALL - 1, bz + mid))
            ) {
                tally.niches++
            }
            if (solid(layout, bx + mid, bz + 1) &&
                (!solid(layout, bx + mid, bz) || !solid(layout, bx + mid, bz + MazeLayout.WALL - 1))
            ) {
                tally.niches++
            }
        }
    }
}

private fun countAnomalies(layout: MazeLayout, tally: Tally) {
    val origin = -WINDOW / 2
    for (x in origin until origin + WINDOW) {
        for (z in origin until origin + WINDOW) {
            val column = layout.columnAt(x, z)
            if (column and MazeLayout.SOLID == 0) {
                if (column and MazeLayout.PIT == 0) {
                    val soak = layout.carpetAnomaly(x, z)
                    if (soak >= 0) {
                        tally.soaks++
                        checkFlatSlice(MazeLayout.CARPET_ANOMALIES, soak, x, z, "carpet") { nx, nz ->
                            layout.carpetAnomaly(nx, nz)
                        }
                    }
                }
                val ceiling = MazeLayout.ceilingOf(column)
                val leak = layout.ceilingAnomaly(x, z, ceiling)
                if (leak >= 0) {
                    tally.leaks++
                    checkFlatSlice(MazeLayout.CEILING_ANOMALIES, leak, x, z, "ceiling") { nx, nz ->
                        layout.ceilingAnomaly(nx, nz, MazeLayout.ceilingOf(layout.columnAt(nx, nz)))
                    }
                }
                continue
            }
            checkWallColumn(layout, x, z, tally)
        }
    }
}

private fun checkWallColumn(layout: MazeLayout, x: Int, z: Int, tally: Tally) {
    val top = MazeLayout.topOf(layout.columnAt(x, z))
    var sawAnomaly = false
    for (y in MazeLayout.WALL_BASE_Y until top) {
        val part = layout.wallAnomaly(x, y, z, top)
        if (part < 0) continue
        check(part in 0 until WALL_VARIANT_COUNT) {
            "wall anomaly part $part at ($x, $y, $z) is not a blockstate variant"
        }
        val kind = MazeLayout.footprintOf(MazeLayout.WALL_ANOMALIES, part)
        check(kind != null) { "wall anomaly part $part at ($x, $y, $z) belongs to no footprint" }
        sawAnomaly = true
        val local = part - kind.base
        val row = local / kind.cols
        val col = local % kind.cols
        check(kind.rowAt(y, top) == row) {
            "wall anomaly at ($x, $y, $z) is row $row of its kind but sits at the Y of row ${kind.rowAt(y, top)}"
        }
        if (row > 0) {
            val above = layout.wallAnomaly(x, y + 1, z, top)
            check(above == part - kind.cols) {
                "wall anomaly at ($x, $y, $z) has no row ${row - 1} above it: found $above"
            }
        }
        if (col > 0) {
            val left = part - 1
            fun holds(nx: Int, nz: Int) = layout.wallAnomaly(nx, y, nz, MazeLayout.topOf(layout.columnAt(nx, nz))) == left
            check(holds(x - 1, z) || holds(x + 1, z) || holds(x, z - 1) || holds(x, z + 1)) {
                "wall anomaly at ($x, $y, $z) has no column ${col - 1} beside it"
            }
        }
    }
    if (sawAnomaly) tally.anomalies++
}

private fun checkFlatSlice(
    kinds: Array<MazeLayout.Footprint>,
    part: Int,
    x: Int,
    z: Int,
    what: String,
    at: (Int, Int) -> Int
) {
    val kind = MazeLayout.footprintOf(kinds, part)
    check(kind != null) { "$what anomaly part $part at ($x, $z) belongs to no footprint" }
    val local = part - kind.base
    val originX = x - local % kind.cols
    val originZ = z - local / kind.cols
    for (i in 0 until kind.cols) for (j in 0 until kind.rows) {
        check(at(originX + i, originZ + j) == kind.base + j * kind.cols + i) {
            "$what anomaly at ($x, $z) is missing its slice ($i, $j) at (${originX + i}, ${originZ + j})"
        }
    }
}

private val WALL_VARIANT_COUNT: Int =
    MazeLayout.WALL_ANOMALIES.maxOf { it.base + it.size }

private fun isSquarePillar(layout: MazeLayout, x: Int, z: Int): Boolean {
    val side = MazeLayout.WALL
    for (dx in 0 until side) for (dz in 0 until side) if (!solid(layout, x + dx, z + dz)) return false
    for (d in -1..side) {
        if (solid(layout, x + d, z - 1) || solid(layout, x + d, z + side)) return false
        if (solid(layout, x - 1, z + d) || solid(layout, x + side, z + d)) return false
    }
    return true
}
