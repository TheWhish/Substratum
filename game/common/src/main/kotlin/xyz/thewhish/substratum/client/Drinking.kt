package xyz.thewhish.substratum.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.ItemInHandRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.player.PlayerRenderer
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import xyz.thewhish.substratum.item.AlmondWaterItem
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Vector3f
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.exp

object Drinking {
    const val LOWER_AT = 35
    val GULPS = intArrayOf(11, 20, 29)
    private const val GULP_TICKS = 3
    private const val DRAIN_FROM = 4f
    private const val DRAIN_TICKS = 30f

    private const val LIFT_RATE = 0.7f
    private const val TIP_RATE = 0.9f
    private const val HEAD_RATE = 0.4f

    private const val TIP_REST = 0.15f
    private const val TIP_DRAIN = 0.7f
    private const val TIP_GULP = 0.15f
    private const val HEAD_GULP = 0.1f

    internal const val HEAD_PITCH = -0.22f
    private const val VIEW_PITCH = -12f

    private const val GULP_VOLUME = 0.45f
    private const val GULP_PITCH = 0.92f
    private const val GULP_PITCH_SPREAD = 0.12f

    private const val MODEL_SCALE = 0.9375f
    private const val HAND = 10f / 16f
    private const val GLOVE = 8f / 16f
    internal const val GRIP_SLIDE = 1.5f
    private const val PIXEL = 1f / 16f
    private const val SETTLED = 1e-3f

    internal class Joints(
        val x: Float, val y: Float, val z: Float,
        val bendX: Float, val bendY: Float, val bendZ: Float,
        val wristX: Float, val wristZ: Float, val reach: Float,
    )

    internal val SIP = Joints(-1.72f, 0.31f, 0.89f, -1.13f, -1.07f, 0.93f, -1.09f, -0.54f, 2.13f)
    internal val DRAIN = Joints(-2.03f, 0.51f, 0.89f, -0.93f, -1.03f, 0.91f, -0.99f, -0.34f, 2.02f)
    internal class View(val hand: Vector3f, val reach: Vector3f, val up: Vector3f)

    internal val VIEW_HOLD = View(Vector3f(0.46f, -0.42f, -0.82f), Vector3f(-0.15f, 0.45f, -0.88f), Vector3f(0.05f, 0.9f, 0.42f))
    internal val VIEW_SIP = View(Vector3f(0.14f, -0.17f, -0.74f), Vector3f(-0.92f, 0.3f, -0.2f), Vector3f(-0.28f, -0.22f, 0.93f))
    internal val VIEW_DRAIN = View(Vector3f(0.1f, -0.08f, -0.72f), Vector3f(-0.86f, 0.5f, 0f), Vector3f(-0.28f, -0.58f, 0.76f))
    private const val EQUIP_DROP = -0.6f
    private val sleeves by lazy { HumanoidArm.entries.associateWith { Suit.sleeve(it == HumanoidArm.LEFT) } }

    private class Wrist(val arm: HumanoidArm, val x: Float, val z: Float, val slide: Float)

    private val wrists = WeakHashMap<PlayerModel<*>, Wrist>()

    internal class Spring(private val rate: Float) {
        private var previous = 0f
        private var value = 0f
        private var velocity = 0f

        fun pull(target: Float) {
            previous = value
            val offset = value - target
            val decay = exp(-rate)
            val pace = velocity + rate * offset
            value = target + (offset + pace) * decay
            velocity = (velocity - rate * pace) * decay
        }

        fun at(partialTick: Float): Float = Mth.lerp(partialTick, previous, value)

        val settled: Boolean get() = abs(value) < SETTLED && abs(previous) < SETTLED && abs(velocity) < SETTLED
    }

    internal class Motion {
        val lift = Spring(LIFT_RATE)
        val tip = Spring(TIP_RATE)
        val head = Spring(HEAD_RATE)

