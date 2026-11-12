package xyz.thewhish.substratum.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import xyz.thewhish.substratum.level.Footprint
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.sign
import kotlin.math.sin

object Collapse {
    const val IMPACT = 66
    const val SETTLE = 30
    private const val GRACE = 10
    const val END = IMPACT + SETTLE
    const val TICKS = END + GRACE
    private const val SLIP = IMPACT - 14
    private const val DIM_FROM = IMPACT + 8f
    private const val LINGER = 60
    private const val BLEND = 16f
    private const val HEAD_TURN = 40f
    private const val BODY_TURN = 0.1f
    private const val CALM = 0.35f
    private const val WALL_GAP = 0.12
    private const val HAND_LAG = 5f
    private const val SLIDE = 36f

    private const val TIP_FROM = 20f
    private const val TIP_CURVE = 3.5f
    private const val SWAY_RISE = 24f
    private const val SWAY_FADE = 4f
    private const val ROLL = 0.035f
    private const val ROLL_PERIOD = 44f
    private const val NOD = 0.018f
    private const val NOD_PERIOD = 37f
    private const val NOD_PHASE = 0.6f
    internal const val TOPPLE = 1.405f
    private const val BOUNCE = 0.03f
    private const val BOUNCE_TICKS = 9f
    private const val SETTLE_RATE = 5f
    private const val FLAIL_SPEED = 1.6f
    private const val HEEL = 2.4f / 16f * 0.9375f
    private const val FOOT = 4.4f / 16f * 0.9375f
    private const val LIMB_LAG = 6f
    private const val HEAD_LOLL = 0.035f
    private const val ARM_SWING = 0.05f

    internal const val MODEL_SCALE = 0.9375f
    internal const val MODEL_OFFSET = 1.501f
    private const val EYE_HEIGHT = 1.62f
    private val EYE = Vector3f(0f, EYE_HEIGHT / MODEL_SCALE - MODEL_OFFSET, 0f).negate()

    private const val THUD_VOLUME = 0.9f
    private const val THUD_PITCH = 0.8f
    private const val GEAR_VOLUME = 0.55f
    private const val GEAR_PITCH = 0.75f
    private const val SLIP_VOLUME = 0.35f
    private const val SLIP_PITCH = 0.7f

    private const val HEAD = 0
    private const val RIGHT_ARM = 3
    private const val LEFT_ARM = 8
    private const val RIGHT_LEG = 13
    private const val LEFT_LEG = 18
    private const val ANGLES = 23

    private val STAND = floatArrayOf(
        0f, 0f, 0f,
        0f, 0f, 0.05f, -0.05f, 0f,
        0f, 0f, -0.05f, -0.05f, 0f,
        0f, 0f, 0f, 0f, 0f,
        0f, 0f, 0f, 0f, 0f,
    )
    private val BUCKLE = floatArrayOf(
        0.12f, 0.05f, 0.05f,
        0.05f, 0f, 0.12f, -0.15f, 0f,
        0.02f, 0f, -0.1f, -0.1f, 0f,
        -0.1f, 0.02f, 0.02f, 0.18f, 0f,
        -0.06f, -0.02f, -0.02f, 0.1f, 0f,
    )
    private val FLAIL = floatArrayOf(
        0.3f, 0.1f, 0.05f,
        -0.7f, 0.1f, 0.35f, -0.35f, 0f,
        -0.55f, -0.05f, -0.3f, -0.3f, 0f,
        -0.25f, 0.03f, 0.05f, 0.3f, 0f,
        -0.15f, -0.02f, -0.04f, 0.15f, 0f,
    )
    internal val REST = floatArrayOf(
        -0.25f, 0.38f, 0.08f,
        0.39f, 0f, 0.3f, -0.3f, 0.1f,
        0.31f, 0f, -0.22f, -0.12f, 0f,
        -0.07f, 0.25f, 0.1f, 0.08f, 0f,
        -0.055f, -0.2f, -0.07f, 0.05f, 0f,
    )

    private class Fall(val yaw: Float, val shift: Vec3) {
        var age = 0
        var body = Float.NaN
        var bodyO = Float.NaN
    }

    private val falls = HashMap<UUID, Fall>()

    fun follow(id: UUID, landing: Footprint.Landing?) {
        if (landing == null) falls.remove(id) else falls[id] = Fall(landing.yaw, landing.shift)
    }

