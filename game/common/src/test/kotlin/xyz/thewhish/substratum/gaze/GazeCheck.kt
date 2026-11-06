package xyz.thewhish.substratum.gaze

import net.minecraft.world.phys.Vec3
import xyz.thewhish.substratum.network.ViewportSync
import xyz.thewhish.substratum.worldgen.Corridors
import xyz.thewhish.substratum.worldgen.MazeLayout
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

private const val PAIRS = 20000
private const val SAMPLE_STEP = 0.01
private const val SEED = 0x5311EL
private val layout = MazeLayout(SEED)

private fun viewer(eye: Vec3, forward: Vec3, viewport: ViewportSync.Viewport = ViewportSync.DEFAULT, margin: Double = Viewer.MIN_MARGIN, stride: Double = Viewer.stride(0.0, 0)) =
    Viewer(UUID.randomUUID(), eye, forward.normalize(), viewport, margin, stride = stride)

private fun walkable(random: Random): Corridors.Column {
    while (true) {
        val x = random.nextInt(-400, 400)
        val z = random.nextInt(-400, 400)
        if (Corridors.walkable(layout.columnAt(x, z))) return Corridors.Column(x, z)
    }
}

private fun eyeAt(column: Corridors.Column): Vec3 = Vec3(column.x + 0.5, Gaze.FLOOR + Gaze.EYE, column.z + 0.5)

private fun checkCone() {
    val random = Random(11)
    for (fov in listOf(7f, 30f, 70f, 110f, 150f)) for (aspect in listOf(0.25f, 0.5f, 4f / 3f, 16f / 9f, 32f / 9f, 8f)) repeat(20) {
        val viewport = ViewportSync.Viewport(fov, aspect, ViewportSync.Perspective.FIRST_PERSON)
        val forward = Vec3(random.nextDouble(-1.0, 1.0), random.nextDouble(-0.8, 0.8), random.nextDouble(-1.0, 1.0)).normalize()
        val view = viewer(Vec3(random.nextDouble(-50.0, 50.0), 3.62, random.nextDouble(-50.0, 50.0)), forward, viewport)
        val width = tan(viewport.halfHFov)
        val height = tan(viewport.halfVFov)
        fun screen(u: Double, v: Double): Vec3 = view.eye.add(forward.add(view.right.scale(u * width)).add(view.up.scale(v * height)).scale(10.0))
        for (u in listOf(-0.98, 0.0, 0.98)) for (v in listOf(-0.98, 0.0, 0.98)) {
            check(view.onScreen(screen(u, v)) && view.frames(screen(u, v))) { "fov $fov aspect $aspect: on-screen point ($u, $v) is not framed" }
        }
        for ((u, v) in listOf(1.02 to 0.0, -1.02 to 0.0, 0.0 to 1.02, 0.0 to -1.02)) {
            check(!view.onScreen(screen(u, v))) { "fov $fov aspect $aspect: off-screen point ($u, $v) is on screen" }
            check(view.frames(screen(u, v))) { "fov $fov aspect $aspect: the ping margin misses ($u, $v)" }
        }
        val beyond = view.halfH + view.margin + Math.toRadians(1.0)
        if (beyond < PI) {
            val outside = view.eye.add(forward.scale(cos(beyond)).add(view.right.scale(sin(beyond))).scale(10.0))
            check(!view.frames(outside)) { "fov $fov aspect $aspect: a point past the margin is framed" }
        }
        check(!view.frames(view.eye.subtract(forward))) { "fov $fov aspect $aspect: a point behind the camera is framed" }
        check(viewer(view.eye, forward, viewport, PI).frames(view.eye.subtract(forward))) { "a half-turn margin misses the back" }
    }
    val look = Vec3(0.3, -0.2, 1.0).normalize()
    val eye = Vec3(0.5, 3.62, 0.5)
    for (perspective in ViewportSync.Perspective.entries) for (reach in listOf(4.0, 1.5)) {
        val (camera, forward) = Viewer.camera(eye, look, perspective) { reach }
        val view = viewer(camera, forward)
        val expected = if (perspective == ViewportSync.Perspective.FIRST_PERSON) 0.0 else reach
        check(abs(camera.distanceTo(eye) - expected) < 1.0e-9) { "$perspective camera stands ${camera.distanceTo(eye)} from the eye" }
        if (perspective != ViewportSync.Perspective.FIRST_PERSON) check(view.onScreen(eye) && view.centrality(eye) > 0.99) { "$perspective camera does not look at the player" }
    }
    println("cone: screen edges, margins and third person agree")
}

