package xyz.thewhish.substratum.rift

import net.minecraft.SharedConstants
import net.minecraft.client.renderer.block.model.BakedQuad
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.server.Bootstrap
import xyz.thewhish.substratum.rift.client.QuadClipper
import java.io.File
import kotlin.math.abs

private const val HITBOX = 0.6
private const val NEAR_PLANE = 0.05

fun main() {
    SharedConstants.tryDetectVersion()
    Bootstrap.bootStrap()

    checkWidths()
    checkYawGate()
    checkBrace()
    checkClipper()
    val scanned = checkNoCameraWrites()
    println("slit geometry ok: cut=${RiftCutBlock.CUT}, pass=${RiftCutBlock.PASS}; $scanned mod sources leave the camera alone.")
}

private fun checkClipper() {
    val face = quad(
        floatArrayOf(0f, 0f, 0f, 0f, 0f),
        floatArrayOf(0f, 1f, 0f, 0f, 16f),
        floatArrayOf(1f, 1f, 0f, 16f, 16f),
        floatArrayOf(1f, 0f, 0f, 16f, 0f)
    )

    val untouched = mutableListOf<BakedQuad>()
    QuadClipper.clip(face, Direction.Axis.X, 1f, keepLess = true, untouched)
    check(untouched.size == 1 && untouched[0] === face) { "a quad clear of the plane must not be rebuilt" }

    val dropped = mutableListOf<BakedQuad>()
    QuadClipper.clip(face, Direction.Axis.X, 0f, keepLess = false, dropped)
    check(dropped.size == 1) { "a quad flush with the plane is kept whole, got ${dropped.size}" }

    val low = mutableListOf<BakedQuad>()
    QuadClipper.clip(face, Direction.Axis.X, 0.4f, keepLess = true, low)
    check(low.size == 1) { "clipping a rectangle should leave a rectangle, got ${low.size} quads" }
    span(low[0], 0) { min, max -> check(near(min, 0f) && near(max, 0.4f)) { "low half spans $min..$max" } }
    span(low[0], 4) { min, max -> check(near(min, 0f) && near(max, 6.4f)) { "low half u spans $min..$max" } }

    val high = mutableListOf<BakedQuad>()
    QuadClipper.clip(face, Direction.Axis.X, 0.6f, keepLess = false, high)
    span(high[0], 0) { min, max -> check(near(min, 0.6f) && near(max, 1f)) { "high half spans $min..$max" } }
    span(high[0], 4) { min, max -> check(near(min, 9.6f) && near(max, 16f)) { "high half u spans $min..$max" } }

    val diamond = quad(
        floatArrayOf(0.5f, 0f, 0f, 8f, 0f),
        floatArrayOf(1f, 0.5f, 0f, 16f, 8f),
        floatArrayOf(0.5f, 1f, 0f, 8f, 16f),
        floatArrayOf(0f, 0.5f, 0f, 0f, 8f)
    )
    val triangle = mutableListOf<BakedQuad>()
    QuadClipper.clip(diamond, Direction.Axis.X, 0.4f, keepLess = true, triangle)
    check(triangle.size == 1) { "a three-vertex clip is one degenerate quad, got ${triangle.size}" }
    val pentagon = mutableListOf<BakedQuad>()
    QuadClipper.clip(diamond, Direction.Axis.X, 0.6f, keepLess = true, pentagon)
    check(pentagon.size == 2) { "a five-vertex clip is two quads, got ${pentagon.size}" }
    for (piece in pentagon) span(piece, 0) { _, max -> check(max <= 0.6f + 1e-4f) { "clip leaked past the plane to $max" } }

    val moved = QuadClipper.translate(face, Direction.Axis.Z, 0.6f)
    span(moved, 2) { min, max -> check(near(min, 0.6f) && near(max, 0.6f)) { "translate moved z to $min..$max" } }
    span(moved, 0) { min, max -> check(near(min, 0f) && near(max, 1f)) { "translate disturbed x: $min..$max" } }
}