    private fun slide(player: Player, t: Float): Vec3 = falls[player.uuid]?.shift?.scale(smooth(t / SLIDE).toDouble()) ?: Vec3.ZERO

    fun reset() = falls.clear()

    @JvmStatic
    fun frozen(): Boolean = Minecraft.getInstance().player?.let(::falling) == true || Feed.dark

    fun freeze(minecraft: Minecraft) {
        if (!frozen()) return
        KeyMapping.releaseAll()
        if (minecraft.screen is ChatScreen) minecraft.setScreen(null)
    }

    fun tick(level: ClientLevel) {
        falls.values.removeIf { ++it.age > END + LINGER }
        for (player in level.players()) {
            val fall = falls[player.uuid] ?: continue
            if (player.isSpectator) continue
            if (fall.body.isNaN()) fall.body = player.yBodyRot
            fall.bodyO = fall.body
            fall.body = Mth.rotLerp(BODY_TURN, fall.body, fall.yaw)
            when (fall.age) {
                SLIP -> play(level, player, SoundEvents.ARMOR_EQUIP_LEATHER.value(), SLIP_VOLUME, SLIP_PITCH)
                IMPACT -> {
                    play(level, player, SoundEvents.PLAYER_BIG_FALL, THUD_VOLUME, THUD_PITCH)
                    play(level, player, SoundEvents.ARMOR_EQUIP_GENERIC.value(), GEAR_VOLUME, GEAR_PITCH)
                }
            }
        }
    }

    private fun play(level: ClientLevel, player: Player, sound: SoundEvent, volume: Float, pitch: Float) =
        level.playLocalSound(player.x, player.y, player.z, sound, SoundSource.PLAYERS, volume, pitch, false)

    private fun time(entity: Entity, partialTick: Float): Float? {
        if (entity !is Player || entity.isSpectator) return null
        val fall = falls[entity.uuid] ?: return null
        return minOf(fall.age + partialTick, END.toFloat())
    }

    @JvmStatic
    fun falling(entity: Entity): Boolean = time(entity, 0f) != null

    fun ended(entity: Entity): Boolean = (time(entity, 0f) ?: 0f) >= END

    fun fade(entity: Entity, partialTick: Float): Float = time(entity, partialTick)?.let { smooth((it - DIM_FROM) / (END - DIM_FROM)) } ?: 0f