private fun cutsAround(random: Random, at: Vec3): Set<Long> {
    val cx = floor(at.x).toInt()
    val cz = floor(at.z).toInt()
    val walls = ArrayList<Long>()
    for (x in cx - 30..cx + 30) for (z in cz - 30..cz + 30) if (layout.columnAt(x, z) and MazeLayout.SOLID != 0) walls.add(Gaze.key(x, z))
    return walls.shuffled(random).take(40).toHashSet()
}

private fun sampledClear(cuts: Set<Long>, from: Vec3, to: Vec3): Boolean {
    val steps = (from.distanceTo(to) / SAMPLE_STEP).toInt() + 1
    return (0..steps).all { i ->
        val p = from.lerp(to, i.toDouble() / steps)
        val x = floor(p.x).toInt()
        val z = floor(p.z).toInt()
        val column = layout.columnAt(x, z)
        if (column and MazeLayout.SOLID != 0) return@all Gaze.key(x, z) in cuts && p.y >= Gaze.FLOOR && p.y < Gaze.CUT_CEILING
        p.y < MazeLayout.ceilingOf(column) && (p.y >= Gaze.FLOOR || column and MazeLayout.PIT != 0)
    }
}

private fun checkRays() {
    val random = Random(5)
    var clear = 0
    var grazed = 0
    var asymmetric = 0
    var throughCuts = 0
    var underLow = 0
    repeat(PAIRS) {
        val from = Vec3(random.nextDouble(-200.0, 200.0), random.nextDouble(2.1, 4.4), random.nextDouble(-200.0, 200.0))
        val to = from.add(Vec3(random.nextDouble(-1.0, 1.0), 0.0, random.nextDouble(-1.0, 1.0)).normalize().scale(random.nextDouble(1.0, 40.0)))
            .let { Vec3(it.x, random.nextDouble(2.1, 4.4), it.z) }
        val cuts = if (it % 4 == 0) cutsAround(random, from) else emptySet()
        val gaze = Gaze(layout, emptyList(), cuts = cuts)
        val traced = gaze.clear(from, to)
        val sampled = sampledClear(cuts, from, to)
        check(!traced || sampled) { "ray $from -> $to passes a wall the samples hit" }
        if (traced) clear++
        if (traced && cuts.isNotEmpty() && !Gaze(layout, emptyList()).clear(from, to)) throughCuts++
        if (traced && max(from.y, to.y) > MazeLayout.CEILING_TIGHT - 1) underLow++
        if (!traced && sampled) grazed++
        if (traced != gaze.clear(to, from)) asymmetric++
    }
    check(clear in PAIRS / 10..PAIRS * 9 / 10) { "only $clear of $PAIRS rays are clear, the sample area is degenerate" }
    check(grazed * 100 < PAIRS) { "$grazed rays blocked without a sampled wall" }
    check(asymmetric * 1000 < PAIRS) { "$asymmetric rays differ by direction" }
    check(throughCuts > 0) { "no ray ever passed a rift cut" }
    check(!Gaze(layout, emptyList()).clear(Vec3(0.5, 3.0, 0.5), Vec3(0.5, 40.0, 0.5))) { "a ray through the ceiling is clear" }
    println("rays: $clear/$PAIRS clear, $throughCuts through rift cuts, $underLow near low ceilings, $grazed grazing, $asymmetric asymmetric")
}

