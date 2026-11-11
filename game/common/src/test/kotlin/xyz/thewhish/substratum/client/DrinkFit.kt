package xyz.thewhish.substratum.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.model.PlayerModel
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.LivingEntity
import org.joml.Vector3f
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

private val LOW = floatArrayOf(-3f, -1.5f, -0.6f, -2.8f, -2.2f, -2.2f, -2.2f, -2.2f, 0f)
private val HIGH = floatArrayOf(0.3f, 1.5f, 1.5f, 0.3f, 2.2f, 2.2f, 2.2f, 2.2f, 3.5f)
private val HOLD_TO = floatArrayOf(6f, 6f, 6f, 6f, 6f, 6f, 0f, 0f, 2f)
private val DESIGN = floatArrayOf(-1.05f, 0f, 0.45f, -1.75f, 0f, 0f, 0f, 0f, 2f)
private val YAW = Math.toRadians(12.0)
private val MOUTH = Vector3f(0f, -1.5f, -6.2f).mul(1f / 16f)

private val fitModel: PlayerModel<LivingEntity> = PlayerModel(Suit.layer().bakeRoot(), false)

private fun joints(values: FloatArray) = Drinking.Joints(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7], values[8])

private fun inside(head: PoseStack.Pose, point: Vector3f, margin: Float): Float {
    val local = head.pose().invert(org.joml.Matrix4f()).transformPosition(Vector3f(point)).mul(16f)
    val dx = max(0f, max(-4.5f - margin - local.x, local.x - 4.5f - margin))
    val dy = max(0f, max(-8.5f - margin - local.y, local.y - 0.5f - margin))
    val dz = max(0f, max(-6f - margin - local.z, local.z - 4.5f - margin))
    return if (dx == 0f && dy == 0f && dz == 0f) 1f else 0f
}

private var debug = false

private fun score(values: FloatArray, head: Float, tilt: Double, anchor: FloatArray?): Float {
    val model = fitModel
    model.head.resetPose()
    model.head.xRot = Drinking.HEAD_PITCH * head
    Drinking.place(model, HumanoidArm.RIGHT, joints(values), Drinking.GRIP_SLIDE)
    val headPose = PoseStack().apply { model.head.translateAndRotate(this) }.last()
    val mouth = headPose.pose().transformPosition(Vector3f(MOUTH))
    val wanted = headPose.pose().transformDirection(Vector3f(-sin(YAW).toFloat() * cos(tilt).toFloat(), -sin(tilt).toFloat(), -cos(YAW).toFloat() * cos(tilt).toFloat())).normalize()
    val (neck, base) = Bottle.ends(held(model, PoseStack()))
    val axis = Vector3f(base).sub(neck).normalize()
    val middle = Vector3f(neck).add(base).mul(0.5f)
    val armPose = PoseStack().apply { model.rightArm.translateAndRotate(this) }.last().pose()
    val elbow = armPose.transformPosition(Vector3f(0f, 4f / 16f, 0f)).mul(16f)
    val hand = PoseStack().apply { model.translateToHand(HumanoidArm.RIGHT, this); Suit.holdInGlove(model, HumanoidArm.RIGHT, this) }.last().pose().transformPosition(Vector3f(0f, 10f / 16f, 0f)).mul(16f)
    val fist = PoseStack().apply { model.translateToHand(HumanoidArm.RIGHT, this); Suit.holdInGlove(model, HumanoidArm.RIGHT, this) }.last().pose().transformPosition(Vector3f(-1f / 16f, 8f / 16f, 0f))
    if (debug) println("gap=%.2f px fist-mouth=%.2f fist-neck=%.2f ".format(neck.distance(mouth) * 16f, fist.distance(mouth) * 16f, fist.distance(neck) * 16f) + "neck=${neck.mul(16f, Vector3f())} base=${base.mul(16f, Vector3f())} mouth=${mouth.mul(16f, Vector3f())} axis=$axis wanted=$wanted elbow=$elbow hand=$hand inside=${inside(headPose, base, 0.3f)},${inside(headPose, middle, 0.3f)},${inside(headPose, Vector3f(hand).mul(1f / 16f), 0.6f)}")
    var total = neck.distanceSquared(mouth) * 256f * 3f + 500f * inside(headPose, neck, -0.25f)
    total += (1f - axis.dot(wanted)) * 300f
    total += 1000f * (inside(headPose, base, 0.3f) + inside(headPose, middle, 0.3f) + inside(headPose, Vector3f(hand).mul(1f / 16f), 0.6f))
    total += 8f * (outside(elbow.x, -9f, -3f) + outside(hand.y - elbow.y, -12f, -3f))
    total += 500f * if (abs(elbow.x) < 4.6f && elbow.y in -0.5f..12.5f && abs(elbow.z) < 2.7f) 1f else 0f
    total += 1f * values[8] * values[8]
    total += 3f * max(0f, hand.x + 0.5f).let { it * it }
    total += 0.3f * values.sumOf { (it * it).toDouble() }.toFloat() + 0.7f * (values[6] * values[6] + values[7] * values[7])
    if (anchor != null) total += values.indices.sumOf { (HOLD_TO[it] * (values[it] - anchor[it]) * (values[it] - anchor[it])).toDouble() }.toFloat()
    if (debug) println("terms: gap=%.1f axis=%.1f head=%.1f elbow=%.1f torso=%.1f reach=%.1f cross=%.1f joints=%.1f".format(neck.distanceSquared(mouth) * 768f, (1f - axis.dot(wanted)) * 300f, 1000f * (inside(headPose, base, 0.3f) + inside(headPose, middle, 0.3f) + inside(headPose, Vector3f(hand).mul(1f / 16f), 0.6f)) + 500f * inside(headPose, neck, -0.25f), 8f * (outside(elbow.x, -9f, -3f) + outside(hand.y - elbow.y, -12f, -3f)), 0f, 3f * values[8] * values[8], 3f * max(0f, hand.x + 0.5f).let { it * it }, 0.3f * values.sumOf { (it * it).toDouble() }.toFloat() + 0.7f * (values[6] * values[6] + values[7] * values[7])))
    return total
}

