package xyz.thewhish.substratum.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeDeformation
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.client.model.geom.builders.PartDefinition
import net.minecraft.util.Mth
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.LivingEntity
import java.util.WeakHashMap

object Suit {
    const val WIDTH = 128
    const val HEIGHT = 64
    const val PACK = "pack"
    private const val LAMP = "lamp"
    const val FOREARM = "forearm"
    const val SHIN = "shin"
    private val FABRIC = CubeDeformation(0.25f)
    private const val JOINT_GROWTH = 0.22f
    private val JOINT = CubeDeformation(JOINT_GROWTH)
    private const val SLEEVE_SEGMENTS = 2
    private val GLOVE = CubeDeformation(0.4f)
    private val CUFF = CubeDeformation(0.6f, 0.2f, 0.6f)
    private val BOOT = CubeDeformation(0.4f, 0.3f, 0.4f)
    private val BELT = CubeDeformation(0f, 0.25f, 0f)
    private val LID = CubeDeformation(0.1f)
    private const val FILTER_PITCH = 0.35f
    private const val FILTER_YAW = 0.6f
    private const val PACK_Y = 0.75f
    private const val PACK_Z = 2.25f
    private const val STRIDE = 0.6662f
    private const val PACK_BOUNCE = 0.18f
    private const val PACK_ROLL = 0.02f
    private const val BREATH_RATE = 0.1f
    private const val BREATH = 0.08f
    private const val BREATH_WINDED = 0.18f
    private const val LIFT_PER_SPEED = 1f
    private const val LIFT_LIMIT = 0.5f
    private const val LEAN_PER_SPEED = 0.35f
    private const val LEAN_LIMIT = 0.07f
    private const val LAMP_STEP = 0.06f
    private const val TWIST_PER_DEGREE = 0.006f
    private const val TWIST_LIMIT = 0.06f
    private const val SWING_PER_SPEED = 1.5f
    private const val SWING_LIMIT = 0.2f
    private const val SWING_INWARD = 0.08f
    private const val LONGEST_STEP = 1f
    private const val LONGEST_GAP = 4f

    private val motions = WeakHashMap<LivingEntity, Motion>()

    @JvmStatic
    fun layer(): LayerDefinition {
        val mesh = MeshDefinition()
        val root = mesh.root
        head(root)
        body(root)
        arm(root, "right_arm", -5f, false)
        arm(root, "left_arm", 5f, true)
        leg(root, "right_leg", -1.9f, false)
        leg(root, "left_leg", 1.9f, true)
        for (name in listOf("hat", "jacket", "right_sleeve", "left_sleeve", "right_pants", "left_pants", "ear", "cloak")) {
            root.addOrReplaceChild(name, CubeListBuilder.create(), PartPose.ZERO)
        }
        return LayerDefinition.create(mesh, WIDTH, HEIGHT)
    }

    @JvmStatic
    fun animate(model: PlayerModel<*>, entity: LivingEntity, limbSwing: Float, limbSwingAmount: Float, ageInTicks: Float) {
        model.head.setRotation(model.head.xRot, model.head.yRot, 0f)
        model.rightArm.getChild(FOREARM).resetPose()
        model.leftArm.getChild(FOREARM).resetPose()
        model.rightLeg.getChild(SHIN).resetPose()
        model.leftLeg.getChild(SHIN).resetPose()
        if (entity is Figure) return rest(model, entity)
        val motion = motions.getOrPut(entity) { Motion(ageInTicks) }
        motion.follow(entity, ageInTicks)
        val step = limbSwing * STRIDE
        val breath = Mth.sin(ageInTicks * BREATH_RATE) * if (entity.isSprinting) BREATH_WINDED else BREATH
        val bounce = Mth.cos(step * 2) * PACK_BOUNCE * limbSwingAmount
        model.body.getChild(PACK).apply {
            setPos(0f, PACK_Y + bounce + breath + motion.lift.position, PACK_Z)
            setRotation(motion.lean.position, motion.twist.position, Mth.sin(step) * PACK_ROLL * limbSwingAmount)
        }
        model.body.getChild(LAMP).setRotation(motion.swing.position + Mth.cos(step * 2) * LAMP_STEP * limbSwingAmount, 0f, motion.sway.position)
        Drinking.pose(model, entity)
    }

    @JvmStatic
    fun holdInGlove(model: PlayerModel<*>, arm: HumanoidArm, pose: PoseStack) {
        val forearm = (if (arm == HumanoidArm.LEFT) model.leftArm else model.rightArm).getChild(FOREARM)
        forearm.translateAndRotate(pose)
        pose.translate(0f, -forearm.y / 16f, 0f)
        Drinking.wrist(model, arm, pose)
    }