private fun checkTurns() {
    check(abs(Viewer.margin(0.0, 0) - Viewer.MIN_MARGIN) < 1.0e-12) { "a still head has more than the minimum margin" }
    check(abs(Viewer.margin(Math.toRadians(5.0), 100) - Math.toRadians(25.0)) < 1.0e-9) { "5 deg/tick at 100 ms should give 25 deg: two ticks of ping and three of display" }
    check(abs(Viewer.stride(0.0, 0) - 1.2) < 1.0e-9 && abs(Viewer.stride(1.0, 100) - 5.0) < 1.0e-9 && Viewer.stride(10.0, 1000) == 6.0) { "the eye stride is off" }
    check(Viewer.margin(Math.toRadians(10.0), 1000) == PI) { "the margin is not capped at a half turn" }
    check(Viewer.kind(false, Math.toRadians(3.0), Viewer.margin(Math.toRadians(3.0), 50)) == Viewer.Kind.LOOKING) { "a slow turn sweeps" }
    check(Viewer.kind(false, Math.toRadians(12.0), Viewer.margin(Math.toRadians(12.0), 0)) == Viewer.Kind.SWEEPING) { "a fast turn does not sweep" }
    check(Viewer.kind(false, Math.toRadians(8.0), Viewer.margin(Math.toRadians(8.0), 2000)) == Viewer.Kind.SWEEPING) { "a capped margin does not sweep" }
    check(Viewer.kind(true, 0.0, Viewer.MIN_MARGIN) == Viewer.Kind.EVERYWHERE) { "a spectator does not see everywhere" }
    val sweeping = Viewer(UUID.randomUUID(), Vec3.ZERO, Vec3(0.0, 0.0, 1.0), ViewportSync.DEFAULT, kind = Viewer.Kind.SWEEPING)
    check(sweeping.frames(Vec3(0.0, 0.0, -5.0))) { "a sweeping viewer misses its back" }
    check(Viewer.range(64.0, null) == 64.0 && Viewer.range(64.0, 100) == 5.0 && Viewer.range(64.0, -1) == 5.0) { "blindness range is off" }
    check(abs(Viewer.range(64.0, 10) - 34.5) < 1.0e-9 && Viewer.range(3.0, 100) == 3.0) { "fading blindness range is off" }
    val wide = ViewportSync.Viewport(90f, 16f / 9f, ViewportSync.Perspective.FIRST_PERSON, 60f)
    val narrow = ViewportSync.Viewport(70f, 16f / 9f, ViewportSync.Perspective.THIRD_PERSON_BACK, 48f)
    val history = listOf(ViewportSync.Seen(0, wide), ViewportSync.Seen(300, narrow))
    val recent = ViewportSync.widest(history, 600)
    check(recent.vFovDegrees == 90f && recent.haze == 60f && recent.perspective == ViewportSync.Perspective.THIRD_PERSON_BACK) { "a narrowing report forgets the wide frame too early" }
    check(ViewportSync.widest(history, 900) == narrow) { "a wide frame lingers past ${ViewportSync.MEMORY_MS} ms" }
    println("turns: margins, sweeps, blindness and viewport memory agree")
}

private fun lampColumn(random: Random, lamp: Boolean): Vec3 {
    while (true) {
        val cellX = random.nextInt(-60, 60)
        val cellZ = random.nextInt(-60, 60)
        val x = cellX * MazeLayout.CELL + MazeLayout.CENTRE
        val z = cellZ * MazeLayout.CELL + MazeLayout.CENTRE
        val column = layout.columnAt(x, z)
        if (column and MazeLayout.SOLID != 0) continue
        if (lamp && column and MazeLayout.LAMP != 0) return Vec3(x + 0.5, Gaze.FLOOR + 0.1, z + 0.5)
        if (!lamp && (cellX - 1..cellX + 1).all { cx ->
                (cellZ - 1..cellZ + 1).all { cz ->
                    layout.columnAt(cx * MazeLayout.CELL + MazeLayout.CENTRE, cz * MazeLayout.CELL + MazeLayout.CENTRE) and MazeLayout.LAMP == 0
                }
            }
        ) return Vec3(x + 0.5, Gaze.FLOOR + 0.1, z + 0.5)
    }
}

