package xyz.thewhish.substratum.client

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import dev.architectury.platform.Platform
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EffectInstance
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import xyz.thewhish.substratum.Substratum
import java.util.function.IntSupplier

object Smaa {
    private val AREA = Substratum.id("textures/smaa/area.png")
    private val SEARCH = Substratum.id("textures/smaa/search.png")
    private val CLEAR = floatArrayOf(0f, 0f, 0f, 0f)
    private val iris by lazy { Platform.isModLoaded("iris") }

    private class Passes(
        val edge: EffectInstance,
        val weight: EffectInstance,
        val blend: EffectInstance,
        val edges: RenderTarget,
        val weights: RenderTarget,
        val output: RenderTarget,
        val vao: Int,
    ) : AutoCloseable {
        override fun close() {
            listOf(edge, weight, blend).forEach(EffectInstance::close)
            listOf(edges, weights, output).forEach(RenderTarget::destroyBuffers)
            GlStateManager._glDeleteVertexArrays(vao)
        }
    }

    private var passes: Passes? = null
    private var failed = false
    private lateinit var source: RenderTarget

    fun smooth(minecraft: Minecraft) {
        val main = minecraft.mainRenderTarget
        val smoothed = resolve(minecraft, main)
        if (smoothed === main) return
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, smoothed.frameBufferId)
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, main.frameBufferId)
        GlStateManager._glBlitFrameBuffer(0, 0, main.width, main.height, 0, 0, main.width, main.height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST)
        main.bindWrite(true)
    }

    fun resolve(minecraft: Minecraft, frame: RenderTarget): RenderTarget {
        if (!ClientConfig.antialiasing || iris) return frame
        val current = passes(minecraft, frame) ?: return frame
        source = frame
        val filter = frame.filterMode
        frame.setFilterMode(GL11.GL_LINEAR)
        RenderSystem.disableBlend()
        RenderSystem.disableDepthTest()
        GlStateManager._glBindVertexArray(current.vao)
        draw(current.edge, current.edges, clear = true)
        draw(current.weight, current.weights, clear = true)
        draw(current.blend, current.output, clear = false)
        GlStateManager._glBindVertexArray(0)
        BufferUploader.invalidate()
        frame.setFilterMode(filter)
        return current.output
    }

    fun reset() {
        passes?.close()
        passes = null
        failed = false
    }

    private fun draw(effect: EffectInstance, into: RenderTarget, clear: Boolean) {
        effect.apply()
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, into.frameBufferId)
        GlStateManager._viewport(0, 0, into.width, into.height)
        if (clear) GL30.glClearBufferfv(GL11.GL_COLOR, 0, CLEAR)
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3)
        effect.clear()
    }

    private fun passes(minecraft: Minecraft, frame: RenderTarget): Passes? {
        if (failed) return null
        passes?.takeIf { it.output.width == frame.width && it.output.height == frame.height }?.let { return it }
        passes?.close()
        passes = null
        return runCatching { build(minecraft, frame.width, frame.height) }
            .onFailure {
                failed = true
                minecraft.mainRenderTarget.bindWrite(true)
                Substratum.LOGGER.error("SMAA failed to load, rendering without anti-aliasing", it)
            }
            .getOrNull()
            ?.also { passes = it }
    }

    private fun build(minecraft: Minecraft, width: Int, height: Int): Passes {
        val textures = minecraft.textureManager
        val area = textures.getTexture(AREA).apply { setFilter(true, false) }
        val search = textures.getTexture(SEARCH).apply { setFilter(false, false) }
        val made = ArrayList<AutoCloseable>()
        try {
            fun effect(name: String, vararg samplers: Pair<String, IntSupplier>): EffectInstance =
                EffectInstance(minecraft.resourceManager, name).also { effect ->
                    made += effect
                    samplers.forEach { (sampler, texture) -> effect.setSampler(sampler, texture) }
                    effect.safeGetUniform("InSize").set(width.toFloat(), height.toFloat())
                }
            fun target(format: Int): RenderTarget = hdr(width, height, format).also { made += AutoCloseable(it::destroyBuffers) }
            val edges = target(GL30.GL_RG8)
            val weights = target(GL11.GL_RGBA8)
            val output = target(GL11.GL_RGB10_A2)
            val frame = "DiffuseSampler" to IntSupplier { source.colorTextureId }
            val edge = effect("substratum_smaa_edge", frame)
            val weight = effect(
                "substratum_smaa_weight",
                "DiffuseSampler" to IntSupplier(edges::getColorTextureId),
                "AreaSampler" to IntSupplier(area::getId),
                "SearchSampler" to IntSupplier(search::getId),
            )
            val blend = effect("substratum_smaa_blend", frame, "BlendSampler" to IntSupplier(weights::getColorTextureId))
            return Passes(edge, weight, blend, edges, weights, output, GlStateManager._glGenVertexArrays())
        } catch (e: Exception) {
            made.forEach(AutoCloseable::close)
            throw e
        }
    }
}
