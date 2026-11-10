package xyz.thewhish.substratum.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.brigadier.context.CommandContext
import dev.architectury.event.events.client.ClientCommandRegistrationEvent
import dev.architectury.event.events.client.ClientCommandRegistrationEvent.ClientCommandSourceStack
import dev.architectury.event.events.client.ClientCommandRegistrationEvent.literal
import dev.architectury.event.events.client.ClientGuiEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11C
import org.lwjgl.opengl.GL15C
import org.lwjgl.opengl.GL33C
import qouteall.imm_ptl.core.portal.Portal
import xyz.thewhish.substratum.Substratum
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.function.Supplier

object Probe {

    private const val LOG_FILE = "substratum-probe.log"
    private const val IN_FLIGHT = 4
    private const val MARKS = 512
    private const val DEPTH = 6
    private const val SHOWN = 26
    private const val LOGGED = 40
    private const val WINDOW_NANOS = 1_000_000_000L
    private const val NANOS_PER_MILLI = 1_000_000.0
    private const val TOTAL_VIDMEM_NVX = 0x9048
    private const val AVAILABLE_VIDMEM_NVX = 0x9049
    private const val EVICTION_COUNT_NVX = 0x904A
    private const val EVICTED_MEMORY_NVX = 0x904B
    private const val PANEL = 0xD0101010.toInt()
    private const val TITLE = 0xFFFFD060.toInt()
    private const val TEXT = 0xFFE0E0E0.toInt()
    private const val HOT = 0xFFFF6050.toInt()
    private const val HOT_MS = 5.0
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")

    @JvmField
    var enabled = false

    private class Frame {
        val queries = IntArray(MARKS)
        val labels = arrayOfNulls<String>(MARKS)
        var count = 0
        var overflow = false
        var portalViews = 0
        var pending = false
    }

    private val frames = Array(IN_FLIGHT) { Frame() }
    private var current: Frame? = null
    private var frameIndex = 0L
    private val names = arrayOfNulls<String>(DEPTH)
    private var depth = 0

    private var windowStart = 0L
    private var lastFrameStart = 0L
    private var cpuFrames = 0
    private var cpuWorst = 0.0
    private var gpuFrames = 0
    private var gpuTotal = 0.0
    private var gpuWorst = 0.0
    private var missed = 0
    private var overflows = 0
    private var portalViews = 0
    private val sums = HashMap<String, Double>()
    private var worst = HashMap<String, Double>()
    private var lastEvictions = -1
    private var lines: List<String> = emptyList()
    private var summary: List<String> = emptyList()
    private var detailed = true

