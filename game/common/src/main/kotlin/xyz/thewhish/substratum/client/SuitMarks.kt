package xyz.thewhish.substratum.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.util.Mth

class SuitMarks(
    parent: RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>>,
) : RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>>(parent) {

    override fun render(
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int,
        player: AbstractClientPlayer,
        limbSwing: Float,
        limbSwingAmount: Float,
        partialTick: Float,
        ageInTicks: Float,
        netHeadYaw: Float,
        headPitch: Float,
    ) {
        if (player.isInvisible) return
        val distance = Math.sqrt(
            Minecraft.getInstance().gameRenderer.mainCamera.position.distanceToSqr(player.getPosition(partialTick)),
        ).toFloat()
        val alpha = Mth.clamp((RANGE - distance) / (RANGE - FADE), 0f, 1f)
        val byte = (alpha * 255f).toInt()
        if (byte < LEAST_ALPHA) return
        val label = Labels.of(Lobby.numberOf(player.uuid).toString().padStart(2, '0')) ?: return
        val color = byte shl 24 or PAINT
        poseStack.pushPose()
        parentModel.body.translateAndRotate(poseStack)
        parentModel.body.getChild(Suit.PACK).translateAndRotate(poseStack)
        poseStack.translate(0f, Y / PIXELS, Z / PIXELS)
        poseStack.mulPose(Axis.YP.rotationDegrees(180f))
        val scale = minOf(HEIGHT / GLYPH, WIDTH / label.span) / PIXELS
        poseStack.scale(scale, scale, scale)
        val pose = poseStack.last().pose()
        val left = -label.span / 2f - Labels.PAD
        val top = -GLYPH / 2f - Labels.PAD
        val right = left + label.width
        val bottom = top + Labels.CELL + 2 * Labels.PAD
        val ink = buffer.getBuffer(RenderType.textPolygonOffset(label.location))
        ink.addVertex(pose, left, top, 0f).setColor(color).setUv(0f, 1f).setLight(packedLight)
        ink.addVertex(pose, left, bottom, 0f).setColor(color).setUv(0f, 0f).setLight(packedLight)
        ink.addVertex(pose, right, bottom, 0f).setColor(color).setUv(1f, 0f).setLight(packedLight)
        ink.addVertex(pose, right, top, 0f).setColor(color).setUv(1f, 1f).setLight(packedLight)
        poseStack.popPose()
    }

    private companion object {
        const val PIXELS = 16f
        const val GLYPH = 7f
        const val LEAST_ALPHA = 8
        const val FADE = 10f
        const val RANGE = 16f
        const val Y = 5.75f
        const val Z = 3.01f
        const val WIDTH = 4.4f
        const val HEIGHT = 2.4f
        const val PAINT = 0xC2BBA2
    }
}
