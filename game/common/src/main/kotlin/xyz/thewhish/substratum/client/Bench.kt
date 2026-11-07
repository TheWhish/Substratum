package xyz.thewhish.substratum.client

import com.mojang.blaze3d.platform.GlUtil
import com.mojang.brigadier.context.CommandContext
import dev.architectury.event.events.client.ClientCommandRegistrationEvent
import dev.architectury.event.events.client.ClientCommandRegistrationEvent.ClientCommandSourceStack
import dev.architectury.event.events.client.ClientCommandRegistrationEvent.literal
import dev.architectury.event.events.client.ClientPlayerEvent
import dev.architectury.event.events.client.ClientTickEvent
import dev.architectury.platform.Platform
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.network.chat.Component
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.phys.Vec3
import org.lwjgl.opengl.GL15C
import org.lwjgl.opengl.GL33C
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

object Bench {
    private const val FORMAT = "substratum-bench 1"
    private const val ROUTE_FILE = "substratum-bench.route"
    private const val WARMUP_TICKS = 200
    private const val SHORTEST_ROUTE = 100
    private const val IN_FLIGHT = 8
    private const val MARKS = 3
    private const val START = 0
    private const val WORLD = 1
    private const val END = 2
    private const val NANOS_PER_MILLI = 1_000_000.0
    private const val SLOW_FRAME_MS = 16.7
    private const val UNLIMITED_FPS = 260
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    private val AUTO = System.getenv("SUBSTRATUM_BENCH") == "run"

    private class Point(val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float)

    private var recorded: MutableList<Point>? = null
    private var route: List<Point>? = null
    private var dimension = ""
    private var cursor = 0
    private var warmup = 0
    private var measuring = false
    private var savedLimit = UNLIMITED_FPS
    private var savedVsync = false

    private var queries: IntArray? = null
    private val marked = BooleanArray(IN_FLIGHT * MARKS)
    private var frame = 0L
    private var lastStart = 0L
    private val cpu = ArrayList<Double>()
    private val gpu = ArrayList<Double>()
    private val gpuWorld = ArrayList<Double>()
    private val serverTicks = ArrayList<Double>()
    private var lastServerTick = 0