    fun register() {
        ClientCommandRegistrationEvent.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                literal("probe")
                    .then(literal("low").executes { reply(it, start(false)) })
                    .then(literal("high").executes { reply(it, start(true)) })
                    .then(literal("off").executes { reply(it, stop()) }),
            )
        }
        ClientGuiEvent.RENDER_HUD.register { graphics, _ -> if (enabled) draw(graphics, if (detailed) lines else summary) }
    }

    private fun reply(context: CommandContext<ClientCommandSourceStack>, text: String): Int {
        context.source.`arch$sendSuccess`({ Component.literal(text) }, false)
        return 1
    }

    private fun start(detailed: Boolean): String {
        this.detailed = detailed
        val view = if (detailed) "подробный" else "краткий"
        if (enabled) return "Замер: $view вид"
        enabled = true
        frames.forEach { it.pending = false }
        resetWindow(System.nanoTime())
        lastFrameStart = 0
        lines = listOf("Замер видеокарты: собираю первую секунду...")
        summary = lines
        log("=== замер включён ${LocalTime.now().format(CLOCK)} ===")
        return "Замер включён, $view вид, лог пишется в $LOG_FILE"
    }

    private fun stop(): String {
        if (!enabled) return "Замер и так выключен"
        enabled = false
        current = null
        return "Замер выключен, лог: $LOG_FILE"
    }

    @JvmStatic
    fun frameStart() {
        if (!enabled || !RenderSystem.isOnRenderThread()) return
        val now = System.nanoTime()
        if (lastFrameStart != 0L) {
            val cpu = (now - lastFrameStart) / NANOS_PER_MILLI
            cpuFrames++
            cpuWorst = maxOf(cpuWorst, cpu)
        }
        lastFrameStart = now
        val frame = frames[(frameIndex % IN_FLIGHT).toInt()]
        if (frame.pending) collect(frame)
        if (frame.queries[0] == 0) GL15C.glGenQueries(frame.queries)
        frame.count = 0
        frame.overflow = false
        frame.portalViews = 0
        frame.pending = false
        current = frame
        depth = 0
        mark("до мира")
        if (now - windowStart >= WINDOW_NANOS) closeWindow(now)
    }

    @JvmStatic
    fun frameEnd() {
        val frame = current ?: return
        mark(null)
        frame.pending = true
        current = null
        frameIndex++
    }

    @JvmStatic
    fun push(name: String) {
        if (current == null || !RenderSystem.isOnRenderThread()) return
        depth++
        if (depth > DEPTH) return
        names[depth - 1] = name
        mark(path())
    }

    @JvmStatic
    fun push(name: Supplier<String>) {
        if (current == null || !RenderSystem.isOnRenderThread()) return
        if (depth + 1 > DEPTH) {
            depth++
            return
        }
        push(name.get())
    }

    @JvmStatic
    fun pop() {
        if (current == null || !RenderSystem.isOnRenderThread() || depth == 0) return
        depth--
        if (depth < DEPTH) mark(if (depth == 0) "вне секций" else path())
    }

    @JvmStatic
    fun popPush(name: String) {
        if (current == null || !RenderSystem.isOnRenderThread() || depth == 0 || depth > DEPTH) return
        names[depth - 1] = name
        mark(path())
    }

    @JvmStatic
    fun popPush(name: Supplier<String>) {
        if (current == null || !RenderSystem.isOnRenderThread() || depth == 0 || depth > DEPTH) return
        popPush(name.get())
    }

    fun portalView() {
        current?.let { it.portalViews++ }
    }

    private fun path(): String = names.take(minOf(depth, DEPTH)).joinToString("/")

    private fun mark(label: String?) {
        val frame = current ?: return
        if (frame.count == MARKS) {
            frame.overflow = true
            return
        }
        GL33C.glQueryCounter(frame.queries[frame.count], GL33C.GL_TIMESTAMP)
        frame.labels[frame.count] = label
        frame.count++
    }

    private fun collect(frame: Frame) {
        frame.pending = false
        if (frame.count < 2 || GL15C.glGetQueryObjecti(frame.queries[frame.count - 1], GL15C.GL_QUERY_RESULT_AVAILABLE) == GL11C.GL_FALSE) {
            missed++
            return
        }
        val parts = HashMap<String, Double>()
        var previous = GL33C.glGetQueryObjecti64(frame.queries[0], GL15C.GL_QUERY_RESULT)
        val first = previous
        for (i in 1 until frame.count) {
            val time = GL33C.glGetQueryObjecti64(frame.queries[i], GL15C.GL_QUERY_RESULT)
            val label = frame.labels[i - 1] ?: continue
            parts.merge(label, (time - previous) / NANOS_PER_MILLI, Double::plus)
            previous = time
        }
        val total = (previous - first) / NANOS_PER_MILLI
        parts.forEach { (label, ms) -> sums.merge(label, ms, Double::plus) }
        gpuFrames++
        gpuTotal += total
        portalViews += frame.portalViews
        if (frame.overflow) overflows++
        if (total > gpuWorst) {
            gpuWorst = total
            worst = parts
        }
    }

    private fun resetWindow(now: Long) {
        windowStart = now
        cpuFrames = 0
        cpuWorst = 0.0
        gpuFrames = 0
        gpuTotal = 0.0
        gpuWorst = 0.0
        missed = 0
        overflows = 0
        portalViews = 0
        sums.clear()
        worst = HashMap()
    }

    private fun closeWindow(now: Long) {
        val seconds = (now - windowStart) / 1e9
        val minecraft = Minecraft.getInstance()
        val frames = maxOf(gpuFrames, 1)
        val out = ArrayList<String>()
        val fps = f0(cpuFrames / seconds)
        val gpu = "${f2(gpuTotal / frames)} мс, худший ${f2(gpuWorst)} мс"
        val memory = memory()
        out += "ЗАМЕР ВИДЕОКАРТЫ ${LocalTime.now().format(CLOCK)}   /probe off — выключить, /probe low — кратко   лог: $LOG_FILE"
        out += "FPS $fps   кадр CPU худший ${f2(cpuWorst)} мс   видеокарта: средний $gpu"
        out += "Кадров замерено $gpuFrames, без результата $missed, переполнено $overflows   лимит FPS ${minecraft.options.framerateLimit().get()}, vsync ${minecraft.options.enableVsync().get()}"
        out += "Видеопамять: $memory"
        out += "Порталы: видов за кадр ${f2(portalViews.toDouble() / frames)}, порталов рядом ${portals(minecraft)}   лампы в проходе ${LightVolume.visibleLamps}   фонари ${Flashlights.count}"
        out += "Чанки: ${minecraft.levelRenderer.sectionStatistics ?: "-"}"
        out += "Сущности: ${minecraft.levelRenderer.entityStatistics ?: "-"}   окно ${minecraft.window.width}x${minecraft.window.height}   дальность ${minecraft.options.renderDistance().get()}"
        out += "Java: ${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576}/${Runtime.getRuntime().maxMemory() / 1048576} МБ"
        out += "мс средн.  мс в худшем кадре   секция (собственное время видеокарты)"
        val ranked = sums.entries.sortedByDescending { it.value }
        ranked.take(LOGGED).forEach { (label, sum) -> out += "${pad(f2(sum / frames))} ${pad(f2(worst[label] ?: 0.0))}   $label" }
        lines = out.take(9 + SHOWN)
        val heaviest = ranked.firstOrNull()?.let { (label, sum) -> "${label.split('/').takeLast(2).joinToString("/")} ${f2(sum / frames)} мс" } ?: "-"
        summary = listOf("FPS $fps   CPU худший ${f2(cpuWorst)} мс", "Видеокарта $gpu", "Видеопамять $memory", "Тяжелее всего: $heaviest")
        log(out.joinToString("\n"))
        resetWindow(now)
    }

    private fun memory(): String {
        if (!GL.getCapabilities().GL_NVX_gpu_memory_info) return "драйвер не сообщает"
        val total = GL11C.glGetInteger(TOTAL_VIDMEM_NVX) / 1024
        val free = GL11C.glGetInteger(AVAILABLE_VIDMEM_NVX) / 1024
        val evictions = GL11C.glGetInteger(EVICTION_COUNT_NVX)
        val evicted = GL11C.glGetInteger(EVICTED_MEMORY_NVX) / 1024
        val fresh = if (lastEvictions < 0) 0 else evictions - lastEvictions
        lastEvictions = evictions
        return "${total - free}/$total МБ, вытеснений за секунду $fresh (всего $evictions, $evicted МБ)"
    }

    private fun portals(minecraft: Minecraft): Int = minecraft.level?.entitiesForRendering()?.count { it is Portal } ?: 0

    private fun draw(graphics: GuiGraphics, lines: List<String>) {
        val font = Minecraft.getInstance().font
        val height = lines.size * (font.lineHeight + 1) + 6
        val scale = minOf(1f, (graphics.guiHeight() - 4f) / height)
        val width = lines.maxOfOrNull(font::width) ?: 0
        graphics.pose().pushPose()
        graphics.pose().translate(2f, 2f, 0f)
        graphics.pose().scale(scale, scale, 1f)
        graphics.fill(0, 0, width + 6, height, PANEL)
        lines.forEachIndexed { i, line ->
            val colour = when {
                i == 0 -> TITLE
                i > 8 && (line.trim().substringBefore(' ').replace(',', '.').toDoubleOrNull() ?: 0.0) >= HOT_MS -> HOT
                else -> TEXT
            }
            graphics.drawString(font, line, 3, 3 + i * (font.lineHeight + 1), colour, false)
        }
        graphics.pose().popPose()
    }

    private fun log(text: String) {
        runCatching {
            Files.writeString(Minecraft.getInstance().gameDirectory.toPath().resolve(LOG_FILE), text + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }.onFailure { Substratum.LOGGER.warn("Probe could not write $LOG_FILE", it) }
    }

    private fun f0(value: Double) = String.format(Locale.ROOT, "%.0f", value)
    private fun f2(value: Double) = String.format(Locale.ROOT, "%.2f", value)
    private fun pad(text: String) = text.padStart(8)
}