    private fun rest(model: PlayerModel<*>, figure: Figure) {
        model.body.getChild(PACK).apply {
            setPos(0f, PACK_Y, PACK_Z)
            setRotation(0f, 0f, 0f)
        }
        model.body.getChild(LAMP).setRotation(0f, 0f, 0f)
        figure.pose(model)
    }

    private class Spring(private val stiffness: Float, private val damping: Float) {
        var position = 0f
        private var velocity = 0f

        fun pull(target: Float, time: Float) {
            velocity += ((target - position) * stiffness - velocity * damping) * time
            position += velocity * time
        }
    }

    private class Motion(private var age: Float) {
        val lift = Spring(0.7f, 0.9f)
        val lean = Spring(0.5f, 0.9f)
        val twist = Spring(0.45f, 0.8f)
        val swing = Spring(0.4f, 0.35f)
        val sway = Spring(0.4f, 0.35f)
        private val ahead = Spring(0.3f, 1.1f)
        private val aside = Spring(0.3f, 1.1f)

        fun follow(entity: LivingEntity, now: Float) {
            val gap = now - age
            age = now
            if (gap <= 0f || gap > LONGEST_GAP) return
            val yaw = entity.yBodyRot * Mth.DEG_TO_RAD
            val dx = (entity.x - entity.xo).toFloat()
            val dz = (entity.z - entity.zo).toFloat()
            val forward = dz * Mth.cos(yaw) - dx * Mth.sin(yaw)
            val sideways = dx * Mth.cos(yaw) + dz * Mth.sin(yaw)
            val rising = (entity.y - entity.yo).toFloat()
            val turn = Mth.wrapDegrees(entity.yBodyRot - entity.yBodyRotO)
            var left = gap
            while (left > 0f) {
                val time = minOf(left, LONGEST_STEP)
                ahead.pull(forward, time)
                aside.pull(sideways, time)
                val surge = forward - ahead.position
                val drift = sideways - aside.position
                lift.pull(Mth.clamp(rising * LIFT_PER_SPEED, -LIFT_LIMIT, LIFT_LIMIT), time)
                lean.pull(Mth.clamp(surge * LEAN_PER_SPEED, -LEAN_LIMIT, LEAN_LIMIT), time)
                twist.pull(Mth.clamp(-turn * TWIST_PER_DEGREE, -TWIST_LIMIT, TWIST_LIMIT), time)
                swing.pull(Mth.clamp(surge * SWING_PER_SPEED, -SWING_LIMIT, SWING_INWARD), time)
                sway.pull(Mth.clamp(drift * SWING_PER_SPEED, -SWING_LIMIT, SWING_LIMIT), time)
                left -= time
            }
        }
    }

    private fun head(root: PartDefinition) {
        val head = root.addOrReplaceChild(
            "head",
            CubeListBuilder.create()
                .texOffs(0, 0).addBox(-4f, -8f, -4f, 8f, 8f, 8f, FABRIC)
                .texOffs(48, 16).addBox(-3.5f, -7.5f, -5.25f, 7f, 1f, 1f)
                .texOffs(48, 18).addBox(-3.5f, -3.5f, -5.25f, 7f, 1f, 1f)
                .texOffs(60, 20).addBox(-3.5f, -6.5f, -5.25f, 1f, 3f, 1f)
                .texOffs(60, 20).mirror().addBox(2.5f, -6.5f, -5.25f, 1f, 3f, 1f).mirror(false)
                .texOffs(48, 20).addBox(-2.5f, -6.5f, -4.75f, 5f, 3f, 1f)
                .texOffs(58, 24).addBox(-1.5f, -3f, -5.75f, 3f, 2f, 2f),
            PartPose.ZERO,
        )
        for (side in listOf(-1f, 1f)) {
            head.addOrReplaceChild(
                if (side < 0) "right_filter" else "left_filter",
                CubeListBuilder.create().texOffs(68, 24).mirror(side > 0).addBox(-1f, -1f, -2f, 2f, 2f, 2f),
                PartPose.offsetAndRotation(2.75f * side, -1.75f, -4.25f, FILTER_PITCH, -FILTER_YAW * side, 0f),
            )
        }
    }

