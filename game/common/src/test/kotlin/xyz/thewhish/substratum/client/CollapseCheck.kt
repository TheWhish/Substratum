package xyz.thewhish.substratum.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.model.PlayerModel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import xyz.thewhish.substratum.level.Footprint
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

private const val PIXEL = 1f / 16f
private val PARTS = listOf("head", "body", "right_arm", "left_arm", "right_leg", "left_leg")
private val FRAMES = listOf(0, 10, 20, 30, 40, 46, 52, 56, 60, 63, 66, 70, 76, 82, Collapse.END)
private const val ZOOM = 80f
private const val REST_ZOOM = 300f
private const val STEP = 0.25f
private const val CALM_UNTIL = 44f
private const val SINKING_FROM = 40f
private const val SWAY_LIMIT = 1.1f
private const val WALL = 0.2
private const val REACH_SLACK = 0.03
private const val LOW_BEFORE_IMPACT = 10

private val suitSkin = image("assets/substratum/textures/skin/yellow.png")

private class Body(val parts: Map<String, List<Quad>>) {
    val quads = parts.values.flatten()
    fun lowest(part: String) = parts.getValue(part).minOf { quad -> quad.points.minOf { it.y } } / PIXEL
}

private fun body(t: Float, side: Float): Body {
    val root = Suit.layer().bakeRoot()
    val model = PlayerModel<LivingEntity>(root, false)
    Collapse.place(model, Collapse.targets(t, side), 1f)
    val pose = PoseStack()
    pose.mulPose(Collapse.rig(Matrix4f(), t, side))
    pose.scale(-Collapse.MODEL_SCALE, -Collapse.MODEL_SCALE, Collapse.MODEL_SCALE)
    pose.translate(0f, -Collapse.MODEL_OFFSET, 0f)
    return Body(PARTS.associateWith { name ->
        Collector(suitSkin).also { root.getChild(name).render(pose, it, 0, 0) }.quads
    })
}

private fun sightStartsWhereTheEyesAre() {
    for (pitch in listOf(-0.9f, 0f, 0.6f)) for (turn in listOf(-0.7f, 0f, 0.5f)) {
        val (shift, rotation) = Collapse.sight(0f, 1f, 37f, pitch, turn)
        check(shift.length() < 1e-4f) { "the fall must start at the eyes, moved ${shift.length()} at pitch $pitch turn $turn" }
        check(abs(abs(rotation.w) - 1f) < 1e-4f) { "the fall must start looking where the player looks, turned $rotation" }
    }
}

private fun bodyNeverSinks() {
    for (side in listOf(1f, -1f)) for (tick in 0..Collapse.END * 2) {
        val t = tick / 2f
        val lowest = body(t, side).quads.minOf { quad -> quad.points.minOf { it.y } } / PIXEL
        check(lowest > -0.6f) { "the body sinks ${-lowest} px into the floor at t=$t side=$side" }
    }
}

private fun corpseRestsOnTheFloor(): Body {
    val corpse = body(Collapse.END.toFloat(), 1f)
    for (part in PARTS) println("  %-9s lowest %.2f px".format(part, corpse.lowest(part)))
    for (part in PARTS) check(corpse.lowest(part) in -0.6f..1.2f) { "$part floats or sinks at rest: ${corpse.lowest(part)} px" }
    return corpse
}

private fun hitboxCoversTheCorpse(corpse: Body) {
    val points = corpse.quads.flatMap { it.points }
    val front = -points.minOf { it.z }
    val back = points.maxOf { it.z }
    val width = points.maxOf { abs(it.x) }
    val height = points.maxOf { it.y }
    val legs = points.filter { it.z < Footprint.LEGS_REACH }.maxOf { it.y }
    println("  corpse spans front %.2f back %.2f half-width %.2f height %.2f, legs %.2f".format(front, back, width, height, legs))
    check(front <= Footprint.FRONT && back <= Footprint.BACK) { "the hitbox must cover the corpse lengthwise: $front..$back" }
    check(width <= Footprint.HALF_WIDTH && height <= Footprint.HEIGHT && legs <= Footprint.LEGS_HEIGHT) { "the hitbox must cover the corpse: $width wide, $height tall, legs $legs" }
    check(Footprint.BACK - back < 0.1 && Footprint.HALF_WIDTH - width < 0.1 && Footprint.HEIGHT - height < 0.1 && Footprint.LEGS_HEIGHT - legs < 0.1) { "the hitbox must hug the corpse" }
}

private fun fallIsSmooth() {
    fun sight(t: Float) = Collapse.sight(t, 1f, 0f, 0.3f, 0.2f)
    var swaySpeed = 0f
    var t = 0f
    while (t < Collapse.IMPACT) {
        val (shift, rotation) = sight(t)
        val (nextShift, next) = sight(t + STEP)
        val turn = Math.toDegrees(Quaternionf(rotation).conjugate().mul(next).angle().toDouble()).toFloat() / STEP
        if (t < CALM_UNTIL) swaySpeed = maxOf(swaySpeed, turn)
        if (t >= SINKING_FROM) check(nextShift.y < shift.y) { "the fall must not stall at t=$t" }
        t += STEP
    }
    println("  sway turns the view at most %.2f deg per tick".format(swaySpeed))
    check(swaySpeed < SWAY_LIMIT) { "the sway turns the view too fast: $swaySpeed deg per tick" }
}

