package xyz.thewhish.substratum.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.util.Mth
import java.util.Locale

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
        val numberInk = fade(distance, NUMBER_FADE, NUMBER_RANGE)
        if (numberInk == 0) return
        val body = parentModel.body
        poseStack.pushPose()
        body.translateAndRotate(poseStack)
        val nameInk = fade(distance, NAME_FADE, NAME_RANGE)
        if (nameInk != 0) {
            poseStack.pushPose()
            poseStack.translate(0f, NAME_Y / PIXELS, NAME_Z / PIXELS)
            val name = player.gameProfile.name.uppercase(Locale.ROOT)
            print(poseStack, buffer, packedLight, name, NAME_WIDTH, NAME_HEIGHT, nameInk or NAME_INK)
            poseStack.popPose()
        }
        body.getChild(Suit.PACK).translateAndRotate(poseStack)
        poseStack.translate(0f, NUMBER_Y / PIXELS, NUMBER_Z / PIXELS)
        poseStack.mulPose(Axis.YP.rotationDegrees(180f))
        val number = Lobby.numberOf(player.uuid).toString().padStart(2, '0')
        print(poseStack, buffer, packedLight, number, NUMBER_WIDTH, NUMBER_HEIGHT, numberInk or NUMBER_PAINT)
        poseStack.popPose()
    }

    private fun fade(distance: Float, from: Float, to: Float): Int {
        val alpha = Mth.clamp((to - distance) / (to - from), 0f, 1f)
        val byte = (alpha * 255f).toInt()
        return if (byte < LEAST_ALPHA) 0 else byte shl 24
    }

    private fun print(poseStack: PoseStack, buffer: MultiBufferSource, light: Int, text: String, width: Float, height: Float, color: Int) {
        val font = Minecraft.getInstance().font
        val textWidth = font.width(text).toFloat()
        val scale = minOf(height / GLYPH, width / textWidth) / PIXELS
        poseStack.pushPose()
        poseStack.scale(scale, scale, scale)
        font.drawInBatch(
            text, -textWidth / 2f, -GLYPH / 2f, color, false, poseStack.last().pose(), buffer,
            Font.DisplayMode.POLYGON_OFFSET, 0, light,
        )
        poseStack.popPose()
    }

    private companion object {
        const val PIXELS = 16f
        const val GLYPH = 7f
        const val LEAST_ALPHA = 8
        const val NAME_FADE = 3.5f
        const val NAME_RANGE = 6f
        const val NUMBER_FADE = 10f
        const val NUMBER_RANGE = 16f
        const val NAME_Y = 2.5f
        const val NAME_Z = -2.51f
        const val NAME_WIDTH = 4.2f
        const val NAME_HEIGHT = 1.1f
        const val NAME_INK = 0xD9D3C1
        const val NUMBER_Y = 5.75f
        const val NUMBER_Z = 3.01f
        const val NUMBER_WIDTH = 4.4f
        const val NUMBER_HEIGHT = 2.4f
        const val NUMBER_PAINT = 0xC2BBA2
    }
}
