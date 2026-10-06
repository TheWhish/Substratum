package xyz.thewhish.substratum.client

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import dev.architectury.platform.Platform
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.EffectInstance
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector4f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryUtil
import qouteall.imm_ptl.core.IPCGlobal
import qouteall.imm_ptl.core.portal.Portal
import qouteall.imm_ptl.core.render.FrontClipping
import qouteall.imm_ptl.core.render.context_management.PortalRendering
import qouteall.imm_ptl.core.render.context_management.RenderStates
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.level.SubstratumLevels
import java.nio.FloatBuffer
import java.util.function.IntSupplier
import kotlin.math.floor

object Atmosphere {

    private const val LEVELS = 5
    private const val ENTER = 1f / 20
    private const val LEAVE = 1f / 40
    private const val BLOOM = 1f
    private const val GRAIN = 0.035f
    private const val GAIN = 30f
    private const val HAZE = 0.11f
    private const val EMISSION = 1f
    private const val SECONDS_WRAP = 3600f
    private const val PORTALS = 8
    private const val PORTAL_REACH = SubstratumLevels.SIGHT_CHUNKS * 16.0
    private const val NEAR_W = 0.05f
    private const val SCISSOR_MARGIN = 4
    private val SCATTER = floatArrayOf(0.88f, 0.72f, 0.56f, 0.43f)