    private fun bodyYaw(player: Player, partialTick: Float): Float {
        val fall = falls[player.uuid]?.takeIf { !it.bodyO.isNaN() } ?: return Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot)
        return Mth.rotLerp(partialTick, fall.bodyO, fall.body)
    }

    private fun drift(player: Player, partialTick: Float): Float =
        Mth.wrapDegrees(Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot) - bodyYaw(player, partialTick))

    internal fun side(id: UUID): Float = if (id.leastSignificantBits and 1L == 0L) 1f else -1f

    private fun smooth(value: Float): Float = Mth.clamp(value, 0f, 1f).let { it * it * (3f - 2f * it) }

    private fun wave(t: Float, period: Float, phase: Float = 0f): Float = sin(2f * PI.toFloat() * t / period + phase)

    private fun tip(t: Float): Float {
        val progress = Mth.clamp((t - TIP_FROM) / (IMPACT - TIP_FROM), 0f, 1f)
        return (cosh(TIP_CURVE * progress) - 1f) / (cosh(TIP_CURVE) - 1f)
    }

    private fun sway(t: Float): Float = smooth(t / SWAY_RISE) * (1f - smooth(tip(t) * SWAY_FADE))

    private fun tilt(t: Float, calm: Float): Float {
        if (t <= IMPACT) return TOPPLE * tip(t) + calm * NOD * sway(t) * wave(t, NOD_PERIOD, NOD_PHASE)
        val bounce = Mth.clamp((t - IMPACT) / BOUNCE_TICKS, 0f, 1f)
        return TOPPLE - BOUNCE * sin(PI.toFloat() * bounce)
    }

    internal fun rig(matrix: Matrix4f, t: Float, side: Float, calm: Float = 1f): Matrix4f {
        val roll = side * calm * ROLL * sway(t) * wave(t, ROLL_PERIOD)
        val pivot = -sign(roll) * FOOT
        return matrix
            .translate(0f, 0f, HEEL).rotateX(tilt(t, calm)).translate(0f, 0f, -HEEL)
            .translate(pivot, 0f, 0f).rotateZ(roll).translate(-pivot, 0f, 0f)
    }

    internal fun targets(t: Float, side: Float, calm: Float = 1f): FloatArray {
        val pose = if (t <= IMPACT) {
            blend(blend(STAND, BUCKLE, smooth(t / SWAY_RISE)), FLAIL, smooth(tip(t) * FLAIL_SPEED)).also { loll(it, t, calm) }
        } else {
            blend(FLAIL, REST, settle((t - IMPACT) / SETTLE))
        }
        return if (side > 0f) pose else mirror(pose)
    }

    private fun loll(pose: FloatArray, t: Float, calm: Float) {
        val amount = sway(t) * calm
        val swing = wave(t - LIMB_LAG, ROLL_PERIOD) * amount
        pose[HEAD] += HEAD_LOLL * wave(t - LIMB_LAG, NOD_PERIOD) * amount
        pose[HEAD + 2] += HEAD_LOLL * swing
        pose[RIGHT_ARM + 2] += ARM_SWING * swing
        pose[LEFT_ARM + 2] += ARM_SWING * swing
    }

    private fun settle(progress: Float): Float {
        fun rise(u: Float) = 1f - (1f + SETTLE_RATE * u) * exp(-SETTLE_RATE * u)
        return rise(Mth.clamp(progress, 0f, 1f)) / rise(1f)
    }

    private fun blend(from: FloatArray, to: FloatArray, amount: Float) = FloatArray(ANGLES) { Mth.lerp(amount, from[it], to[it]) }

    private fun mirror(pose: FloatArray): FloatArray {
        val mirrored = pose.copyOf()
        for (limb in listOf(RIGHT_ARM to LEFT_ARM, RIGHT_LEG to LEFT_LEG)) {
            pose.copyInto(mirrored, limb.second, limb.first, limb.first + 5)
            pose.copyInto(mirrored, limb.first, limb.second, limb.second + 5)
        }
        for (index in listOf(HEAD + 1, HEAD + 2) + listOf(RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG).flatMap { listOf(it + 1, it + 2, it + 4) }) {
            mirrored[index] = -mirrored[index]
        }
        return mirrored
    }

    internal fun place(model: PlayerModel<*>, pose: FloatArray, weight: Float, headTurn: Float = weight) {
        fun set(part: ModelPart, at: Int) {
            part.xRot = Mth.lerp(if (at == HEAD) headTurn else weight, part.xRot, pose[at])
            part.yRot = Mth.lerp(if (at == HEAD) headTurn else weight, part.yRot, pose[at + 1])
            part.zRot = Mth.lerp(weight, part.zRot, pose[at + 2])
        }
        fun bend(part: ModelPart, at: Int) {
            part.xRot = Mth.lerp(weight, part.xRot, pose[at + 3])
            part.zRot = Mth.lerp(weight, part.zRot, pose[at + 4])
        }
        set(model.head, HEAD)
        set(model.rightArm, RIGHT_ARM)
        set(model.leftArm, LEFT_ARM)
        set(model.rightLeg, RIGHT_LEG)
        set(model.leftLeg, LEFT_LEG)
        bend(model.rightArm.getChild(Suit.FOREARM), RIGHT_ARM)
        bend(model.leftArm.getChild(Suit.FOREARM), LEFT_ARM)
        bend(model.rightLeg.getChild(Suit.SHIN), RIGHT_LEG)
        bend(model.leftLeg.getChild(Suit.SHIN), LEFT_LEG)
    }

    fun pose(model: PlayerModel<*>, entity: LivingEntity) {
        val player = entity as? Player ?: return
        val partialTick = partialTick()
        val t = time(player, partialTick) ?: return
        model.head.yRot += drift(player, partialTick) * Mth.DEG_TO_RAD
        place(model, targets(t, side(player.uuid)), smooth(t / BLEND), smooth(t / HEAD_TURN))
    }

    @JvmStatic
    fun slump(entity: Entity, pose: PoseStack, partialTick: Float) {
        val player = entity as? Player ?: return
        val t = time(player, partialTick) ?: return
        pose.mulPose(Axis.YP.rotationDegrees(drift(player, partialTick)))
        val slide = slide(player, t)
        val local = Vector3f(slide.x.toFloat(), 0f, slide.z.toFloat()).rotateY(-(180f - bodyYaw(player, partialTick)) * Mth.DEG_TO_RAD)
        pose.translate(local.x, local.y, local.z)
        pose.mulPose(rig(Matrix4f(), t, side(player.uuid)))
    }

    fun lie(pose: PoseStack, side: Float) {
        pose.mulPose(rig(Matrix4f(), END.toFloat(), side))
    }

    fun rest(model: PlayerModel<*>, side: Float) = place(model, targets(END.toFloat(), side), 1f)

    @JvmStatic
    fun aim(entity: Entity, partialTick: Float, eye: Vec3, rotation: Quaternionf): Vec3? {
        val player = entity as? Player ?: return null
        val t = time(player, partialTick) ?: return null
        val (shift, turnBy) = sight(player, partialTick, t)
        rotation.premul(turnBy)
        val target = eye.add(shift.x.toDouble(), shift.y.toDouble(), shift.z.toDouble()).add(slide(player, t))
        val hit = player.level().clip(ClipContext(eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        if (hit.type == HitResult.Type.MISS) return target
        return hit.location.add(eye.subtract(hit.location).normalize().scale(WALL_GAP))
    }

    @JvmStatic
    fun hands(pose: PoseStack, partialTick: Float) {
        val player = Minecraft.getInstance().cameraEntity as? Player ?: return
        val t = time(player, partialTick) ?: return
        val view = upright(player, partialTick)
        val now = sight(player, partialTick, t).second
        val before = sight(player, partialTick, maxOf(0f, t - HAND_LAG)).second
        pose.mulPose(Quaternionf(view).conjugate().mul(now.conjugate()).mul(before).mul(view))
    }

    private fun sight(player: Player, partialTick: Float, t: Float): Pair<Vector3f, Quaternionf> {
        val bodyYaw = bodyYaw(player, partialTick)
        val pitch = player.getViewXRot(partialTick) * Mth.DEG_TO_RAD
        val turn = Mth.wrapDegrees(player.getViewYRot(partialTick) - bodyYaw) * Mth.DEG_TO_RAD
        return sight(t, side(player.uuid), bodyYaw, pitch, turn, if (ClientConfig.motionSicknessSafe) CALM else 1f)
    }

    private fun upright(player: Player, partialTick: Float): Quaternionf =
        Quaternionf().rotationYXZ(PI.toFloat() - player.getViewYRot(partialTick) * Mth.DEG_TO_RAD, -player.getViewXRot(partialTick) * Mth.DEG_TO_RAD, 0f)

    internal fun sight(t: Float, side: Float, bodyYaw: Float, pitch: Float, turn: Float, calm: Float = 1f): Pair<Vector3f, Quaternionf> {
        val weight = smooth(t / BLEND)
        val pose = targets(t, side, calm)
        val now = head(bodyYaw, rig(Matrix4f(), t, side, calm), Mth.lerp(smooth(t / HEAD_TURN), pitch, pose[HEAD]), Mth.lerp(smooth(t / HEAD_TURN), turn, pose[HEAD + 1]), weight * pose[HEAD + 2])
        val still = head(bodyYaw, Matrix4f(), pitch, turn, 0f)
        val shift = now.transformPosition(Vector3f(EYE)).sub(still.transformPosition(Vector3f(EYE)))
        return shift to orientation(now).mul(orientation(still).conjugate())
    }

    fun look(player: Player, partialTick: Float): Pair<Vec3, Quaternionf>? {
        val rotation = upright(player, partialTick)
        val eye = aim(player, partialTick, player.getEyePosition(partialTick), rotation) ?: return null
        return eye to rotation
    }

    internal fun head(bodyYaw: Float, rig: Matrix4f, x: Float, y: Float, z: Float): Matrix4f =
        Matrix4f().rotateY((180f - bodyYaw) * Mth.DEG_TO_RAD).mul(rig)
            .scale(-MODEL_SCALE, -MODEL_SCALE, MODEL_SCALE).translate(0f, -MODEL_OFFSET, 0f)
            .rotate(Quaternionf().rotationZYX(z, y, x))

    private fun orientation(frame: Matrix4f): Quaternionf {
        val forward = frame.transformDirection(Vector3f(0f, 0f, -1f)).normalize()
        val up = frame.transformDirection(Vector3f(0f, -1f, 0f)).normalize()
        val right = Vector3f(forward).cross(up).normalize()
        val back = Vector3f(forward).negate()
        return Quaternionf().setFromNormalized(Matrix3f(right, Vector3f(back).cross(right), back))
    }

    private fun partialTick(): Float = Minecraft.getInstance().timer.getGameTimeDeltaPartialTick(false)
}
