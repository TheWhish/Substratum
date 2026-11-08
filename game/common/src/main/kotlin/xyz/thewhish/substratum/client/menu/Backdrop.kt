package xyz.thewhish.substratum.client.menu

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.platform.TextureUtil
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.math.Axis
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.EffectInstance
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.server.packs.resources.ResourceManager
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL30
import org.lwjgl.stb.STBImage
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.client.MenuShot
import xyz.thewhish.substratum.registry.ModSounds
import java.io.File
import java.io.IOException
import java.util.SplittableRandom
import java.util.function.IntSupplier
import kotlin.math.cos
import kotlin.math.exp

object Backdrop {
    private const val FADE_MS = 35f
    private const val FLICKER_VOLUME = 0.35f
    private const val SHADE = 0xB0000000.toInt()
    private const val SHADE_SPAN = 0.55f
    private const val ZOOM_BASE = 1.04f
    private const val LOOK = 0.004f
    private const val LOOK_MS = 600f
    private const val ZOOM = 0.07f
    private const val ZOOM_PERIOD_MS = 45_000f
    private const val QUIET_MIN_MS = 3_000L
    private const val QUIET_MAX_MS = 9_000L
    private const val BLINK_MIN_MS = 50L
    private const val BLINK_MAX_MS = 140L
    private const val GAP_MIN_MS = 80L
    private const val GAP_MAX_MS = 220L
    private const val OUTAGE_MIN_MS = 400L
    private const val OUTAGE_MAX_MS = 1_800L
    private const val TIME_WRAP_MS = 3_600_000L
    private const val DARK = "off.jpg"
    private const val LIT = "on.jpg"
    private const val DEPTH = "depth.png"
    private const val SCENE = "scene.json"
    private val FILES = listOf(DARK, LIT, DEPTH, SCENE)

    private class Photo(val width: Int, val height: Int) : AbstractTexture() {
        override fun load(manager: ResourceManager) = Unit
    }

    private class Scene(val dark: Photo, val lit: Photo, val depth: Photo, val data: JsonObject, val effect: EffectInstance, val vao: Int) : AutoCloseable {
        override fun close() {
            effect.close()
            GlStateManager._glDeleteVertexArrays(vao)
            listOf(dark, lit, depth).forEach(AbstractTexture::releaseId)
        }
    }

    private var scene: Scene? = null
    private var failed = false
    private var glow = 1f
    private var lit = true
    private var last = 0L
    private var lookX = 0f
    private var lookY = 0f
    private var next = 0L
    private val outages = ArrayDeque<LongRange>()
    private val random = SplittableRandom()