    private class Pass(val effect: EffectInstance, val target: RenderTarget) {
        fun draw() {
            effect.apply()
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.frameBufferId)
            GlStateManager._viewport(0, 0, target.width, target.height)
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3)
            effect.clear()
        }
    }

    private class Chain(
        val width: Int,
        val height: Int,
        val world: List<Pass>,
        val lens: List<Pass>,
        val view: List<Pass>,
        private val swap: RenderTarget,
        private val targets: List<RenderTarget>,
        private val vao: Int
    ) : AutoCloseable {
        val light: Pass get() = world.first()
        val prefilter: Pass get() = lens.first()
        val composite: Pass get() = lens[lens.size - 2]
        private val portals = GL20.glGetUniformLocation(light.effect.id, "Portals")
        private val portalCount = GL20.glGetUniformLocation(light.effect.id, "PortalCount")

        fun run(passes: List<Pass>) {
            GlStateManager._glBindVertexArray(vao)
            passes.forEach(Pass::draw)
            GlStateManager._glBindVertexArray(0)
            BufferUploader.invalidate()
        }

        fun foreign(data: FloatBuffer, count: Int) {
            GlStateManager._glUseProgram(light.effect.id)
            if (count > 0) GL20.glUniform4fv(portals, data)
            GL20.glUniform1i(portalCount, count)
            GlStateManager._glUseProgram(0)
        }

        fun copy(from: RenderTarget) {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from.frameBufferId)
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, swap.frameBufferId)
            GlStateManager._glBlitFrameBuffer(0, 0, from.width, from.height, 0, 0, swap.width, swap.height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST)
        }

        override fun close() {
            (world + lens + view).forEach { it.effect.close() }
            targets.forEach(RenderTarget::destroyBuffers)
            GlStateManager._glDeleteVertexArrays(vao)
        }
    }

    private var chain: Chain? = null
    private var failed = false
    private var presence = 0f
    private var presenceO = 0f
    private var seconds = 0f
    private var lit = false
    private var hands = true
    private var composited = false
    private val viewProjection = Matrix4f()
    private val corner = Vector4f()
    private val portalData = MemoryUtil.memAllocFloat(PORTALS * 12)

    private val irisInUse: (() -> Boolean)? by lazy {
        if (!Platform.isModLoaded("iris")) return@lazy null
        runCatching {
            val api = Class.forName("net.irisshaders.iris.api.v0.IrisApi")
            val instance = api.getMethod("getInstance").invoke(null)
            val inUse = api.getMethod("isShaderPackInUse")
            return@runCatching { inUse.invoke(instance) == true }
        }.getOrNull()
    }

    fun tick(minecraft: Minecraft) {
        presenceO = presence
        val inside = minecraft.level?.dimension() == SubstratumLevels.LEVEL_0
        presence = if (inside) minOf(1f, presence + ENTER) else maxOf(0f, presence - LEAVE)
        val level = minecraft.level ?: return
        active(minecraft, ClientConfig.intensity)
        if (inside) LightVolume.selectLamps(level, minecraft.gameRenderer.mainCamera.position)
    }

    @JvmStatic
    fun settle(minecraft: Minecraft) {
        presence = if (minecraft.level?.dimension() == SubstratumLevels.LEVEL_0) 1f else 0f
        presenceO = presence
    }

    @JvmStatic
    fun renderWorld(minecraft: Minecraft, partialTick: Float, camera: Camera, view: Matrix4f, projection: Matrix4f, clearsHands: Boolean) {
        val level = minecraft.level ?: return
        if (level.dimension() != SubstratumLevels.LEVEL_0) return
        if (PortalRendering.isRendering()) {
            renderPortalView(minecraft, level, camera, view, projection)
            return
        }
        val strength = strength(partialTick)
        val current = active(minecraft, strength) ?: return
        light(current, level, camera, view, projection, strength)
        minecraft.mainRenderTarget.bindWrite(true)
        lit = true
        hands = clearsHands
    }

    private fun renderPortalView(minecraft: Minecraft, level: ClientLevel, camera: Camera, view: Matrix4f, projection: Matrix4f) {
        if (RenderStates.originalPlayerDimension == SubstratumLevels.LEVEL_0) return
        if (IPCGlobal.renderer !== IPCGlobal.rendererUsingStencil || FrontClipping.isClippingEnabled) return
        val strength = ClientConfig.intensity
        val current = active(minecraft, strength) ?: return
        val main = minecraft.mainRenderTarget
        val bounds = portalBounds(camera, view, projection, main)
        if (bounds != null && (bounds[2] <= bounds[0] || bounds[3] <= bounds[1])) return
        LightVolume.selectLamps(level, camera.position)
        scissor(bounds, current.light.target, main)
        light(current, level, camera, view, projection, strength)
        scissor(bounds, main, main)
        current.copy(main)
        current.view.first().effect.apply {
            safeGetUniform("Haze").set(HAZE * strength)
            safeGetUniform("Strength").set(strength)
        }
        prepare()
        current.run(current.view)
        if (bounds != null) RenderSystem.disableScissor()
        main.bindWrite(true)
        RenderSystem.enableDepthTest()
    }

    private fun portalBounds(camera: Camera, view: Matrix4f, projection: Matrix4f, main: RenderTarget): IntArray? {
        val portal = PortalRendering.getRenderingPortal()
        viewProjection.set(projection).mul(view)
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (local in portal.getFourVerticesLocal(0.0)) {
            val point = portal.transformPoint(portal.originPos.add(local)).subtract(camera.position)
            corner.set(point.x.toFloat(), point.y.toFloat(), point.z.toFloat(), 1f).mul(viewProjection)
            if (corner.w < NEAR_W) return null
            x0 = minOf(x0, corner.x / corner.w)
            y0 = minOf(y0, corner.y / corner.w)
            x1 = maxOf(x1, corner.x / corner.w)
            y1 = maxOf(y1, corner.y / corner.w)
        }
        return intArrayOf(
            pixel(x0, main.width, -SCISSOR_MARGIN), pixel(y0, main.height, -SCISSOR_MARGIN),
            pixel(x1, main.width, SCISSOR_MARGIN), pixel(y1, main.height, SCISSOR_MARGIN)
        )
    }

    private fun pixel(ndc: Float, size: Int, margin: Int): Int =
        (floor((ndc * 0.5f + 0.5f) * size).toInt() + margin).coerceIn(0, size)

    private fun scissor(bounds: IntArray?, target: RenderTarget, main: RenderTarget) {
        if (bounds == null) return
        val x = bounds[0] * target.width / main.width
        val y = bounds[1] * target.height / main.height
        val right = (bounds[2] * target.width + main.width - 1) / main.width
        val top = (bounds[3] * target.height + main.height - 1) / main.height
        RenderSystem.enableScissor(x, y, right - x, top - y)
    }

    private fun light(current: Chain, level: ClientLevel, camera: Camera, view: Matrix4f, projection: Matrix4f, strength: Float) {
        val position = camera.position
        viewProjection.set(projection).mul(view)
        LightVolume.update(level, ChunkPos(camera.blockPosition), SubstratumLevels.SIGHT_CHUNKS)
        LightVolume.uploadLamps(position, viewProjection, current.light.target.width, current.light.target.height)
        foreignPortals(current, level, position)
        current.light.effect.apply {
            safeGetUniform("InvViewProj").set(viewProjection.invert())
            safeGetUniform("Camera").set(wrap(position.x), (position.y - LightVolume.BASE_Y).toFloat(), wrap(position.z))
            safeGetUniform("TilesX").set(LightVolume.tilesX)
            safeGetUniform("FogStart").set(DepthFog.lastStart)
            safeGetUniform("FogEnd").set(DepthFog.lastEnd)
            safeGetUniform("Gain").set(GAIN * strength)
        }
        prepare()
        LightVolume.bind()
        current.run(current.world)
        LightVolume.unbind()
    }

    private fun foreignPortals(current: Chain, level: ClientLevel, camera: Vec3) {
        portalData.clear()
        var count = 0
        for (entity in level.entitiesForRendering()) {
            val portal = entity as? Portal ?: continue
            if (portal.destDim == SubstratumLevels.LEVEL_0 || !portal.isVisible) continue
            if (portal.originPos.distanceToSqr(camera) > PORTAL_REACH * PORTAL_REACH) continue
            put(portal.originPos.subtract(camera))
            put(portal.axisW.scale(portal.width / 2))
            put(portal.axisH.scale(portal.height / 2))
            if (++count == PORTALS) break
        }
        portalData.flip()
        current.foreign(portalData, count)
    }

    private fun put(vector: Vec3) {
        portalData.put(vector.x.toFloat()).put(vector.y.toFloat()).put(vector.z.toFloat()).put(0f)
    }

    @JvmStatic
    fun render(minecraft: Minecraft, frameTicks: Float, partialTick: Float) {
        seconds = (seconds + frameTicks / 20f) % SECONDS_WRAP
        val strength = strength(partialTick)
        val current = active(minecraft, strength) ?: return
        val glare = 1f + SanityEffects.glare(partialTick) * ClientConfig.intensity
        val lighting = if (lit) 1f else 0f
        lit = false
        current.prefilter.effect.apply {
            safeGetUniform("Lit").set(lighting)
            safeGetUniform("Haze").set(HAZE * strength * glare)
            safeGetUniform("Emission").set(EMISSION * strength)
            safeGetUniform("Hands").set(if (hands) 1f else 0f)
        }
        current.composite.effect.apply {
            safeGetUniform("Lit").set(lighting)
            safeGetUniform("Haze").set(HAZE * strength * glare)
            safeGetUniform("Hands").set(if (hands) 1f else 0f)
            safeGetUniform("Time").set(seconds)
            safeGetUniform("Strength").set(strength)
            safeGetUniform("Bloom").set(BLOOM * glare)
            safeGetUniform("Grain").set(GRAIN * SanityEffects.grain(partialTick) * ClientConfig.intensity)
            safeGetUniform("Aberration").set(SanityEffects.aberration(partialTick) * strength)
            safeGetUniform("Ghost").set(SanityEffects.ghost(partialTick) * strength)
            safeGetUniform("Tunnel").set(SanityEffects.tunnel(partialTick) * strength)
            safeGetUniform("Drain").set(SanityEffects.drain(partialTick) * strength)
            safeGetUniform("Vhs").set(Vhs.level(partialTick))
            safeGetUniform("VhsSeed").set(Vhs.seed)
            safeGetUniform("VhsShake").set(Vhs.shake())
        }
        composited = true
        val main = minecraft.mainRenderTarget
        prepare()
        main.setFilterMode(GL11.GL_LINEAR)
        current.run(current.lens)
        main.setFilterMode(GL11.GL_NEAREST)
        main.bindWrite(true)
    }

    fun consumeComposited(): Boolean = composited.also { composited = false }

    fun reset() {
        chain?.close()
        chain = null
        failed = false
        lit = false
    }

    private fun strength(partialTick: Float): Float = ClientConfig.intensity * (presenceO + (presence - presenceO) * partialTick)

    private fun active(minecraft: Minecraft, strength: Float): Chain? {
        if (failed || strength <= 0f || minecraft.level == null || irisInUse?.invoke() == true) return null
        val main = minecraft.mainRenderTarget
        return chain?.takeIf { it.width == main.width && it.height == main.height } ?: rebuild(minecraft)
    }

    private fun prepare() {
        RenderSystem.disableBlend()
        RenderSystem.disableDepthTest()
        RenderSystem.resetTextureMatrix()
    }

    private fun wrap(coordinate: Double): Float =
        (coordinate - floor(coordinate / LightVolume.SIDE) * LightVolume.SIDE).toFloat()

    private fun hdr(width: Int, height: Int, format: Int): TextureTarget = TextureTarget(width, height, false, Minecraft.ON_OSX).apply {
        GlStateManager._bindTexture(getColorTextureId())
        GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, format, this.width, this.height, 0, GL11.GL_RGB, GL11.GL_FLOAT, null)
        GlStateManager._bindTexture(0)
        setFilterMode(GL11.GL_LINEAR)
    }

    private fun rebuild(minecraft: Minecraft): Chain? {
        reset()
        return runCatching { build(minecraft) }.onFailure {
            failed = true
            minecraft.mainRenderTarget.bindWrite(true)
            Substratum.LOGGER.error("Level 0 atmosphere failed to load, rendering without it", it)
        }.getOrNull()?.also { chain = it }
    }

    private fun build(minecraft: Minecraft): Chain {
        val main = minecraft.mainRenderTarget
        val targets = ArrayList<RenderTarget>()
        val effects = ArrayList<EffectInstance>()
        var vao = -1
        try {
            fun <T : RenderTarget> keep(target: T): T = target.also(targets::add)
            fun pass(name: String, from: RenderTarget, to: RenderTarget, vararg samplers: Pair<String, IntSupplier>): Pass {
                val effect = EffectInstance(minecraft.resourceManager, name).also(effects::add)
                effect.setSampler("DiffuseSampler", IntSupplier(from::getColorTextureId))
                samplers.forEach { (sampler, texture) -> effect.setSampler(sampler, texture) }
                effect.safeGetUniform("InSize").set(from.width.toFloat(), from.height.toFloat())
                return Pass(effect, to)
            }
            val down = List(LEVELS) { keep(hdr(maxOf(1, main.width shr (it + 1)), maxOf(1, main.height shr (it + 1)), GL30.GL_R11F_G11F_B10F)) }
            val up = List(LEVELS - 1) { keep(hdr(down[it].width, down[it].height, GL30.GL_R11F_G11F_B10F)) }
            val light = keep(hdr(down[0].width, down[0].height, GL30.GL_RGBA16F))
            val swap = keep(TextureTarget(main.width, main.height, false, Minecraft.ON_OSX)).apply { setFilterMode(GL11.GL_LINEAR) }
            val depth = "DepthSampler" to IntSupplier(main::getDepthTextureId)
            val lighting = "LightSampler" to IntSupplier(light::getColorTextureId)
            val world = pass("substratum_light", main, light, depth)
            LightVolume.attach(world.effect.id)
            val lens = ArrayList<Pass>()
            lens += pass("substratum_bloom_prefilter", main, down[0], lighting, depth)
            for (i in 1 until LEVELS) lens += pass("substratum_bloom_down", down[i - 1], down[i])
            for (i in LEVELS - 2 downTo 0) {
                lens += pass("substratum_bloom_up", if (i == LEVELS - 2) down[i + 1] else up[i + 1], up[i], "BaseSampler" to IntSupplier(down[i]::getColorTextureId))
                lens.last().effect.safeGetUniform("Scatter").set(SCATTER[i])
            }
            lens += pass("substratum_atmosphere", main, swap, "BloomSampler" to IntSupplier(up[0]::getColorTextureId), lighting, depth)
            lens += pass("substratum_fxaa", swap, main)
            val view = pass("substratum_portal", swap, main, lighting)
            vao = GlStateManager._glGenVertexArrays()
            return Chain(main.width, main.height, listOf(world), lens, listOf(view), swap, targets, vao)
        } catch (e: Exception) {
            effects.forEach(EffectInstance::close)
            targets.forEach(RenderTarget::destroyBuffers)
            if (vao >= 0) GlStateManager._glDeleteVertexArrays(vao)
            throw e
        }
    }
}
