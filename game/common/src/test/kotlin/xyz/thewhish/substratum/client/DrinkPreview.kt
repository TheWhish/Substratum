package xyz.thewhish.substratum.client

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.renderer.block.model.ItemTransform
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.LivingEntity
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

internal class Quad(val points: List<Vector3f>, val color: Color)

internal class Collector(private val texture: BufferedImage) : VertexConsumer {
    private val corners = ArrayList<Vector3f>()
    private var u = 0f
    private var v = 0f
    val quads = ArrayList<Quad>()

    override fun addVertex(x: Float, y: Float, z: Float, color: Int, u: Float, v: Float, overlay: Int, light: Int, nx: Float, ny: Float, nz: Float) {
        corners += Vector3f(x, y, z)
        this.u += u
        this.v += v
        if (corners.size < 4) return
        quads += Quad(ArrayList(corners), sample(texture, this.u / 4f, this.v / 4f))
        corners.clear()
        this.u = 0f
        this.v = 0f
    }

    override fun addVertex(x: Float, y: Float, z: Float): VertexConsumer = error("unpacked path")
    override fun setColor(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer = error("unpacked path")
    override fun setUv(u: Float, v: Float): VertexConsumer = error("unpacked path")
    override fun setUv1(u: Int, v: Int): VertexConsumer = error("unpacked path")
    override fun setUv2(u: Int, v: Int): VertexConsumer = error("unpacked path")
    override fun setNormal(x: Float, y: Float, z: Float): VertexConsumer = error("unpacked path")
}

private fun sample(texture: BufferedImage, u: Float, v: Float): Color {
    val x = (u * texture.width).toInt().coerceIn(0, texture.width - 1)
    val y = (v * texture.height).toInt().coerceIn(0, texture.height - 1)
    return Color(texture.getRGB(x, y), true)
}

internal fun image(path: String): BufferedImage =
    ImageIO.read(Suit::class.java.classLoader.getResourceAsStream(path) ?: error("missing $path"))

internal object Bottle {
    private val json = JsonParser.parseString(
        Suit::class.java.classLoader.getResourceAsStream("assets/substratum/models/item/almond_water.json")!!.reader().readText()
    ).asJsonObject
    private val texture = image("assets/substratum/textures/item/almond_water.png")
    private val hand: ItemTransform = json.getAsJsonObject("display").getAsJsonObject("thirdperson_righthand").let { display ->
        fun vector(name: String, default: Float, scale: Float = 1f) = display.getAsJsonArray(name)?.let {
            Vector3f(it[0].asFloat * scale, it[1].asFloat * scale, it[2].asFloat * scale)
        } ?: Vector3f(default)
        ItemTransform(vector("rotation", 0f), vector("translation", 0f, 1f / 16f), vector("scale", 1f))
    }

    private val heights = json.getAsJsonArray("elements").flatMap { element ->
        listOf(element.asJsonObject.getAsJsonArray("from")[1].asFloat, element.asJsonObject.getAsJsonArray("to")[1].asFloat)
    }

    fun ends(pose: PoseStack): Pair<Vector3f, Vector3f> {
        pose.pushPose()
        hand.apply(false, pose)
        pose.translate(-0.5f, -0.5f, -0.5f)
        val matrix = pose.last().pose()
        val neck = matrix.transformPosition(Vector3f(0.5f, heights.max() / 16f, 0.5f))
        val base = matrix.transformPosition(Vector3f(0.5f, heights.min() / 16f, 0.5f))
        pose.popPose()
        return neck to base
    }

    fun quads(pose: PoseStack, into: MutableList<Quad>) {
        pose.pushPose()
        hand.apply(false, pose)
        pose.translate(-0.5f, -0.5f, -0.5f)
        val matrix = pose.last().pose()
        for (element in json.getAsJsonArray("elements")) element(element.asJsonObject, matrix, into)
        pose.popPose()
    }