private fun checkNotice() {
    val random = Random(17)
    val gaze = Gaze(layout, emptyList())
    repeat(50) {
        check(gaze.lit(lampColumn(random, lamp = true)).light >= Gaze.NOTICED) { "the floor under a lamp is dark" }
        check(gaze.lit(lampColumn(random, lamp = false)).light == 0.0) { "a floor without lamps around is lit" }
    }
    var dark = 0
    var bright = 0
    var tries = 0
    while ((dark < 40 || bright < 40) && tries++ < 200000) {
        val eye = eyeAt(walkable(random))
        val target = eye.add(Vec3(random.nextDouble(-1.0, 1.0), 0.0, random.nextDouble(-1.0, 1.0)).normalize().scale(random.nextDouble(12.0, 40.0)))
            .let { Vec3(it.x, random.nextDouble(2.1, 4.4), it.z) }
        if (!gaze.clear(eye, target)) continue
        val view = viewer(eye, target.subtract(eye))
        val lit = gaze.lit(target).light >= Gaze.NOTICED
        check(gaze.sees(view, target, glow = true)) { "a glowing point in plain view is missed" }
        check(gaze.judge(view, target, glow = false) == if (lit) Gaze.Sight.SEEN else Gaze.Sight.DARK) { "a ${if (lit) "lit" else "dark"} point at ${target.distanceTo(eye)} has the wrong visibility" }
        if (lit) bright++ else dark++
    }
    check(dark >= 40 && bright >= 40) { "noticeability sample is one-sided: $dark dark, $bright lit" }
    println("notice: $bright lit and $dark dark points in plain view behave")
}

private fun checkMemory() {
    val random = Random(23)
    var remembered = 0
    var nanos = 0L
    repeat(60) {
        val home = walkable(random)
        val yaw = random.nextDouble(0.0, 2 * PI)
        val forward = Vec3(sin(yaw), 0.0, cos(yaw))
        val view = viewer(eyeAt(home), forward)
        val gaze = Gaze(layout, listOf(view))
        val memory = HashMap<Long, Long>()
        val started = System.nanoTime()
        Watch.remember(gaze, view.steady(), memory, 100)
        nanos += System.nanoTime() - started
        remembered += memory.size
        val cellX = Math.floorDiv(home.x, MazeLayout.CELL)
        val cellZ = Math.floorDiv(home.z, MazeLayout.CELL)
        for (cx in cellX - 6..cellX + 6) for (cz in cellZ - 6..cellZ + 6) {
            val centre = Vec3((cx * MazeLayout.CELL + MazeLayout.CELL / 2).toDouble(), view.eye.y, (cz * MazeLayout.CELL + MazeLayout.CELL / 2).toDouble())
            val away = centre.subtract(view.eye)
            if (away.horizontalDistance() >= 2.5 * MazeLayout.CELL && away.normalize().dot(forward) < -0.7) {
                check(Gaze.key(cx, cz) !in memory) { "a cell straight behind the player was remembered" }
            }
        }
        val far = viewer(view.eye.add(4000.0, 0.0, 0.0), forward)
        Watch.remember(Gaze(layout, listOf(far)), far, memory, 100 + 20L * 60 * 10 + 1)
        check(memory.values.all { it > 100 }) { "old cells are not forgotten" }
    }
    check(remembered > 60) { "almost nothing was remembered: $remembered cells" }
    println("memory: $remembered cells remembered over 60 looks (%.2f ms per look), nothing behind, old ones forgotten".format(nanos / 1e6 / 60))
}