    fun register() {
        ClientCommandRegistrationEvent.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                literal("bench")
                    .then(literal("record").executes { reply(it, record(Minecraft.getInstance())) })
                    .then(literal("stop").executes { reply(it, stop(Minecraft.getInstance())) })
                    .then(literal("run").executes { reply(it, run(Minecraft.getInstance())) }),
            )
        }
        ClientTickEvent.CLIENT_POST.register(::tick)
        if (AUTO) ClientPlayerEvent.CLIENT_PLAYER_JOIN.register { player ->
            val minecraft = Minecraft.getInstance()
            player.displayClientMessage(Component.literal(run(minecraft)), false)
            if (route == null) minecraft.stop()
        }
    }

    private fun reply(context: CommandContext<ClientCommandSourceStack>, text: String): Int {
        context.source.`arch$sendSuccess`({ Component.literal(text) }, false)
        return 1
    }

    private fun record(minecraft: Minecraft): String {
        if (busy()) return "Бенчмарк уже идёт"
        routeFile(minecraft) ?: return "Маршрут пишется только в одиночной игре"
        recorded = ArrayList()
        dimension = minecraft.level!!.dimension().location().toString()
        return "Запись маршрута: пройди путь, потом /bench stop"
    }

    private fun stop(minecraft: Minecraft): String {
        val points = recorded ?: return "Запись не идёт"
        recorded = null
        if (points.size < SHORTEST_ROUTE) return "Маршрут короче ${SHORTEST_ROUTE / 20} секунд — не сохранён"
        val file = routeFile(minecraft) ?: return "Мир закрыт — маршрут не сохранён"
        val lines = listOf("$FORMAT $dimension") + points.map { "${it.x} ${it.y} ${it.z} ${it.yaw} ${it.pitch}" }
        Files.write(file, lines)
        return "Маршрут сохранён: ${points.size / 20} с, $file"
    }

    private fun run(minecraft: Minecraft): String {
        if (busy()) return "Бенчмарк уже идёт"
        val file = routeFile(minecraft) ?: return "Бенчмарк идёт только в одиночной игре"
        if (!Files.isRegularFile(file)) return "В этом мире нет маршрута: сначала /bench record"
        val lines = Files.readAllLines(file)
        val header = lines.firstOrNull()?.split(' ') ?: return "Файл маршрута пуст"
        if (header.size != 3 || "${header[0]} ${header[1]}" != FORMAT) return "Файл маршрута другого формата"
        val here = minecraft.level!!.dimension().location().toString()
        if (header[2] != here) return "Маршрут записан в ${header[2]}, а ты в $here"
        route = lines.drop(1).map(::parse)
        cursor = 0
        warmup = WARMUP_TICKS
        return "Бенчмарк: ${route!!.size / 20} с после ${WARMUP_TICKS / 20} с прогрева. Не трогай мышь и клавиатуру"
    }

    private fun tick(minecraft: Minecraft) {
        val player = minecraft.player ?: return abort(minecraft, null)
        recorded?.add(Point(player.x, player.y, player.z, player.yRot, player.xRot))
        val points = route ?: return
        if (minecraft.isPaused) return abort(minecraft, "Бенчмарк прерван: игра на паузе")
        val point = points[cursor]
        player.deltaMovement = Vec3.ZERO
        player.absMoveTo(point.x, point.y, point.z, point.yaw, point.pitch)
        if (warmup > 0) {
            if (--warmup == 0) begin(minecraft)
            return
        }
        sampleServer(minecraft)
        if (++cursor == points.size) finish(minecraft)
    }

    @JvmStatic
    fun frameStart() {
        if (!measuring) return
        val now = System.nanoTime()
        if (lastStart != 0L) cpu.add((now - lastStart) / NANOS_PER_MILLI)
        lastStart = now
        if (frame >= IN_FLIGHT) collect(slot(frame))
        mark(START)
    }

    @JvmStatic
    fun worldDone() {
        if (measuring) mark(WORLD)
    }

    @JvmStatic
    fun frameEnd() {
        if (!measuring) return
        mark(END)
        frame++
    }

    private fun busy() = recorded != null || route != null

    private fun begin(minecraft: Minecraft) {
        val ids = queries ?: IntArray(IN_FLIGHT * MARKS).also { GL15C.glGenQueries(it); queries = it }
        check(ids.all { it != 0 }) { "GPU timer queries are unavailable" }
        marked.fill(false)
        frame = 0
        lastStart = 0
        cpu.clear()
        gpu.clear()
        gpuWorld.clear()
        serverTicks.clear()
        lastServerTick = minecraft.singleplayerServer?.tickCount ?: 0
        val options = minecraft.options
        savedLimit = options.framerateLimit().get()
        savedVsync = options.enableVsync().get()
        options.framerateLimit().set(UNLIMITED_FPS)
        options.enableVsync().set(false)
        measuring = true
    }

    private fun end(minecraft: Minecraft) {
        if (!measuring) return
        measuring = false
        minecraft.options.framerateLimit().set(savedLimit)
        minecraft.options.enableVsync().set(savedVsync)
    }

    private fun sampleServer(minecraft: Minecraft) {
        val server = minecraft.singleplayerServer ?: return
        val tick = server.tickCount
        if (tick == lastServerTick) return
        lastServerTick = tick
        val times = server.tickTimesNanos
        serverTicks.add(times[(tick - 1).mod(times.size)] / NANOS_PER_MILLI)
    }

    private fun mark(which: Int) {
        val index = slot(frame) * MARKS + which
        GL33C.glQueryCounter(queries!![index], GL33C.GL_TIMESTAMP)
        marked[index] = true
    }

    private fun slot(frame: Long) = (frame % IN_FLIGHT).toInt()

    private fun collect(slot: Int) {
        val base = slot * MARKS
        if (!marked[base + START] || !marked[base + END]) return clear(base)
        val start = timestamp(base + START)
        gpu.add((timestamp(base + END) - start) / NANOS_PER_MILLI)
        if (marked[base + WORLD]) gpuWorld.add((timestamp(base + WORLD) - start) / NANOS_PER_MILLI)
        clear(base)
    }

    private fun clear(base: Int) = marked.fill(false, base, base + MARKS)

    private fun timestamp(index: Int): Long = GL33C.glGetQueryObjecti64(queries!![index], GL15C.GL_QUERY_RESULT)

    private fun finish(minecraft: Minecraft) {
        end(minecraft)
        for (pending in maxOf(0L, frame - IN_FLIGHT) until frame) collect(slot(pending))
        route = null
        val lines = report(minecraft)
        val dir = minecraft.gameDirectory.toPath().resolve("bench")
        Files.createDirectories(dir)
        val stamp = LocalDateTime.now().format(STAMP)
        val file = dir.resolve("$stamp.txt")
        Files.write(file, lines)
        Screenshot.grab(minecraft.gameDirectory, "bench-$stamp.png", minecraft.mainRenderTarget) {}
        minecraft.player?.displayClientMessage(Component.literal(lines.take(5).joinToString("\n")), false)
        minecraft.player?.displayClientMessage(Component.literal("Отчёт: $file"), false)
        if (AUTO) minecraft.stop()
    }

    private fun abort(minecraft: Minecraft, reason: String?) {
        if (!busy()) return
        recorded = null
        route = null
        end(minecraft)
        if (reason != null) minecraft.player?.displayClientMessage(Component.literal(reason), false)
        if (AUTO) minecraft.stop()
    }

    private fun report(minecraft: Minecraft): List<String> {
        val options = minecraft.options
        val window = minecraft.window
        val cpuSorted = cpu.sorted()
        val gpuSorted = gpu.sorted()
        val worldSorted = gpuWorld.sorted()
        val median = percentile(cpuSorted, 0.5)
        val low = percentile(cpuSorted, 0.99)
        val rare = percentile(cpuSorted, 0.999)
        return listOf(
            "FPS: медиана ${fps(median)}, 1% худших ${fps(low)}, 0.1% худших ${fps(rare)}, кадров ${cpu.size}, медленнее 60 FPS: ${cpu.count { it > SLOW_FRAME_MS }}",
            "Кадр CPU, мс: медиана ${ms(median)}, p99 ${ms(low)}, максимум ${ms(cpuSorted.lastOrNull() ?: 0.0)}",
            "Кадр GPU, мс: медиана ${ms(percentile(gpuSorted, 0.5))}, p99 ${ms(percentile(gpuSorted, 0.99))}",
            "Мир на GPU, мс: медиана ${ms(percentile(worldSorted, 0.5))}, p99 ${ms(percentile(worldSorted, 0.99))}",
            "Пост-обработка и интерфейс на GPU, мс: медиана ${ms(percentile(gpuSorted, 0.5) - percentile(worldSorted, 0.5))}",
            serverTicks.sorted().let { "Тик сервера, мс: медиана ${ms(percentile(it, 0.5))}, p99 ${ms(percentile(it, 0.99))}" },
            "",
            "Окно: ${window.width}x${window.height}, полный экран: ${window.isFullscreen}",
            "Дальность: ${options.renderDistance().get()}, симуляция: ${options.simulationDistance().get()}, графика: ${options.graphicsMode().get()}",
            "Предел FPS и VSync на время замера сняты (у игрока: ${savedLimit}, VSync ${savedVsync})",
            "Sodium: ${Platform.isModLoaded("sodium")}, модов: ${Platform.getModIds().size}",
            "GPU: ${GlUtil.getRenderer()}",
        )
    }

    private fun percentile(sorted: List<Double>, rank: Double): Double =
        if (sorted.isEmpty()) 0.0 else sorted[(rank * (sorted.size - 1)).roundToInt()]

    private fun fps(frameMs: Double) = if (frameMs > 0) (1000 / frameMs).roundToInt().toString() else "—"

    private fun ms(value: Double) = String.format(Locale.ROOT, "%.2f", value)

    private fun parse(line: String): Point {
        val parts = line.split(' ')
        require(parts.size == 5) { "broken route line: $line" }
        return Point(parts[0].toDouble(), parts[1].toDouble(), parts[2].toDouble(), parts[3].toFloat(), parts[4].toFloat())
    }

    private fun routeFile(minecraft: Minecraft): Path? {
        val server = minecraft.singleplayerServer ?: return null
        if (minecraft.level == null) return null
        return server.getWorldPath(LevelResource.ROOT).resolve(ROUTE_FILE)
    }
}
