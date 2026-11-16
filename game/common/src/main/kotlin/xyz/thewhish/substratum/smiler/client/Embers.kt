package xyz.thewhish.substratum.smiler.client

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Embers {

    private const val LIFE = 0.45f
    private const val NOISE_SHARE = 0.7f
    private const val HEAT = 0.12f
    private const val FLARE = 0.7f
    private const val FLARE_SIZE = 1.35f
    private const val INSET = 0.3f
    private const val ASH = 0x161412
    private const val WHITE = 0xFFFFFF
    private const val ASH_ALPHA = 0.8f
    private const val SHRINK = 0.55f
    private const val SWIRL = 0.025f
    private const val GOLDEN = 0.618034f

    private class Drift(val spread: Float, val rise: Float, val lift: Float)

    private val FAR = Drift(0.75f, 0.3f, 0.15f)
    private val NEAR = Drift(0.45f, 0.12f, 0.6f)

    private class Texel(
        val x: Float,
        val y: Float,
        val u: Float,
        val v: Float,
        val glows: Boolean,
        val noise: Float,
        val edge: Float,
        val dirX: Float,
        val dirY: Float,
        val speed: Float,
        val phase: Float,
    )

    private var texels: List<Texel>? = null
    private var texel = 0f
    private var spanU = 0f
    private var spanV = 0f

    fun reset() {
        texels = null
    }

    fun draw(pose: PoseStack.Pose, buffers: MultiBufferSource, glow: RenderType, ash: RenderType, texture: ResourceLocation, half: Float, progress: Float, near: Boolean, seed: Int) {
        val all = texels ?: load(texture, half) ?: return
        val drift = if (near) NEAR else FAR
        val shift = (seed * GOLDEN) % 1f
        val q = progress * (1f + LIFE)
        val glowing = buffers.getBuffer(glow)
        for (t in all) {
            if (!t.glows) continue
            val order = order(t, shift)
            if (progress <= 0f || q < order) {
                quad(glowing, pose, t, t.x, t.y, 0f, 1f, WHITE, 1f)
                val lead = order - q
                if (progress > 0f && lead < HEAT) quad(glowing, pose, t, t.x, t.y, 0f, FLARE_SIZE, WHITE, FLARE * (1f - lead / HEAT))
                continue
            }
            val d = Mth.clamp((q - order) / LIFE, 0f, 1f)
            flight(glowing, pose, t, drift, d, WHITE, (1f - d) * (1f - d))
        }
        val ashes = buffers.getBuffer(ash)
        for (t in all) {
            val order = order(t, shift)
            if (progress <= 0f || q < order) {
                if (!t.glows) quad(ashes, pose, t, t.x, t.y, 0f, 1f, WHITE, 1f)
                continue
            }
            val d = Mth.clamp((q - order) / LIFE, 0f, 1f)
            flight(ashes, pose, t, drift, d, ASH, smoothstep(0.08f, 0.4f, d) * (1f - smoothstep(0.55f, 1f, d)) * ASH_ALPHA)
        }
    }

    private fun order(t: Texel, shift: Float): Float = NOISE_SHARE * ((t.noise + shift) % 1f) + (1f - NOISE_SHARE) * (1f - t.edge)

    private fun flight(consumer: VertexConsumer, pose: PoseStack.Pose, t: Texel, drift: Drift, d: Float, rgb: Int, alpha: Float) {
        val out = 1f - (1f - d) * (1f - d)
        val x = t.x + t.dirX * t.speed * drift.spread * out + SWIRL * (sin(d * 7f + t.phase) - sin(t.phase))
        val y = t.y + t.dirY * t.speed * drift.spread * out + drift.rise * d * d
        val z = drift.lift * t.speed * out
        quad(consumer, pose, t, x, y, z, 1f - SHRINK * d, rgb, alpha)
    }

    private fun quad(consumer: VertexConsumer, pose: PoseStack.Pose, t: Texel, x: Float, y: Float, z: Float, size: Float, rgb: Int, alpha: Float) {
        val a = (Mth.clamp(alpha, 0f, 1f) * 255f).toInt()
        if (a == 0) return
        val colour = (a shl 24) or rgb
        val h = texel * size
        val u0 = t.u + spanU * INSET
        val u1 = t.u + spanU * (1f - INSET)
        val v0 = t.v + spanV * INSET
        val v1 = t.v + spanV * (1f - INSET)
        vertex(consumer, pose, x - h, y - h, z, u0, v1, colour)
        vertex(consumer, pose, x + h, y - h, z, u1, v1, colour)
        vertex(consumer, pose, x + h, y + h, z, u1, v0, colour)
        vertex(consumer, pose, x - h, y + h, z, u0, v0, colour)
    }

    private fun vertex(consumer: VertexConsumer, pose: PoseStack.Pose, x: Float, y: Float, z: Float, u: Float, v: Float, colour: Int) {
        consumer.addVertex(pose, x, y, z)
            .setColor(colour)
            .setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(LightTexture.FULL_BRIGHT)
            .setNormal(pose, 0f, 0f, 1f)
    }

    private fun load(texture: ResourceLocation, half: Float): List<Texel>? {
        val resource = Minecraft.getInstance().resourceManager.getResource(texture).orElse(null) ?: return null
        val image = resource.open().use(NativeImage::read)
        return image.use {
            val size = it.height
            texel = half / size
            spanU = 1f / it.width
            spanV = 1f / size
            val width = it.width.toFloat()
            val found = ArrayList<Texel>()
            for (b in 0 until size) for (a in 0 until size) {
                val pixel = it.getPixelRGBA(a, b)
                if ((pixel ushr 24) and 0xFF == 0) continue
                val light = (pixel and 0xFF) + ((pixel shr 8) and 0xFF) + ((pixel shr 16) and 0xFF)
                val x = -half + (a + 0.5f) * 2f * half / size
                val y = half - (b + 0.5f) * 2f * half / size
                val radius = sqrt(x * x + y * y) / half
                val angle = Mth.atan2(y.toDouble(), x.toDouble()).toFloat() + (hash(a, b, 3) - 0.5f) * 1.2f
                found.add(
                    Texel(
                        x, y, a / width, b / size.toFloat(),
                        glows = light > DARK_TEXEL,
                        noise = hash(a, b, 1),
                        edge = Mth.clamp(radius / SQRT2, 0f, 1f),
                        dirX = cos(angle),
                        dirY = sin(angle),
                        speed = 0.6f + 0.8f * hash(a, b, 5),
                        phase = 2f * PI.toFloat() * hash(a, b, 7),
                    )
                )
            }
            found.also { texels = it }
        }
    }

    private fun hash(a: Int, b: Int, salt: Int): Float = (Mth.murmurHash3Mixer(a * 73856093 xor b * 19349663 xor salt * 83492791) ushr 8) / 16777216f

    private fun smoothstep(from: Float, to: Float, value: Float): Float {
        val t = Mth.clamp((value - from) / (to - from), 0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private const val DARK_TEXEL = 90
    private const val SQRT2 = 1.4142135f
}
