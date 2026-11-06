package xyz.thewhish.substratum.smiler.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.math.Axis
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import qouteall.imm_ptl.core.render.context_management.PortalRendering
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.gaze.Gaze
import xyz.thewhish.substratum.smiler.Smiler

class SmilerRenderer(context: EntityRendererProvider.Context) : EntityRenderer<Smiler>(context) {

    override fun getTextureLocation(entity: Smiler): ResourceLocation = TEXTURE

    override fun render(entity: Smiler, yaw: Float, partialTick: Float, poseStack: PoseStack, buffers: MultiBufferSource, light: Int) {
        if (entity.vanished) return
        entity.drawnFrame = Vanish.frame
        if (PortalRendering.isRendering()) entity.portalFrame = Vanish.frame
        val time = entity.tickCount + partialTick
        val phase = entity.id * PHASE_SPREAD
        poseStack.pushPose()
        poseStack.translate(0.0, Smiler.FACE_CENTRE + BOB * Mth.sin(time * BOB_SPEED + phase), 0.0)
        poseStack.mulPose(Axis.YP.rotationDegrees(SWAY * Mth.sin(time * SWAY_SPEED + phase) - Mth.rotLerp(partialTick, entity.yRotO, entity.yRot)))
        poseStack.mulPose(Axis.ZP.rotationDegrees(TILT * Mth.sin(time * TILT_SPEED + phase * 1.7f)))
        val u = frame(entity) * FRAME_U
        quad(buffers.getBuffer(FACE), poseStack.last(), u, GLOW)
        quad(buffers.getBuffer(FACE_DEPTH), poseStack.last(), u, DEPTH_CUTOFF)
        poseStack.popPose()
    }

    private fun quad(consumer: VertexConsumer, pose: PoseStack.Pose, u: Float, colour: Int) {
        vertex(consumer, pose, -HALF, -HALF, u, 1f, colour)
        vertex(consumer, pose, HALF, -HALF, u + FRAME_U, 1f, colour)
        vertex(consumer, pose, HALF, HALF, u + FRAME_U, 0f, colour)
        vertex(consumer, pose, -HALF, HALF, u, 0f, colour)
    }

    private fun frame(entity: Smiler): Int {
        val time = entity.level().gameTime
        if (entity.glitching()) return GLITCH[Math.floorMod(roll(time, entity.id), GLITCH.size)]
        return IDLE[Math.floorMod(roll(time / IDLE_HOLD, entity.id), IDLE.size)]
    }

    private fun roll(time: Long, id: Int): Int = Mth.murmurHash3Mixer(time.toInt() * 31 + id)

    private fun vertex(consumer: VertexConsumer, pose: PoseStack.Pose, x: Float, y: Float, u: Float, v: Float, colour: Int) {
        consumer.addVertex(pose, x, y, 0f)
            .setColor(colour)
            .setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(LightTexture.FULL_BRIGHT)
            .setNormal(pose, 0f, 0f, 1f)
    }

    private companion object {
        val TEXTURE: ResourceLocation = Substratum.id("textures/entity/smiler.png")

        const val FRAMES = 6
        const val FRAME_U = 1f / FRAMES
        const val HALF = Smiler.FACE_HALF.toFloat()

        val IDLE = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 2, 2, 3)
        val GLITCH = intArrayOf(3, 4, 5, 4, 1, 5, 3, 4, 2, 5)
        const val IDLE_HOLD = 2L

        const val PHASE_SPREAD = 2.39f
        const val SWAY = 3f
        const val SWAY_SPEED = 0.045f
        const val TILT = 2.5f
        const val TILT_SPEED = 0.031f
        const val BOB = 0.03f
        const val BOB_SPEED = 0.06f

        const val GLOW = -1
        const val DEPTH_CUTOFF = 0x80FFFFFF.toInt()

        var fogStart = 0f
        var fogEnd = 0f

        val FACE: RenderType = RenderType.create(
            "substratum_smiler_face",
            DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS,
            RenderType.TRANSIENT_BUFFER_SIZE,
            false,
            false,
            RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.RENDERTYPE_EYES_SHADER)
                .setTextureState(RenderStateShard.TextureStateShard(TEXTURE, false, false))
                .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                .setTexturingState(RenderStateShard.TexturingStateShard("substratum_smiler_fog", ::pushFog, ::restoreFog))
                .createCompositeState(false),
        )

        val FACE_DEPTH: RenderType = RenderType.create(
            "substratum_smiler_face_depth",
            DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS,
            RenderType.TRANSIENT_BUFFER_SIZE,
            false,
            false,
            RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_ALPHA_SHADER)
                .setTextureState(RenderStateShard.TextureStateShard(TEXTURE, false, false))
                .setWriteMaskState(RenderStateShard.DEPTH_WRITE)
                .createCompositeState(false),
        )

        fun pushFog() {
            fogStart = RenderSystem.getShaderFogStart()
            fogEnd = RenderSystem.getShaderFogEnd()
            RenderSystem.setShaderFogStart(maxOf(fogStart, Smiler.FACE_CLEAR.toFloat()))
            RenderSystem.setShaderFogEnd(maxOf(fogEnd, Gaze.GLOW_RANGE.toFloat()))
        }

        fun restoreFog() {
            RenderSystem.setShaderFogStart(fogStart)
            RenderSystem.setShaderFogEnd(fogEnd)
        }
    }
}
