package xyz.thewhish.substratum.client

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import dev.architectury.event.events.client.ClientCommandRegistrationEvent
import dev.architectury.event.events.client.ClientCommandRegistrationEvent.ClientCommandSourceStack
import dev.architectury.event.events.client.ClientCommandRegistrationEvent.argument
import dev.architectury.event.events.client.ClientCommandRegistrationEvent.literal
import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Camera
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector4f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryUtil
import qouteall.imm_ptl.core.render.context_management.PortalRendering
import xyz.thewhish.substratum.client.menu.Backdrop
import xyz.thewhish.substratum.registry.ModBlocks
import xyz.thewhish.substratum.registry.ModBlocks.LampCondition
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.ImageWriteParam
import javax.imageio.metadata.IIOMetadataNode
import kotlin.math.pow
import kotlin.math.roundToInt

object MenuShot {
    const val WIDTH = 5040
    const val HEIGHT = 2160
    const val FAR = 128f
    const val FOLDER = "menushot"
    private const val SUPERSAMPLE = 2
    private const val SETTLE_TICKS = 40
    private const val WARMUP_FRAMES = 3
    private const val LAMP_REACH = 8
    private const val MAX_RADIUS = 48
    private const val QUALITY = 0.97f
    private const val JPEG_TREE = "javax_imageio_jpeg_image_1.0"
    private const val GAMMA = 2.2
    private const val SUBJECT_HEIGHT = 0.55
    private val STATES = linkedMapOf("on" to LampCondition.ON, "off" to LampCondition.DEAD, "dim" to LampCondition.DIM, "flicker" to LampCondition.FLICKERING)

    private enum class Stage { IDLE, OFF, ON }

    private var stage = Stage.IDLE
    private var wait = 0
    private var lamp = BlockPos.ZERO
    private var lit = LampCondition.ON
    private var eye = Vec3.ZERO
    private var yaw = 0f
    private var pitch = 0f
    private var scale = 1
    private var grabbing = false
    private val scene = JsonObject()

    val busy: Boolean get() = stage != Stage.IDLE