    private fun element(element: JsonObject, matrix: Matrix4f, into: MutableList<Quad>) {
        val from = element.getAsJsonArray("from").map { it.asFloat / 16f }
        val to = element.getAsJsonArray("to").map { it.asFloat / 16f }
        val rotation = element.getAsJsonObject("rotation")
        val turn = Matrix4f()
        if (rotation != null) {
            val origin = rotation.getAsJsonArray("origin").map { it.asFloat / 16f }
            val angle = Math.toRadians(rotation["angle"].asDouble).toFloat()
            val axis = when (rotation["axis"].asString) { "x" -> Vector3f(1f, 0f, 0f); "y" -> Vector3f(0f, 1f, 0f); else -> Vector3f(0f, 0f, 1f) }
            turn.translate(origin[0], origin[1], origin[2]).rotate(Quaternionf().rotateAxis(angle, axis)).translate(-origin[0], -origin[1], -origin[2])
        }
        val (x0, y0, z0) = from
        val (x1, y1, z1) = to
        val faces = mapOf(
            "north" to listOf(Vector3f(x1, y1, z0), Vector3f(x0, y1, z0), Vector3f(x0, y0, z0), Vector3f(x1, y0, z0)),
            "south" to listOf(Vector3f(x0, y1, z1), Vector3f(x1, y1, z1), Vector3f(x1, y0, z1), Vector3f(x0, y0, z1)),
            "west" to listOf(Vector3f(x0, y1, z0), Vector3f(x0, y1, z1), Vector3f(x0, y0, z1), Vector3f(x0, y0, z0)),
            "east" to listOf(Vector3f(x1, y1, z1), Vector3f(x1, y1, z0), Vector3f(x1, y0, z0), Vector3f(x1, y0, z1)),
            "up" to listOf(Vector3f(x0, y1, z0), Vector3f(x1, y1, z0), Vector3f(x1, y1, z1), Vector3f(x0, y1, z1)),
            "down" to listOf(Vector3f(x0, y0, z1), Vector3f(x1, y0, z1), Vector3f(x1, y0, z0), Vector3f(x0, y0, z0)),
        )
        val described = element.getAsJsonObject("faces")
        for ((name, corners) in faces) {
            val face = described.getAsJsonObject(name) ?: continue
            val uv = face.getAsJsonArray("uv").map { it.asFloat / 16f }
            val color = sample(texture, (uv[0] + uv[2]) / 2f, (uv[1] + uv[3]) / 2f)
            if (color.alpha == 0) continue
            into += Quad(corners.map { matrix.transformPosition(turn.transformPosition(Vector3f(it))) }, color)
        }
    }
}

private class Scene(time: Int) {
    val lift: Float
    val tip: Float
    val head: Float

    init {
        val motion = Drinking.Motion()
        for (tick in 1..time) motion.follow(tick)
        lift = motion.lift.at(1f)
        tip = motion.tip.at(1f)
        head = motion.head.at(1f)
    }
}

private val skin = image("assets/substratum/textures/skin/yellow.png")

private fun model(): PlayerModel<LivingEntity> = PlayerModel(Suit.layer().bakeRoot(), false)

private fun thirdPerson(scene: Scene): Triple<List<Quad>, Vector3f, Vector3f> {
    val root = Suit.layer().bakeRoot()
    val model = PlayerModel<LivingEntity>(root, false)
    model.rightArm.xRot = THIRD_PERSON_REST
    Drinking.bend(model, HumanoidArm.RIGHT, null, scene.lift, scene.tip)
    model.head.xRot = Drinking.HEAD_PITCH * scene.head
    val collector = Collector(skin)
    root.render(PoseStack(), collector, 0, 0)
    val pose = PoseStack()
    held(model, pose)
    val quads = ArrayList(collector.quads)
    Bottle.quads(pose, quads)
    val mouth = PoseStack().apply { model.head.translateAndRotate(this) }.last().pose().transformPosition(Vector3f(0f, -2f / 16f, -6f / 16f))
    return Triple(quads, mouth, Bottle.ends(held(model, PoseStack())).first)
}

private fun firstPerson(scene: Scene): List<Quad> {
    val model = model()
    val pose = PoseStack()
    Drinking.viewFrame(pose, HumanoidArm.RIGHT, scene.lift, scene.tip)
    val collector = Collector(skin)
    model.rightArm.getChild(Suit.FOREARM).render(pose, collector, 0, 0)
    Suit.sleeve(false).render(pose, collector, 0, 0)
    val quads = ArrayList(collector.quads)
    Drinking.grip(HumanoidArm.RIGHT, pose)
    Bottle.quads(pose, quads)
    return quads
}

private fun emptyHand(): List<Quad> {
    val model = model()
    val pose = PoseStack()
    pose.translate(0.64f, -0.6f, -0.72f)
    pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(45f))
    pose.translate(-1f, 3.6f, 3.5f)
    pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(120f))
    pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(200f))
    pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-135f))
    pose.translate(5.6f, 0f, 0f)
    val collector = Collector(skin)
    model.rightArm.render(pose, collector, 0, 0)
    return collector.quads
}