    fun render(graphics: GuiGraphics, width: Int, height: Int, mouseX: Int, mouseY: Int, main: Boolean) {
        val scene = scene() ?: return graphics.fill(0, 0, width, height, Menus.SKY)
        val now = Util.getMillis()
        val on = lit(now)
        if (main && on && !lit) Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(ModSounds.LAMP_FLICKER.get(), 1f, FLICKER_VOLUME))
        lit = on
        val elapsed = (now - last).coerceIn(0L, 1000L)
        glow += ((if (on) 1f else 0f) - glow) * (1f - exp(-elapsed / FADE_MS))
        val follow = 1f - exp(-elapsed / LOOK_MS)
        lookX += ((if (main) mouseX * 2f / width - 1f else 0f) - lookX) * follow
        lookY += ((if (main) mouseY * 2f / height - 1f else 0f) - lookY) * follow
        last = now
        graphics.flush()
        draw(scene, now)
        if (main) shade(graphics, width, height)
    }

    private fun draw(scene: Scene, now: Long) {
        val main = Minecraft.getInstance().mainRenderTarget
        val screen = main.width.toFloat() / main.height
        val picture = scene.dark.width.toFloat() / scene.dark.height
        val shownWidth = if (screen > picture) 1f else screen / picture
        val shownHeight = if (screen > picture) picture / screen else 1f
        val subject = scene.data.getAsJsonArray("subject")
        val lamp = scene.data.getAsJsonArray("lamp")
        val effect = scene.effect
        with(effect) {
            safeGetUniform("OutSize").set(main.width.toFloat(), main.height.toFloat())
            safeGetUniform("Crop").set((1f - shownWidth) / 2f, (1f - shownHeight) / 2f, shownWidth, shownHeight)
            safeGetUniform("Subject").set(subject[0].asFloat, subject[1].asFloat)
            safeGetUniform("Lamp").set(lamp?.get(0)?.asFloat ?: 0f, lamp?.get(1)?.asFloat ?: 0f, if (lamp == null) 0f else 1f)
            safeGetUniform("Zoom").set(ZOOM_BASE + ZOOM * (0.5f - 0.5f * cos(now % ZOOM_PERIOD_MS.toLong() / ZOOM_PERIOD_MS * 2f * Math.PI.toFloat())))
            safeGetUniform("Glow").set(glow)
            safeGetUniform("Look").set(lookX * LOOK, lookY * LOOK)
            safeGetUniform("Time").set((now % TIME_WRAP_MS) / 1000f)
            safeGetUniform("Focus").set(scene.data.get("focus").asFloat)
            safeGetUniform("Far").set(scene.data.get("far").asFloat)
        }
        effect.apply()
        RenderSystem.disableDepthTest()
        RenderSystem.depthMask(false)
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, main.frameBufferId)
        GlStateManager._viewport(0, 0, main.width, main.height)
        GlStateManager._glBindVertexArray(scene.vao)
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3)
        GlStateManager._glBindVertexArray(0)
        effect.clear()
        RenderSystem.depthMask(true)
        RenderSystem.enableDepthTest()
        BufferUploader.invalidate()
        main.bindWrite(true)
    }

    private fun lit(now: Long): Boolean {
        if (now >= next) plan(now)
        while (outages.isNotEmpty() && outages.first().last < now) outages.removeFirst()
        return outages.firstOrNull()?.let { now < it.first } ?: true
    }

    private fun plan(now: Long) {
        var at = now + random.nextLong(QUIET_MIN_MS, QUIET_MAX_MS)
        val roll = random.nextInt(100)
        repeat(if (roll in 45 until 75) 2 else 1) {
            val off = random.nextLong(BLINK_MIN_MS, BLINK_MAX_MS)
            outages.addLast(at..at + off)
            at += off + random.nextLong(GAP_MIN_MS, GAP_MAX_MS)
        }
        if (roll >= 75) {
            val off = random.nextLong(OUTAGE_MIN_MS, OUTAGE_MAX_MS)
            outages.addLast(at..at + off)
            at += off
        }
        next = at
    }

    private fun shade(graphics: GuiGraphics, width: Int, height: Int) {
        val pose = graphics.pose()
        pose.pushPose()
        pose.translate(0f, height.toFloat(), 0f)
        pose.mulPose(Axis.ZP.rotationDegrees(-90f))
        graphics.fillGradient(0, 0, height, (width * SHADE_SPAN).toInt(), SHADE, 0)
        pose.popPose()
    }

    fun release() {
        scene?.close()
        scene = null
        failed = false
    }

    private fun scene(): Scene? {
        if (scene != null || failed) return scene
        val minecraft = Minecraft.getInstance()
        val shot = File(minecraft.gameDirectory, MenuShot.FOLDER).takeIf { dir -> FILES.all { File(dir, it).isFile } }
        val read: (String) -> ByteArray = { name ->
            shot?.let { File(it, name).readBytes() }
                ?: minecraft.resourceManager.getResourceOrThrow(Substratum.id("textures/menu/$name")).open().use { it.readAllBytes() }
        }
        val made = ArrayList<AutoCloseable>()
        scene = runCatching {
            val data = JsonParser.parseString(String(read(SCENE))).asJsonObject
            val dark = load(read(DARK), true).also { made += AutoCloseable(it::releaseId) }
            val lit = load(read(LIT), true).also { made += AutoCloseable(it::releaseId) }
            val depth = load(read(DEPTH), false).also { made += AutoCloseable(it::releaseId) }
            val effect = EffectInstance(minecraft.resourceManager, "substratum_backdrop").also { made += it }
            effect.setSampler("DarkSampler", IntSupplier(dark::getId))
            effect.setSampler("LitSampler", IntSupplier(lit::getId))
            effect.setSampler("DepthSampler", IntSupplier(depth::getId))
            Scene(dark, lit, depth, data, effect, GlStateManager._glGenVertexArrays())
        }.onFailure {
            made.forEach(AutoCloseable::close)
            failed = true
            Substratum.LOGGER.error("Menu backdrop failed to load", it)
        }.getOrNull()
        return scene
    }

    private fun load(bytes: ByteArray, smooth: Boolean): Photo {
        val file = MemoryUtil.memAlloc(bytes.size).put(bytes).flip()
        try {
            MemoryStack.stackPush().use { stack ->
                val width = stack.mallocInt(1)
                val height = stack.mallocInt(1)
                val pixels = STBImage.stbi_load_from_memory(file, width, height, stack.mallocInt(1), 4)
                    ?: throw IOException("Cannot decode menu image: ${STBImage.stbi_failure_reason()}")
                try {
                    val photo = Photo(width[0], height[0])
                    val levels = if (smooth) 31 - Integer.numberOfLeadingZeros(minOf(photo.width, photo.height)) else 0
                    TextureUtil.prepareImage(photo.getId(), levels, photo.width, photo.height)
                    GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0)
                    GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0)
                    GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0)
                    GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4)
                    GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, photo.width, photo.height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels)
                    if (smooth) GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D)
                    GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
                    GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
                    photo.setFilter(smooth, smooth)
                    return photo
                } finally {
                    STBImage.stbi_image_free(pixels)
                }
            }
        } finally {
            MemoryUtil.memFree(file)
        }
    }
}