        fun follow(ticks: Int) {
            if (ticks < 0 || ticks >= LOWER_AT) {
                lift.pull(0f)
                tip.pull(0f)
                head.pull(0f)
                return
            }
            val drained = Mth.smoothstep(((ticks - DRAIN_FROM) / DRAIN_TICKS).coerceIn(0f, 1f).toDouble()).toFloat()
            val gulp = if (GULPS.any { ticks - it in 0 until GULP_TICKS }) 1f else 0f
            lift.pull(1f)
            tip.pull(TIP_REST + TIP_DRAIN * drained + TIP_GULP * gulp)
            head.pull(drained + HEAD_GULP * gulp)
        }

        val settled: Boolean get() = lift.settled && tip.settled && head.settled
    }

    private val motions = WeakHashMap<LivingEntity, Motion>()

    fun drinking(entity: LivingEntity): Boolean = entity.isUsingItem && entity.useItem.item is AlmondWaterItem

    fun tick(level: ClientLevel) {
        for (player in level.players()) {
            val drinking = drinking(player)
            val motion = motions[player] ?: if (drinking) Motion().also { motions[player] = it } else continue
            val ticks = if (drinking) player.ticksUsingItem else -1
            motion.follow(ticks)
            if (ticks in GULPS) gulp(level, player)
            if (!drinking && motion.settled) motions.remove(player)
        }
    }

    private fun gulp(level: ClientLevel, player: AbstractClientPlayer) {
        val pitch = GULP_PITCH + level.random.nextFloat() * GULP_PITCH_SPREAD
        level.playLocalSound(player.x, player.eyeY, player.z, SoundEvents.GENERIC_DRINK, SoundSource.PLAYERS, GULP_VOLUME, pitch, false)
    }

    private fun partialTick(): Float = Minecraft.getInstance().timer.getGameTimeDeltaPartialTick(false)

    fun pose(model: PlayerModel<*>, entity: LivingEntity) {
        wrists.remove(model)
        val motion = motions[entity] ?: return
        val partial = partialTick()
        val lift = motion.lift.at(partial)
        if (lift <= 0f) return
        bend(model, entity.mainArm, null, lift, motion.tip.at(partial))
        model.head.xRot += HEAD_PITCH * motion.head.at(partial)
    }

    internal fun bend(model: PlayerModel<*>, side: HumanoidArm, rest: Joints?, lift: Float, tip: Float) {
        val mirror = if (side == HumanoidArm.RIGHT) 1f else -1f
        val arm = if (side == HumanoidArm.RIGHT) model.rightArm else model.leftArm
        val from = rest ?: Joints(arm.xRot, arm.yRot * mirror, arm.zRot * mirror, 0f, 0f, 0f, 0f, 0f, 0f)
        val drained = tip.coerceIn(0f, 1f)
        fun blend(pick: (Joints) -> Float) = Mth.lerp(lift, pick(from), Mth.lerp(drained, pick(SIP), pick(DRAIN)))
        place(model, side, Joints(
            blend { it.x }, blend { it.y }, blend { it.z },
            blend { it.bendX }, blend { it.bendY }, blend { it.bendZ },
            blend { it.wristX }, blend { it.wristZ }, blend { it.reach },
        ), GRIP_SLIDE * lift)
    }

    internal fun place(model: PlayerModel<*>, side: HumanoidArm, joints: Joints, slide: Float) {
        val mirror = if (side == HumanoidArm.RIGHT) 1f else -1f
        val arm = if (side == HumanoidArm.RIGHT) model.rightArm else model.leftArm
        arm.setRotation(joints.x, joints.y * mirror, joints.z * mirror)
        arm.getChild(Suit.FOREARM).setRotation(joints.bendX, joints.bendY * mirror, joints.bendZ * mirror)
        arm.z = -joints.reach
        wrists[model] = Wrist(side, joints.wristX, joints.wristZ * mirror, slide)
    }