private const val THIRD_PERSON_REST = -0.314f
private const val CELL = 300
private const val WIDE = 320
private const val FIRST_HEIGHT = 180
private const val ZOOM = 180f
private const val FOV = 70.0

internal fun paint(image: BufferedImage, quads: List<Quad>, project: (Vector3f) -> Pair<Float, Float>?, depth: (Vector3f) -> Float) {
    val buffer = FloatArray(image.width * image.height) { Float.MAX_VALUE }
    val drawn = quads.mapNotNull { quad ->
        val points = quad.points.map(project)
        if (points.any { it == null }) null else Triple(quad, points.filterNotNull(), quad.points.map(depth))
    }
    val (opaque, glass) = drawn.partition { it.first.color.alpha == 255 }
    for (pass in listOf(opaque, glass.sortedByDescending { it.third.average() })) for ((quad, points, depths) in pass) {
        val a = quad.points[0]
        val normal = Vector3f(quad.points[1]).sub(a).cross(Vector3f(quad.points[2]).sub(a))
        val light = if (normal.length() == 0f) 1f else 0.55f + 0.45f * abs(normal.normalize().dot(Vector3f(0.3f, -0.8f, -0.5f).normalize()))
        for (triangle in listOf(intArrayOf(0, 1, 2), intArrayOf(0, 2, 3))) {
            fill(image, buffer, triangle.map { points[it] }, triangle.map { depths[it] }, quad.color, light, quad.color.alpha == 255)
        }
    }
}

private fun fill(image: BufferedImage, buffer: FloatArray, points: List<Pair<Float, Float>>, depths: List<Float>, color: Color, light: Float, opaque: Boolean) {
    val (x0, y0) = points[0]
    val (x1, y1) = points[1]
    val (x2, y2) = points[2]
    val area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
    if (abs(area) < 1e-6f) return
    val left = maxOf(0, minOf(x0, x1, x2).toInt())
    val right = minOf(image.width - 1, maxOf(x0, x1, x2).toInt() + 1)
    val top = maxOf(0, minOf(y0, y1, y2).toInt())
    val bottom = minOf(image.height - 1, maxOf(y0, y1, y2).toInt() + 1)
    val alpha = color.alpha / 255f
    for (y in top..bottom) for (x in left..right) {
        val px = x + 0.5f
        val py = y + 0.5f
        val w0 = ((x1 - px) * (y2 - py) - (x2 - px) * (y1 - py)) / area
        val w1 = ((x2 - px) * (y0 - py) - (x0 - px) * (y2 - py)) / area
        val w2 = 1f - w0 - w1
        if (w0 < 0f || w1 < 0f || w2 < 0f) continue
        val z = w0 * depths[0] + w1 * depths[1] + w2 * depths[2]
        val index = y * image.width + x
        if (z >= buffer[index]) continue
        if (opaque) buffer[index] = z
        val under = Color(image.getRGB(x, y))
        fun mix(top: Int, below: Int) = (top * light * alpha + below * (1f - alpha)).toInt().coerceIn(0, 255)
        image.setRGB(x, y, Color(mix(color.red, under.red), mix(color.green, under.green), mix(color.blue, under.blue)).rgb)
    }
}