private fun cameraPath() {
    for (t in FRAMES) {
        val (shift, rotation) = Collapse.sight(t.toFloat(), 1f, 0f, 0f, 0f)
        val look = rotation.transform(Vector3f(0f, 0f, 1f))
        println("  t=%2d eye %+.2f %+.2f %+.2f look %+.2f %+.2f %+.2f".format(t, shift.x, 1.62f + shift.y, shift.z, look.x, look.y, look.z))
    }
}

private fun draw(file: File, frames: List<Int>, zoom: Float) {
    val views = listOf<Triple<(Vector3f) -> Pair<Float, Float>, (Vector3f) -> Float, Float>>(
        Triple({ p -> (p.z + 0.4f) to (2.1f - p.y) }, { p -> p.x }, 2.3f),
        Triple({ p -> (p.x * 0.8f + p.z * 0.6f + 0.7f) to (2.3f - p.y - 0.35f * (p.z * 0.8f - p.x * 0.6f)) }, { p -> p.x * 0.6f - p.z * 0.8f }, 2.6f),
        Triple({ p -> (p.x + 1.1f) to (p.z + 0.4f) }, { p -> -p.y }, 2.5f),
    )
    val width = (2.6f * zoom).toInt()
    val rows = views.map { (it.third * zoom).toInt() }
    val image = BufferedImage(width * frames.size, rows.sum(), BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    graphics.color = Color(0x3A3420)
    graphics.fillRect(0, 0, image.width, image.height)
    for ((column, t) in frames.withIndex()) {
        val corpse = body(t.toFloat(), 1f)
        var top = 0
        for ((row, view) in views.withIndex()) {
            val left = column * width
            if (row < 2) {
                graphics.color = Color(0x5A5030)
                val floor = top + (view.first(Vector3f()).second * zoom).toInt()
                graphics.drawLine(left, floor, left + width, floor)
            }
            paint(image, corpse.quads, { p -> view.first(p).let { (x, y) -> (left + x * zoom) to (top + y * zoom) } }, view.second)
            top += rows[row]
        }
        graphics.color = Color.WHITE
        graphics.drawString("t=$t", column * width + 6, 14)
    }
    ImageIO.write(image, "png", file)
}

private fun fallClearsWalls() {
    val origin = Vec3(0.5, 0.0, 0.5)
    val rooms = mapOf<String, (Vec3) -> Boolean>(
        "open floor" to { _ -> false },
        "wall behind" to { p -> p.z < WALL },
        "wall to the left" to { p -> p.x > 1.0 - WALL },
        "corner behind" to { p -> p.z < WALL || p.x < WALL },
        "corridor end" to { p -> p.z < WALL || p.x < WALL - 1.0 || p.x > 2.0 - WALL },
    )
    for ((room, solid) in rooms) for (facing in listOf(0f, 35f, 90f)) for (side in listOf(1f, -1f)) {
        val landing = Footprint.landing(origin, facing, solid)
        val yaw = landing.yaw
        val at = origin.add(landing.shift)
        if (room == "open floor") check(yaw == facing && at == origin) { "on open floor the body must fall straight back" }
        val place = Matrix4f().translate(at.x.toFloat(), 0f, at.z.toFloat()).rotateY((180f - yaw) * Mth.DEG_TO_RAD)
        for (t in Collapse.IMPACT - LOW_BEFORE_IMPACT..Collapse.END) {
            val inside = body(t.toFloat(), side).quads.flatMap { it.points }
                .map { place.transformPosition(Vector3f(it)) }
                .firstOrNull { solid(Vec3(it.x.toDouble() + if (it.x > at.x) -REACH_SLACK else REACH_SLACK, it.y.toDouble(), it.z.toDouble() + if (it.z > at.z) -REACH_SLACK else REACH_SLACK)) }
            check(inside == null) { "the body enters the wall in '$room' facing $facing (fell at $yaw, moved ${landing.shift}) at t=$t: $inside" }
        }
    }
}

fun main(args: Array<String>) {
    sightStartsWhereTheEyesAre()
    fallIsSmooth()
    cameraPath()
    val file = File(args.firstOrNull() ?: "collapse.png")
    draw(file, FRAMES, ZOOM)
    draw(File(file.parentFile, "collapse_rest.png"), listOf(Collapse.END), REST_ZOOM)
    val corpse = corpseRestsOnTheFloor()
    hitboxCoversTheCorpse(corpse)
    bodyNeverSinks()
    fallClearsWalls()
    println("collapse: the fall starts at the eyes and ends on the floor")
}