    fun register() {
        ClientCommandRegistrationEvent.EVENT.register { dispatcher, _ ->
            val lampNode = literal("lamp")
            STATES.forEach { (name, condition) ->
                lampNode.then(
                    literal(name)
                        .executes { reply(it, lamp(Minecraft.getInstance(), condition)) }
                        .then(argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS)).executes {
                            reply(it, lamps(Minecraft.getInstance(), condition, IntegerArgumentType.getInteger(it, "radius")))
                        }),
                )
            }
            dispatcher.register(
                literal("menushot")
                    .then(literal("place").executes { reply(it, place(Minecraft.getInstance(), false)) }
                        .then(literal("mirror").executes { reply(it, place(Minecraft.getInstance(), true)) }))
                    .then(literal("shoot").executes { reply(it, shoot(Minecraft.getInstance())) })
                    .then(literal("clear").executes { reply(it, clear()) })
                    .then(lampNode),
            )
        }
        ClientTickEvent.CLIENT_POST.register(::tick)
    }

    private fun reply(context: CommandContext<ClientCommandSourceStack>, text: String): Int {
        context.source.`arch$sendSuccess`({ Component.literal(text) }, false)
        return 1
    }

    private fun place(minecraft: Minecraft, mirrored: Boolean): String {
        val level = minecraft.level ?: return "Нет мира"
        val hit = minecraft.hitResult as? BlockHitResult
        if (hit == null || hit.type != HitResult.Type.BLOCK || !hit.direction.axis.isHorizontal) return "Наведи прицел на боковую грань блока стены"
        Mannequin.place(level, hit.blockPos, hit.direction, mirrored)
        return "Костюм у стены. Встань для кадра и введи /menushot shoot"
    }

    private fun clear(): String {
        if (busy) return "Идёт съёмка"
        Mannequin.clear()
        return "Костюм убран"
    }

    private fun lamp(minecraft: Minecraft, condition: LampCondition): String {
        if (busy) return "Идёт съёмка"
        val level = minecraft.level ?: return "Нет мира"
        val hit = minecraft.hitResult as? BlockHitResult
        if (hit == null || hit.type != HitResult.Type.BLOCK || !level.getBlockState(hit.blockPos).`is`(ModBlocks.lampBlock)) return "Наведи прицел на лампу"
        LampFlicker.force(level, hit.blockPos, condition)
        return "Лампа: ${name(condition)}"
    }

    private fun lamps(minecraft: Minecraft, condition: LampCondition, radius: Int): String {
        if (busy) return "Идёт съёмка"
        val level = minecraft.level ?: return "Нет мира"
        val player = minecraft.player ?: return "Нет игрока"
        val found = scanBlocks(level, player.blockPosition(), radius) { it.`is`(ModBlocks.lampBlock) }
        found.forEach { LampFlicker.force(level, it, condition) }
        return "Ламп в радиусе $radius: ${found.size} — ${name(condition)}"
    }

    private fun name(condition: LampCondition): String = STATES.entries.first { it.value == condition }.key

    private fun shoot(minecraft: Minecraft): String {
        if (busy) return "Съёмка уже идёт"
        val level = minecraft.level ?: return "Нет мира"
        val player = minecraft.player ?: return "Нет игрока"
        val centre = Mannequin.placed()?.blockPosition() ?: player.blockPosition()
        val candidates = scanBlocks(level, centre, LAMP_REACH) { it.`is`(ModBlocks.lampBlock) && it.getValue(ModBlocks.LAMP_CONDITION).let { c -> c.lit || c == LampCondition.FLICKERING } }
        val found = candidates.filter { level.getBlockState(it).getValue(ModBlocks.LAMP_CONDITION) == LampCondition.FLICKERING }.minByOrNull { it.distSqr(centre) }
            ?: candidates.minByOrNull { it.distSqr(centre) }
            ?: return "Рядом с костюмом нет горящей лампы"
        lamp = found
        lit = level.getBlockState(found).getValue(ModBlocks.LAMP_CONDITION)
        if (!lit.lit) lit = LampCondition.FLICKERING
        eye = player.position()
        yaw = player.yRot
        pitch = player.xRot
        scale = if (GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE) >= WIDTH * SUPERSAMPLE) SUPERSAMPLE else 1
        stage = Stage.OFF
        wait = SETTLE_TICKS
        LampFlicker.switchTo(level, lamp, LampCondition.DEAD)
        return "Съёмка: мигать будет лампа ${found.toShortString()}, не двигайся ~10 секунд"
    }

    private fun tick(minecraft: Minecraft) {
        if (!busy) return
        val level = minecraft.level
        if (level == null) {
            stage = Stage.IDLE
            return
        }
        if (minecraft.screen != null || --wait > 0) return
        if (stage == Stage.OFF) {
            capture(minecraft, "off", depth = true)
            LampFlicker.switchTo(level, lamp, lit)
            stage = Stage.ON
            wait = SETTLE_TICKS
            return
        }
        capture(minecraft, "on", depth = false)
        File(folder(minecraft), "scene.json").writeText(scene.toString())
        stage = Stage.IDLE
        Backdrop.release()
        minecraft.player?.displayClientMessage(Component.literal("Готово: ${folder(minecraft)} — выйди в главное меню, фон уже из этой съёмки"), false)
    }

    fun folder(minecraft: Minecraft): File = File(minecraft.gameDirectory, FOLDER).apply { mkdirs() }

    private fun capture(minecraft: Minecraft, name: String, depth: Boolean) {
        val window = minecraft.window
        val width = window.width
        val height = window.height
        val hidden = minecraft.options.hideGui
        val bob = minecraft.options.bobView().get()
        minecraft.player?.moveTo(eye.x, eye.y, eye.z, yaw, pitch)
        minecraft.options.hideGui = true
        minecraft.options.bobView().set(false)
        try {
            window.width = WIDTH * scale
            window.height = HEIGHT * scale
            minecraft.resizeDisplay()
            repeat(WARMUP_FRAMES) {
                grabbing = depth && it == WARMUP_FRAMES - 1
                minecraft.mainRenderTarget.bindWrite(true)
                minecraft.gameRenderer.render(DeltaTracker.ONE, true)
            }
            Screenshot.takeScreenshot(minecraft.mainRenderTarget).use { frame ->
                if (scale == 1) save(frame, folder(minecraft), name) else shrink(frame).use { save(it, folder(minecraft), name) }
            }
        } finally {
            grabbing = false
            minecraft.options.hideGui = hidden
            minecraft.options.bobView().set(bob)
            window.width = width
            window.height = height
            minecraft.resizeDisplay()
        }
    }

    @JvmStatic
    fun afterWorld(camera: Camera, view: Matrix4f, projection: Matrix4f) {
        if (!grabbing || PortalRendering.isRendering()) return
        grabbing = false
        val minecraft = Minecraft.getInstance()
        val main = minecraft.mainRenderTarget
        val step = scale * 2
        val focus = readDepth(main.frameBufferId, main.width, main.height, step, projection, File(folder(minecraft), "depth.png"))
        val subject = Mannequin.placed()?.let { it.position().add(0.0, SUBJECT_HEIGHT * it.scale, 0.0) }
        val subjectAt = subject?.let { project(it, camera, view, projection) }
        val lampAt = project(Vec3(lamp.x + 0.5, lamp.y.toDouble(), lamp.z + 0.5), camera, view, projection)
        scene.entrySet().clear()
        scene.addProperty("far", FAR)
        scene.addProperty("focus", subject?.let { distance(it, camera, view) } ?: focus)
        scene.add("subject", pair(subjectAt ?: floatArrayOf(0.5f, 0.5f)))
        lampAt?.let { scene.add("lamp", pair(it)) }
    }

    private fun readDepth(framebuffer: Int, width: Int, height: Int, step: Int, projection: Matrix4f, file: File): Float {
        val w = width / step
        val h = height / step
        val row = MemoryUtil.memAllocFloat(width)
        try {
            NativeImage(w, h, false).use { image ->
                GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer)
                for (j in 0 until h) {
                    GL11.glReadPixels(0, j * step + step / 2, width, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, row)
                    for (i in 0 until w) {
                        val ndc = row.get(i * step + step / 2) * 2f - 1f
                        val code = ((projection.m32() / (ndc + projection.m22()) / FAR).coerceIn(0f, 1f) * 65535f).roundToInt()
                        image.setPixelRGBA(i, h - 1 - j, (0xFF shl 24) or ((code and 0xFF) shl 8) or (code shr 8))
                    }
                }
                image.writeToFile(file)
                val centre = image.getPixelRGBA(w / 2, h / 2)
                return ((centre and 0xFF) * 256 + (centre shr 8 and 0xFF)) / 65535f * FAR
            }
        } finally {
            MemoryUtil.memFree(row)
        }
    }

    private fun project(point: Vec3, camera: Camera, view: Matrix4f, projection: Matrix4f): FloatArray? {
        val clip = relative(point, camera).mul(view).mul(projection)
        if (clip.w <= 0f) return null
        return floatArrayOf(clip.x / clip.w * 0.5f + 0.5f, 0.5f - clip.y / clip.w * 0.5f)
    }

    private fun distance(point: Vec3, camera: Camera, view: Matrix4f): Float = -relative(point, camera).mul(view).z

    private fun relative(point: Vec3, camera: Camera): Vector4f {
        val origin = camera.position
        return Vector4f((point.x - origin.x).toFloat(), (point.y - origin.y).toFloat(), (point.z - origin.z).toFloat(), 1f)
    }

    private fun pair(values: FloatArray) = JsonArray().apply { values.forEach(::add) }

    private fun shrink(frame: NativeImage): NativeImage {
        val linear = DoubleArray(256) { (it / 255.0).pow(GAMMA) }
        val samples = scale * scale
        val result = NativeImage(frame.width / scale, frame.height / scale, false)
        val sum = DoubleArray(3)
        for (y in 0 until result.height) for (x in 0 until result.width) {
            sum.fill(0.0)
            for (dy in 0 until scale) for (dx in 0 until scale) {
                val abgr = frame.getPixelRGBA(x * scale + dx, y * scale + dy)
                for (c in 0 until 3) sum[c] += linear[abgr shr (c * 8) and 0xFF]
            }
            var abgr = 0xFF shl 24
            for (c in 0 until 3) abgr = abgr or (((sum[c] / samples).pow(1 / GAMMA) * 255.0).roundToInt().coerceIn(0, 255) shl (c * 8))
            result.setPixelRGBA(x, y, abgr)
        }
        return result
    }

    private fun save(image: NativeImage, folder: File, name: String) {
        image.writeToFile(File(folder, "$name.png"))
        val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val abgr = image.getPixelRGBA(x, y)
            rgb.setRGB(x, y, (abgr and 0xFF shl 16) or (abgr and 0xFF00) or (abgr shr 16 and 0xFF))
        }
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val settings = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = QUALITY
        }
        val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(rgb), settings)
        val tree = metadata.getAsTree(JPEG_TREE) as IIOMetadataNode
        val components = tree.getElementsByTagName("componentSpec")
        for (i in 0 until components.length) (components.item(i) as IIOMetadataNode).apply {
            setAttribute("HsamplingFactor", "1")
            setAttribute("VsamplingFactor", "1")
        }
        metadata.setFromTree(JPEG_TREE, tree)
        val jpeg = File(folder, "$name.jpg").apply { delete() }
        ImageIO.createImageOutputStream(jpeg).use {
            writer.output = it
            writer.write(null, IIOImage(rgb, null, metadata), settings)
        }
        writer.dispose()
    }
}