@Suppress("TYPE_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
private fun quad(vararg corners: FloatArray): BakedQuad {
    val vertices = IntArray(4 * 8)
    for (i in 0 until 4) {
        val c = corners[i]
        for (k in 0 until 3) vertices[i * 8 + k] = c[k].toRawBits()
        vertices[i * 8 + 4] = c[3].toRawBits()
        vertices[i * 8 + 5] = c[4].toRawBits()
    }
    return BakedQuad(vertices, 0, Direction.NORTH, null, true)
}

private inline fun span(quad: BakedQuad, component: Int, body: (Float, Float) -> Unit) {
    var min = Float.MAX_VALUE
    var max = -Float.MAX_VALUE
    for (i in 0 until 4) {
        val value = Float.fromBits(quad.vertices[i * 8 + component])
        if (value < min) min = value
        if (value > max) max = value
    }
    body(min, max)
}

private fun near(a: Float, b: Float) = abs(a - b) < 1e-4f

private fun checkWidths() {
    check(RiftCutBlock.CUT < HITBOX) {
        "the drawn crack must be impassable to an unturned player, got ${RiftCutBlock.CUT}"
    }
    check(RiftCutBlock.PASS > HITBOX) {
        "a turned player must fit, got ${RiftCutBlock.PASS} against a $HITBOX hitbox"
    }

    val drift = (RiftCutBlock.PASS - HITBOX) / 2.0
    check(drift + NEAR_PLANE < RiftCutBlock.CUT / 2.0) {
        "camera leaves the drawn crack: drift $drift + near plane $NEAR_PLANE >= ${RiftCutBlock.CUT / 2.0}"
    }
}

private fun checkYawGate() {
    open(0f, Direction.Axis.X)
    open(180f, Direction.Axis.X)
    shut(90f, Direction.Axis.X)
    shut(-90f, Direction.Axis.X)

    open(90f, Direction.Axis.Z)
    open(-90f, Direction.Axis.Z)
    shut(0f, Direction.Axis.Z)
    shut(180f, Direction.Axis.Z)

    open(70f, Direction.Axis.Z)
    shut(50f, Direction.Axis.Z)

    open(90f + 720f, Direction.Axis.Z)
    open(-270f, Direction.Axis.Z)
}

private fun open(yaw: Float, axis: Direction.Axis) = check(RiftCutBlock.isSideways(yaw, axis)) {
    "yaw $yaw should pass a $axis slit"
}

private fun shut(yaw: Float, axis: Direction.Axis) = check(!RiftCutBlock.isSideways(yaw, axis)) {
    "yaw $yaw should be refused by a $axis slit"
}

private fun checkBrace() {
    check(Squeeze.across(80f, Direction.Axis.Z) == 90f) { "a body already near 90 should stay at 90" }
    check(Squeeze.across(-80f, Direction.Axis.Z) == -90f) { "a body already near -90 should stay at -90" }
    check(Squeeze.across(10f, Direction.Axis.X) == 0f) { "a body along X braces to 0 or 180" }
    check(Squeeze.across(170f, Direction.Axis.X) == -180f) { "a body along X braces to 0 or 180" }
    for (axis in Direction.Axis.entries) {
        if (axis.isVertical) continue
        for (yaw in -720..720 step 7) {
            val braced = Squeeze.across(yaw.toFloat(), axis)
            check(abs(Mth.wrapDegrees(braced - yaw.toFloat())) <= 90f + 1e-3f) {
                "bracing yaw $yaw on $axis swung the body ${braced - yaw} degrees"
            }
            val along = if (axis == Direction.Axis.X) -sin(braced) else cos(braced)
            check(abs(along) < 1e-5f) { "braced yaw $braced is not across $axis" }
        }
    }
}

private fun sin(yaw: Float) = Mth.sin(yaw * Mth.DEG_TO_RAD)

private fun cos(yaw: Float) = Mth.cos(yaw * Mth.DEG_TO_RAD)

private fun checkNoCameraWrites(): Int {
    val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle").isFile }
        ?: error("no repository root above ${File("").absolutePath}")
    val roots = listOf("common", "fabric", "neoforge").map { File(root, "$it/src/main") }
    for (dir in roots) check(dir.isDirectory) { "$dir is missing — the scan would pass by finding nothing" }

    var scanned = 0
    for (dir in roots) {
        for (file in dir.walkTopDown()) {
            if (!file.isFile || (file.extension != "kt" && file.extension != "java")) continue
            scanned++
            val text = file.readText()
            for (banned in CAMERA_WRITES) {
                val hit = banned.find(text) ?: continue
                val line = text.take(hit.range.first).lines().size
                error("${file.name}:$line writes the player's view (`${hit.value.trim()}`) — TS §2.2 forbids it")
            }
        }
    }
    check(scanned > 0) { "found no mod sources to scan under $root" }
    return scanned
}

private val CAMERA_WRITES = listOf(
    Regex("""\.set[XY]Rot\("""),
    Regex("""\.setYHeadRot\("""),
    Regex("""\.[xy]Rot\s*=(?!=)"""),
    Regex("""\.[xy]RotO\s*=(?!=)"""),
    Regex("""\.absRotateTo\("""),
    Regex("""\.lookAt\(""")
)
