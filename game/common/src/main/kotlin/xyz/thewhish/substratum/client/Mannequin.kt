package xyz.thewhish.substratum.client

import com.mojang.authlib.GameProfile
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.RemotePlayer
import net.minecraft.client.resources.PlayerSkin
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import java.util.UUID

class Mannequin(level: ClientLevel, private val mirrored: Boolean) : RemotePlayer(level, GameProfile(UUID.randomUUID(), NAME)) {

    override fun getSkin(): PlayerSkin = LocalSkin.of(gameProfile) ?: super.getSkin()

    override fun getScale(): Float = SIZE

    fun slump(poseStack: PoseStack) {
        poseStack.translate(0f, HIP - DROP, 0f)
        poseStack.mulPose(Axis.XP.rotation(LEAN))
        poseStack.mulPose(Axis.ZP.rotation(side * ROLL))
        poseStack.translate(0f, -HIP, 0f)
    }

    fun pose(model: PlayerModel<*>) {
        model.head.setRotation(0.8f + LEAN, side * 0.25f, side * 0.3f)
        bend(if (mirrored) model.leftArm else model.rightArm, Suit.FOREARM, LEAN - 0.05f, side * 0.05f, side * 0.25f, -0.55f)
        bend(if (mirrored) model.rightArm else model.leftArm, Suit.FOREARM, LEAN - 0.6f, side * 0.35f, side * -0.05f, -0.8f)
        bend(if (mirrored) model.leftLeg else model.rightLeg, Suit.SHIN, LEAN - 1.95f, side * 0.25f, side * 0.12f, 1.25f)
        bend(if (mirrored) model.rightLeg else model.leftLeg, Suit.SHIN, LEAN - 1.52f, side * -0.35f, side * -0.1f, 0.2f)
    }

    private val side: Float get() = if (mirrored) -1f else 1f

    private fun bend(limb: ModelPart, joint: String, x: Float, y: Float, z: Float, angle: Float) {
        limb.setRotation(x, y, z)
        limb.getChild(joint).setRotation(angle, 0f, 0f)
    }

    companion object {
        private const val NAME = "suit"
        private const val SIZE = 1.5f
        private const val HIP = 0.75f
        private const val DROP = 0.61f
        private const val LEAN = 0.3f
        private const val ROLL = 0.06f
        private const val WALL_GAP = 0.53
        private const val ID = -0x5017

        private var placed: Mannequin? = null

        fun place(level: ClientLevel, wall: BlockPos, face: Direction, mirrored: Boolean): Mannequin {
            clear()
            var floor = wall.relative(face)
            while (floor.y > level.minBuildHeight && level.getBlockState(floor.below()).getCollisionShape(level, floor.below()).isEmpty) floor = floor.below()
            return Mannequin(level, mirrored).apply {
                val x = floor.x + 0.5 - face.stepX * (0.5 - WALL_GAP * SIZE)
                val z = floor.z + 0.5 - face.stepZ * (0.5 - WALL_GAP * SIZE)
                moveTo(x, floor.y.toDouble(), z, face.toYRot(), 0f)
                yHeadRot = face.toYRot()
                yBodyRot = face.toYRot()
                id = ID
                level.addEntity(this)
                placed = this
            }
        }

        fun placed(): Mannequin? = placed?.takeIf { !it.isRemoved }

        fun clear() {
            placed?.discard()
            placed = null
        }
    }
}