private fun checkQueries() {
    val random = Random(29)
    var soonPoints = 0
    repeat(100) {
        val home = walkable(random)
        val yaw = random.nextDouble(0.0, 2 * PI)
        val forward = Vec3(sin(yaw), 0.0, cos(yaw))
        val view = viewer(eyeAt(home), forward)
        val gaze = Gaze(layout, listOf(view))
        val field = Corridors(layout, home.x, home.z, 12)
        for (point in gaze.soon(view, field)) {
            soonPoints++
            val steps = field.steps(floor(point.x).toInt(), floor(point.z).toInt())
            check(steps in 5..12) { "a soon point is $steps steps away" }
            check(Vec3(point.x - home.x - 0.5, 0.0, point.z - home.z - 0.5).normalize().dot(forward) >= 0.3) { "a soon point lies off the look" }
        }
        val rear = gaze.rear(view.eye, listOf(view))
        check(rear.none { it.dot(forward) > 0.9 }) { "the look direction counts as rear" }
        check(rear.any { it.dot(forward) < -0.9 }) { "the back does not count as rear" }
    }
    check(soonPoints > 0) { "no soon points at all" }
    val viewers = (0 until 4).map { val home = walkable(random); viewer(eyeAt(home), Vec3(random.nextDouble(-1.0, 1.0), 0.0, random.nextDouble(-1.0, 1.0))) }
    val cells = (0 until 4000).map {
        val near = viewers.random(random).eye
        Math.floorDiv(floor(near.x).toInt(), MazeLayout.CELL) + random.nextInt(-8, 9) to Math.floorDiv(floor(near.z).toInt(), MazeLayout.CELL) + random.nextInt(-8, 9)
    }.distinct()
    val rounds = cells.size
    fun timed(gaze: () -> Gaze): Pair<Double, Int> {
        var nanos = 0L
        var unseen = 0
        for ((cellX, cellZ) in cells) {
            val snapshot = gaze()
            val started = System.nanoTime()
            if (snapshot.unseen(snapshot.cell(cellX, cellZ), glow = false)) unseen++
            nanos += System.nanoTime() - started
        }
        return nanos / 1e6 / rounds to unseen
    }
    timed { Gaze(layout, viewers) }
    val (first, unseen) = (0 until 5).map { timed { Gaze(layout, viewers) } }.minBy { it.first }
    val warm = (0 until 5).minOf { Gaze(layout, viewers).let { shared -> timed { shared }.first } }
    check(warm <= 0.02) { "a cell volume query costs %.3f ms within a tick".format(warm) }
    println("queries: $soonPoints soon points, cell volume %.3f ms in a fresh snapshot, %.3f ms in a shared one, 4 moving viewers, %d of %d unseen".format(first, warm, unseen, rounds))
}

private fun checkLead() {
    val random = Random(37)
    var revealed = 0
    var spared = 0
    var pairs = 0
    while (pairs < 4000) {
        val eye = eyeAt(walkable(random))
        val target = eye.add(Vec3(random.nextDouble(-1.0, 1.0), 0.0, random.nextDouble(-1.0, 1.0)).normalize().scale(random.nextDouble(3.0, 20.0)))
        val forward = target.subtract(eye).normalize()
        val still = viewer(eye, forward, stride = 0.0)
        val walking = Viewer(UUID.randomUUID(), eye, forward, ViewportSync.DEFAULT, stride = Viewer.stride(0.0, 0))
        val gaze = Gaze(layout, listOf(still, walking))
        val across = Vec3(eye.z - target.z, 0.0, target.x - eye.x).normalize()
        val steps = (1..12).map { across.scale(it * walking.stride / 12) }.flatMap { listOf(it, it.scale(-1.0)) }
        val open = steps.filter { step -> layout.columnAt(floor(eye.x + step.x).toInt(), floor(eye.z + step.z).toInt()) and MazeLayout.SOLID == 0 }
        val stepped = open.filter { step -> gaze.clear(eye.add(step), target) && gaze.clear(eye, eye.add(step)) }
        val now = gaze.judge(walking, target, glow = true)
        val before = gaze.judge(still, target, glow = true)
        check(before != Gaze.Sight.SEEN || now == Gaze.Sight.SEEN) { "the stride hides what the current eye sees" }
        pairs++
        if (before == Gaze.Sight.HIDDEN && stepped.isEmpty() && now == Gaze.Sight.SEEN) spared++
        if (before != Gaze.Sight.HIDDEN || stepped.isEmpty()) continue
        check(now == Gaze.Sight.SEEN) { "a point revealed by a sidestep of ${stepped.first().length()} is still hidden" }
        revealed++
    }
    check(revealed > 0) { "a sidestep never revealed anything around a corner" }
    val behind = Viewer(UUID.randomUUID(), Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0), ViewportSync.DEFAULT)
    val open = Gaze(null, listOf(behind))
    check(open.judge(behind, Vec3(0.0, 0.0, -10.0), glow = false) == Gaze.Sight.OUT) { "a point behind is framed" }
    check(open.judge(behind, Vec3(0.0, 0.0, -10.0), glow = false, anyTurn = true) == Gaze.Sight.SEEN) { "a turn-proof query misses the back" }
    check(open.judge(behind, Vec3(0.0, 0.0, 100.0), glow = false, anyTurn = true) == Gaze.Sight.FAR) { "a point beyond the fog is not far" }
    println("stride: $revealed corners revealed by a sidestep of up to 1.2 blocks, $spared counted seen without a sampled sidestep, turn-proof queries see the back")
}