    private fun body(root: PartDefinition) {
        val body = root.addOrReplaceChild(
            "body",
            CubeListBuilder.create()
                .texOffs(32, 0).addBox(-4f, 0f, -2f, 8f, 12f, 4f, FABRIC)
                .texOffs(112, 24).addBox(-3.75f, -0.25f, -2.75f, 1f, 8f, 1f)
                .texOffs(112, 24).mirror().addBox(2.75f, -0.25f, -2.75f, 1f, 8f, 1f).mirror(false)
                .texOffs(46, 24).addBox(-3.75f, -0.75f, -2.75f, 1f, 1f, 5f)
                .texOffs(46, 24).mirror().addBox(2.75f, -0.75f, -2.75f, 1f, 1f, 5f).mirror(false)
                .texOffs(0, 24).addBox(-4.5f, 7.5f, -2.5f, 9f, 1f, 5f, BELT)
                .texOffs(94, 24).addBox(-2.5f, 7f, -3.25f, 2f, 2f, 1f)
                .texOffs(82, 24).addBox(-2.5f, 1.5f, -2.5f, 5f, 2f, 1f),
            PartPose.ZERO,
        )
        body.addOrReplaceChild(
            LAMP,
            CubeListBuilder.create()
                .texOffs(100, 24).addBox(-0.5f, 0f, -0.5f, 1f, 3f, 1f)
                .texOffs(100, 28).addBox(-1f, 2.75f, -1f, 2f, 1f, 2f),
            PartPose.offset(2.75f, 8.25f, -3f),
        )
        body.addOrReplaceChild(
            PACK,
            CubeListBuilder.create()
                .texOffs(64, 16).addBox(-3.5f, 0f, 0f, 7f, 3f, 3f, LID)
                .texOffs(88, 0).addBox(-3.5f, 0.5f, 0f, 7f, 8f, 3f)
                .texOffs(88, 16).addBox(-1.5f, -1f, 1f, 3f, 1f, 1f)
                .texOffs(96, 16).addBox(-4f, 8.5f, 0.5f, 8f, 2f, 2f)
                .texOffs(76, 24).addBox(-4.5f, 3f, 0.5f, 1f, 4f, 2f)
                .texOffs(76, 24).mirror().addBox(3.5f, 3f, 0.5f, 1f, 4f, 2f),
            PartPose.offset(0f, PACK_Y, PACK_Z),
        )
    }

    private fun arm(root: PartDefinition, name: String, x: Float, left: Boolean) {
        val inner = if (left) -1f else -3f
        root.addOrReplaceChild(
            name,
            CubeListBuilder.create().mirror(left).texOffs(56, 0).addBox(inner, -2f, -2f, 4f, 6f, 4f, FABRIC),
            PartPose.offset(x, 2f, 0f),
        ).addOrReplaceChild(
            FOREARM,
            CubeListBuilder.create().mirror(left)
                .texOffs(56, 5).addBox(inner, -1f, -2f, 4f, 7f, 4f, JOINT)
                .texOffs(0, 16).addBox(inner, 2f, -2f, 4f, 4f, 4f, GLOVE)
                .texOffs(32, 16).addBox(inner, 1.5f, -2f, 4f, 1f, 4f, CUFF),
            PartPose.offset(0f, 4f, 0f),
        )
    }

    fun sleeve(left: Boolean): ModelPart {
        val mesh = MeshDefinition()
        val cubes = CubeListBuilder.create().mirror(left).texOffs(56, 5)
        for (segment in 1..SLEEVE_SEGMENTS) {
            cubes.addBox(if (left) -1f else -3f, -1f - segment * (7f + 2 * JOINT_GROWTH), -2f, 4f, 7f, 4f, JOINT)
        }
        mesh.root.addOrReplaceChild(FOREARM, cubes, PartPose.offset(0f, 4f, 0f))
        return LayerDefinition.create(mesh, WIDTH, HEIGHT).bakeRoot().getChild(FOREARM)
    }

    private fun leg(root: PartDefinition, name: String, x: Float, left: Boolean) {
        root.addOrReplaceChild(
            name,
            CubeListBuilder.create().mirror(left).texOffs(72, 0).addBox(-2f, 0f, -2f, 4f, 6f, 4f, FABRIC),
            PartPose.offset(x, 12f, 0f),
        ).addOrReplaceChild(
            SHIN,
            CubeListBuilder.create().mirror(left)
                .texOffs(72, 5).addBox(-2f, -1f, -2f, 4f, 7f, 4f, JOINT)
                .texOffs(16, 16).addBox(-2f, 2f, -2f, 4f, 4f, 4f, BOOT)
                .texOffs(32, 16).addBox(-2f, 1.5f, -2f, 4f, 1f, 4f, CUFF),
            PartPose.offset(0f, 6f, 0f),
        )
    }
}
