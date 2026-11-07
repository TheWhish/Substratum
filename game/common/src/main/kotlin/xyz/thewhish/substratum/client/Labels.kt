package xyz.thewhish.substratum.client

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.platform.TextureUtil
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.VertexSorting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import org.joml.Matrix4f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL30
import xyz.thewhish.substratum.Substratum

object Labels {
    const val PAD = 1f
    const val CELL = 8f
    private const val SCALE = 8
    private const val KEEP_TICKS = 1200
    private const val BYTES = 4096
    private const val SHARPEN = -0.5f
    private val PAPER = floatArrayOf(1f, 1f, 1f, 0f)

    class Label(val location: ResourceLocation, val span: Float, val width: Float, var used: Long)

    private class Texture : AbstractTexture() {
        override fun load(manager: ResourceManager) = Unit

        override fun setFilter(blur: Boolean, mipmap: Boolean) = super.setFilter(true, true)
    }

    private val labels = HashMap<String, Label>()
    private val wanted = LinkedHashSet<String>()
    private var ticks = 0L
    private var made = 0

    fun of(text: String): Label? {
        val label = labels[text]
        if (label == null) {
            wanted += text
            return null
        }
        label.used = ticks
        return label
    }

    fun tick(minecraft: Minecraft) {
        ticks++
        labels.entries.removeIf { (_, label) ->
            (ticks - label.used > KEEP_TICKS).also { if (it) minecraft.textureManager.release(label.location) }
        }
        if (wanted.isEmpty()) return
        wanted.forEach { labels[it] = make(minecraft, it) }
        wanted.clear()
        minecraft.mainRenderTarget.bindWrite(true)
    }

    fun reset() {
        val textures = Minecraft.getInstance().textureManager
        labels.values.forEach { textures.release(it.location) }
        labels.clear()
        wanted.clear()
    }

    private fun make(minecraft: Minecraft, text: String): Label {
        val font = minecraft.font
        val span = (font.width(text) - 1).coerceAtLeast(1).toFloat()
        val width = span + 2 * PAD
        val pixelsWide = (width * SCALE).toInt()
        val pixelsHigh = ((CELL + 2 * PAD) * SCALE).toInt()
        val texture = Texture()
        val id = texture.getId()
        TextureUtil.prepareImage(id, 31 - Integer.numberOfLeadingZeros(minOf(pixelsWide, pixelsHigh)), pixelsWide, pixelsHigh)
        val framebuffer = GlStateManager.glGenFramebuffers()
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, id, 0)
        GlStateManager._viewport(0, 0, pixelsWide, pixelsHigh)
        GL30.glClearBufferfv(GL11.GL_COLOR, 0, PAPER)
        print(font, text, pixelsWide, pixelsHigh)
        GlStateManager._glDeleteFramebuffers(framebuffer)
        GlStateManager._bindTexture(id)
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D)
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS, SHARPEN)
        texture.setFilter(true, true)
        val location = Substratum.id("label/${made++}")
        minecraft.textureManager.register(location, texture)
        return Label(location, span, width, ticks)
    }

    private fun print(font: Font, text: String, pixelsWide: Int, pixelsHigh: Int) {
        RenderSystem.backupProjectionMatrix()
        RenderSystem.setProjectionMatrix(Matrix4f().setOrtho(0f, pixelsWide.toFloat(), pixelsHigh.toFloat(), 0f, -1f, 1f), VertexSorting.ORTHOGRAPHIC_Z)
        val view = RenderSystem.getModelViewStack()
        view.pushMatrix().identity()
        RenderSystem.applyModelViewMatrix()
        RenderSystem.colorMask(false, false, false, true)
        ByteBufferBuilder(BYTES).use { bytes ->
            val buffers = MultiBufferSource.immediate(bytes)
            val pose = Matrix4f().scale(SCALE.toFloat()).translate(PAD, PAD, 0f)
            font.drawInBatch(text, 0f, 0f, -1, false, pose, buffers, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT)
            buffers.endBatch()
        }
        RenderSystem.colorMask(true, true, true, true)
        view.popMatrix()
        RenderSystem.applyModelViewMatrix()
        RenderSystem.restoreProjectionMatrix()
    }
}
