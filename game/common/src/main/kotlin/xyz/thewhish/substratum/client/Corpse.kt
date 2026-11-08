package xyz.thewhish.substratum.client

import com.mojang.authlib.GameProfile
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.util.Mth
import java.util.UUID

class Corpse(level: ClientLevel, owner: UUID?) : Figure(level, GameProfile(owner ?: UUID.randomUUID(), NAME)) {

    private var fall = 0f

    override fun isSpectator(): Boolean = false

    override fun isCreative(): Boolean = false

    fun follow(age: Float, yaw: Float) {
        val progress = Mth.clamp(age / FALL_TICKS, 0f, 1f)
        fall = progress * progress
        yBodyRot = yaw
        yBodyRotO = yaw
        yHeadRot = yaw
        yHeadRotO = yaw
    }

    override fun slump(poseStack: PoseStack) {
        poseStack.translate(0f, LIFT * fall, 0f)
        poseStack.mulPose(Axis.XP.rotation(-Mth.HALF_PI * fall))
    }

    override fun pose(model: PlayerModel<*>) {
        model.head.setRotation(-0.15f * fall, HEAD_TURN * fall, 0f)
        model.rightArm.setRotation(0.1f * fall, 0f, ARM_SPREAD * fall)
        model.leftArm.setRotation(-0.05f * fall, 0f, -ARM_SPREAD * 0.7f * fall)
        model.rightArm.getChild(Suit.FOREARM).setRotation(-0.35f * fall, 0f, 0f)
        model.leftArm.getChild(Suit.FOREARM).setRotation(-0.15f * fall, 0f, 0f)
        model.rightLeg.setRotation(0.05f * fall, 0f, LEG_SPREAD * fall)
        model.leftLeg.setRotation(-0.08f * fall, 0f, -LEG_SPREAD * fall)
        model.rightLeg.getChild(Suit.SHIN).setRotation(0.25f * fall, 0f, 0f)
        model.leftLeg.getChild(Suit.SHIN).setRotation(0.1f * fall, 0f, 0f)
    }

    companion object {
        private const val NAME = "suit"
        private const val FALL_TICKS = 18f
        private const val LIFT = 0.17f
        private const val HEAD_TURN = 1.1f
        private const val ARM_SPREAD = 0.25f
        private const val LEG_SPREAD = 0.1f
    }
}