private fun checkWindows() {
    val open = Gaze(null, emptyList())
    val eye = Vec3(0.0, 0.0, 0.0)
    val forward = Vec3(0.0, 0.0, 1.0)
    val shift = Vec3(100.0, 0.0, 0.0)
    val window = Viewer.Window(Vec3(0.0, 0.0, 5.0).add(shift), Vec3(0.0, 0.0, -1.0), Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), 1.0, 1.0)
    val through = viewer(eye, forward).through(eye.add(shift), forward, window)
    check(open.judge(through, Vec3(100.0, 0.0, 10.0), glow = false) == Gaze.Sight.SEEN) { "a point straight through the window is not seen" }
    check(open.judge(through, Vec3(103.0, 0.0, 10.0), glow = false) == Gaze.Sight.OUT) { "a point beside the window is seen through it" }
    check(open.judge(through, Vec3(100.0, 0.0, 3.0), glow = false) == Gaze.Sight.OUT) { "a point in front of the far window is seen through it" }
    check(window.crossing(Vec3(100.0, 0.0, 0.0), Vec3(100.5, 0.5, 9.0)) != null && window.crossing(Vec3(100.0, 0.0, 0.0), Vec3(100.0, 0.0, 4.9)) == null) { "window crossing is off" }
    val random = Random(31)
    repeat(20000) {
        val from = Vec3(random.nextDouble(98.0, 102.0), random.nextDouble(-2.0, 2.0), random.nextDouble(-4.0, 4.0))
        val to = Vec3(random.nextDouble(90.0, 110.0), random.nextDouble(-10.0, 10.0), random.nextDouble(-4.0, 20.0))
        val hit = window.crossing(from, to)
        val t = (5.0 - from.z) / (to.z - from.z)
        val expected = t in 0.0..1.0 && from.lerp(to, t).let { abs(it.x - 100.0) <= 1.0 && abs(it.y) <= 1.0 }
        check((hit != null) == expected) { "window crossing $from -> $to is ${hit != null}, expected $expected" }
    }
    val flat = Viewer(UUID.randomUUID(), eye, forward, ViewportSync.DEFAULT, range = 64.0, cylindrical = true)
    check(flat.reaches(Vec3(0.0, 40.0, 60.0), 64.0) && !viewer(eye, forward).reaches(Vec3(0.0, 40.0, 60.0), 64.0)) { "cylindrical fog range is off" }
    check(Viewer.probes(eye).size == 8 && Viewer.probes(eye).all { abs(abs(it.x) - Viewer.CAMERA_PROBE) < 1.0e-12 && abs(abs(it.y) - Viewer.CAMERA_PROBE) < 1.0e-12 }) { "camera probes differ from the client's" }
    println("windows: portal views, cylinder fog and camera probes agree")
}

fun main() {
    checkCone()
    checkRays()
    checkTurns()
    checkWindows()
    checkLead()
    checkNotice()
    checkMemory()
    checkQueries()
    println("gaze: ok")
}