internal fun view(angle: Double): Pair<Vector3f, Vector3f> {
    val forward = Vector3f(sin(angle).toFloat(), 0f, cos(angle).toFloat())
    val right = Vector3f(forward).cross(Vector3f(0f, -1f, 0f))
    return forward to right
}

fun main(args: Array<String>) {
    val times = listOf(0, 3, 6, 11, 14, 20, 29, 34, 38, 42)
    val output = BufferedImage(WIDE * times.size, CELL * 2 + FIRST_HEIGHT + 40, BufferedImage.TYPE_INT_RGB)
    val graphics = output.createGraphics()
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    graphics.color = Color(0x3A3420)
    graphics.fillRect(0, 0, output.width, output.height)
    for ((column, time) in times.withIndex()) {
        val scene = Scene(time)
        val (quads, mouth, neck) = thirdPerson(scene)
        for ((row, angle) in listOf(Math.toRadians(35.0), Math.toRadians(90.0)).withIndex()) {
            val (forward, right) = view(angle)
            val left = column * WIDE + WIDE / 2f
            val top = row * CELL + 20 + ZOOM / 2
            val project = { p: Vector3f -> (left + p.dot(right) * ZOOM) to (top + p.y * ZOOM) }
            paint(output, quads, project) { it.dot(forward) }
            val (mx, my) = project(mouth)
            graphics.color = Color.RED
            graphics.fillOval(mx.toInt() - 3, my.toInt() - 3, 6, 6)
            val (nx, ny) = project(neck)
            graphics.color = Color.CYAN
            graphics.fillOval(nx.toInt() - 3, ny.toInt() - 3, 6, 6)
        }
        val focal = (FIRST_HEIGHT / 2f) / tan(Math.toRadians(FOV / 2)).toFloat()
        val left = column * WIDE + WIDE / 2f
        val middle = CELL * 2 + FIRST_HEIGHT / 2f
        graphics.color = Color(0x6A5D3A)
        graphics.fillRect(column * WIDE, CELL * 2, WIDE, FIRST_HEIGHT)
        paint(output, firstPerson(scene), { p -> if (p.z > -0.03f) null else (left + p.x / -p.z * focal) to (middle - p.y / -p.z * focal) }) { -it.z }
        graphics.color = Color.WHITE
        graphics.stroke = BasicStroke(1f)
        graphics.drawRect(column * WIDE, CELL * 2, WIDE - 1, FIRST_HEIGHT - 1)
        graphics.fillRect(left.toInt() - 1, middle.toInt() - 1, 2, 2)
        graphics.drawString("t=$time lift=%.2f tip=%.2f head=%.2f".format(scene.lift, scene.tip, scene.head), column * WIDE + 6, CELL * 2 + FIRST_HEIGHT + 20)
    }
    val empty = BufferedImage(WIDE, FIRST_HEIGHT, BufferedImage.TYPE_INT_RGB)
    empty.createGraphics().apply { color = Color(0x6A5D3A); fillRect(0, 0, WIDE, FIRST_HEIGHT) }
    val emptyFocal = (FIRST_HEIGHT / 2f) / tan(Math.toRadians(FOV / 2)).toFloat()
    paint(empty, emptyHand(), { p -> if (p.z > -0.03f) null else (WIDE / 2f + p.x / -p.z * emptyFocal) to (FIRST_HEIGHT / 2f - p.y / -p.z * emptyFocal) }) { -it.z }
    ImageIO.write(empty, "png", File(args[0].replace(".png", "_empty.png")))
    ImageIO.write(output, "png", File(args[0]))
    println("drink preview: ${args[0]}")
}


internal fun held(model: PlayerModel<*>, pose: PoseStack): PoseStack {
    model.translateToHand(HumanoidArm.RIGHT, pose)
    Suit.holdInGlove(model, HumanoidArm.RIGHT, pose)
    Drinking.grip(HumanoidArm.RIGHT, pose)
    return pose
}