private fun fit(head: Float, tilt: Double, anchor: FloatArray?, seed: Int, gapWeight: Float = 1f): FloatArray {
    val random = Random(seed)
    val starts = List(80_000) { FloatArray(9) { LOW[it] + random.nextFloat() * (HIGH[it] - LOW[it]) } }
        .plus(listOfNotNull(anchor?.copyOf()))
        .sortedBy { score(it, head, tilt, anchor) }
        .take(24)
    val best = starts.map { refine(it, head, tilt, anchor) }.minBy { score(it, head, tilt, anchor) }
    println("score %.2f".format(score(best, head, tilt, anchor)))
    return best
}

private fun refine(start: FloatArray, head: Float, tilt: Double, anchor: FloatArray?): FloatArray {
    var best = start
    var bestScore = score(best, head, tilt, anchor)
    var step = 0.2f
    while (step > 0.002f) {
        var improved = false
        for (index in best.indices) for (direction in floatArrayOf(-step, step)) {
            val trial = best.copyOf().also { it[index] = (it[index] + direction).coerceIn(LOW[index], HIGH[index]) }
            val trialScore = score(trial, head, tilt, anchor)
            if (trialScore < bestScore) {
                best = trial
                bestScore = trialScore
                improved = true
            }
        }
        if (!improved) step /= 2f
    }
    return best
}

private fun describe(name: String, values: FloatArray) =
    println("internal val $name = Joints(${values.joinToString(", ") { "%.2ff".format(java.util.Locale.ROOT, it) }})")

fun main() {
    val sip = fit(head = 0f, tilt = Math.toRadians(25.0), anchor = DESIGN, seed = 7)
    val drain = fit(head = 1f, tilt = Math.toRadians(48.0), anchor = sip, seed = 11)
    debug = true
    score(sip, 0f, Math.toRadians(25.0), null)
    score(drain, 1f, Math.toRadians(48.0), null)
    debug = false
    describe("SIP", sip)
    describe("DRAIN", drain)
}

private fun outside(value: Float, low: Float, high: Float): Float {
    val miss = max(0f, max(low - value, value - high))
    return miss * miss
}