    @JvmStatic
    fun wrist(model: PlayerModel<*>, arm: HumanoidArm, pose: PoseStack) {
        val wrist = wrists[model]?.takeIf { it.arm == arm } ?: return
        pose.translate(0f, HAND, 0f)
        pose.mulPose(Axis.ZP.rotation(wrist.z))
        pose.mulPose(Axis.XP.rotation(wrist.x))
        pose.translate(0f, 0f, wrist.slide * PIXEL)
        pose.translate(0f, -HAND, 0f)
    }

    @JvmStatic
    fun viewPitch(): Float {
        if (ClientConfig.motionSicknessSafe) return 0f
        val player = Minecraft.getInstance().player ?: return 0f
        return VIEW_PITCH * (motions[player]?.head?.at(partialTick()) ?: 0f)
    }

    internal fun grip(arm: HumanoidArm, pose: PoseStack) {
        pose.mulPose(Axis.XP.rotationDegrees(-90f))
        pose.mulPose(Axis.YP.rotationDegrees(180f))
        pose.translate((if (arm == HumanoidArm.LEFT) -1f else 1f) * PIXEL, 0.125f, -0.625f)
    }

    private fun frame(axis: Vector3f, side: Vector3f): Matrix3f {
        val across = Vector3f(axis).mul(-side.dot(axis)).add(side).normalize()
        return Matrix3f(across, axis, Vector3f(across).cross(axis))
    }

    internal fun viewFrame(pose: PoseStack, side: HumanoidArm, lift: Float, tip: Float) {
        val raised = lift.coerceIn(0f, 1f)
        val drained = tip.coerceIn(0f, 1f)
        val mirror = if (side == HumanoidArm.RIGHT) 1f else -1f
        fun pick(get: (View) -> Vector3f) =
            Vector3f(get(VIEW_SIP)).lerp(get(VIEW_DRAIN), drained).sub(get(VIEW_HOLD)).mul(raised).add(get(VIEW_HOLD)).mul(mirror, 1f, 1f)
        val hand = pick { it.hand }
        val along = pick { it.reach }.normalize()
        val turn = frame(along, pick { it.up }.cross(along))
        pose.translate(hand.x, hand.y, hand.z)
        pose.last().pose().mul(Matrix4f(turn))
        pose.last().normal().mul(turn)
        pose.scale(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE)
        pose.translate(mirror * PIXEL, -GLOVE, 0f)
    }

    @JvmStatic
    fun renderFirstPerson(
        player: AbstractClientPlayer, stack: ItemStack, equipProgress: Float, pose: PoseStack,
        buffers: MultiBufferSource, light: Int, hands: ItemInHandRenderer,
    ): Boolean {
        if (stack.item !is AlmondWaterItem) return false
        val renderer = Minecraft.getInstance().entityRenderDispatcher.getRenderer(player) as? PlayerRenderer ?: return false
        val side = player.mainArm
        val forearm = (if (side == HumanoidArm.RIGHT) renderer.model.rightArm else renderer.model.leftArm).getChild(Suit.FOREARM)
        val motion = motions[player]
        val partial = partialTick()
        val buffer = buffers.getBuffer(RenderType.entityTranslucent(player.skin.texture()))
        pose.pushPose()
        pose.translate(0f, EQUIP_DROP * equipProgress, 0f)
        viewFrame(pose, side, motion?.lift?.at(partial) ?: 0f, motion?.tip?.at(partial) ?: 0f)
        forearm.resetPose()
        forearm.render(pose, buffer, light, OverlayTexture.NO_OVERLAY)
        sleeves.getValue(side).render(pose, buffer, light, OverlayTexture.NO_OVERLAY)
        grip(side, pose)
        val right = side == HumanoidArm.RIGHT
        val context = if (right) ItemDisplayContext.THIRD_PERSON_RIGHT_HAND else ItemDisplayContext.THIRD_PERSON_LEFT_HAND
        hands.renderItem(player, stack, context, !right, pose, buffers, light)
        pose.popPose()
        return true
    }
}
