package xyz.thewhish.substratum.client

import com.mojang.blaze3d.shaders.FogShape
import net.minecraft.Util
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import org.joml.Matrix4f
import org.joml.Vector4f
import qouteall.imm_ptl.core.render.context_management.PortalRendering
import xyz.thewhish.substratum.gaze.Gaze
import xyz.thewhish.substratum.gaze.Probe
import xyz.thewhish.substratum.gaze.Viewer
import xyz.thewhish.substratum.network.ProbeSync
import xyz.thewhish.substratum.network.ViewportSync
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.tan

object ProbeView {

    private const val MEMORY_MS = 10_000L
    private const val TICK_MS = 50.0
    private const val TURN_GRACE_MS = 200L
    private const val PANEL_SCALE = 0.75f
    private const val PANEL_X = 6
    private const val PANEL_Y = 6
    private const val LINE = 11
    private const val FRUSTUM_DOTS = 64
    private const val EDGE = 1.2f
    private const val GREEN = 0xFF35D06A.toInt()
    private const val BLUE = 0xFF4A9BFF.toInt()
    private const val RED = 0xFFFF2E2E.toInt()
    private const val ORANGE = 0xFFFF9A1F.toInt()
    private const val STILL_DEGREES = 1.5
    private const val WALLED = 0xFF7A1F1F.toInt()
    private const val YELLOW = 0xFFFFD43B.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val GREY = 0xFF8C8C8C.toInt()
    private const val INK = 0xFF000000.toInt()
    private const val BACKDROP = 0xB0000000.toInt()
    private val SEEN = Gaze.Sight.SEEN.ordinal.toByte()
    private val DARK = Gaze.Sight.DARK.ordinal.toByte()
    private val HIDDEN = Gaze.Sight.HIDDEN.ordinal.toByte()
    private val OUT = Gaze.Sight.OUT.ordinal.toByte()
    private const val UNSEEN: Byte = 0
    private const val AGREED: Byte = 1
    private const val GLOOMY: Byte = 2
    private const val WRONG: Byte = 3
    private const val LAGGED: Byte = 4

    private class Tally(val at: Long, val errors: Int, val lagged: Int, val lag: Double)

    private var frame: ProbeSync.Frame? = null
    private var points: Array<Vec3> = emptyArray()
    private var sights = ByteArray(0)
    private var marks = ByteArray(0)
    private var fresh = false
    private val viewProjection = Matrix4f()
    private var camera = Vec3.ZERO
    private var captured = false
    private var fogEnd = Float.MAX_VALUE
    private var fogCylinder = false
    private var cameraLook = Vec3(0.0, 0.0, 1.0)
    private var look: Vec3? = null
    private var lookedAt = 0L
    private var turnedAt = Long.MIN_VALUE / 2
    private val tallies = ArrayDeque<Tally>()
    private var seen = 0
    private var agreed = 0
    private var dark = 0
    private var walled = 0
    private var far = 0
    private var still = 0
    private var lagged = 0
    private var lag = 0.0
    private var cautious = 0

    fun accept(next: ProbeSync.Frame) {
        if (!next.active) return reset()
        val count = next.points.size / 4
        val origin = next.origin
        points = Array(count) {
            Vec3(
                origin.x + next.points[it * 4] * Probe.STEP + 0.5,
                origin.y + next.points[it * 4 + 1] * Probe.STEP + 0.5,
                origin.z + next.points[it * 4 + 2] * Probe.STEP + 0.5,
            )
        }
        sights = ByteArray(count) { next.points[it * 4 + 3] }
        marks = ByteArray(count)
        if (next.frozen || frame?.frozen == true) tallies.clear()
        frame = next
        fresh = true
    }

    fun reset() {
        frame = null
        points = emptyArray()
        sights = ByteArray(0)
        marks = ByteArray(0)
        tallies.clear()
        look = null
        turnedAt = Long.MIN_VALUE / 2
        fresh = false
        captured = false
    }

    @JvmStatic
    fun rememberFog(end: Float, shape: FogShape) {
        fogEnd = end
        fogCylinder = shape == FogShape.CYLINDER
    }

    @JvmStatic
    fun capture(camera: Camera, view: Matrix4f, projection: Matrix4f) {
        if (frame == null || PortalRendering.isRendering()) return
        viewProjection.set(projection).mul(view)
        this.camera = camera.position
        cameraLook = Vec3(camera.lookVector)
        captured = true
    }

    fun tick(minecraft: Minecraft) {
        val frame = frame ?: return
        val level = minecraft.level ?: return
        if (!fresh || !captured || frame.frozen) return
        fresh = false
        evaluate(level, frame)
    }

    private fun evaluate(level: ClientLevel, frame: ProbeSync.Frame) {
        val now = Util.getMillis()
        if (turnPerTick(now) > STILL_DEGREES) turnedAt = now
        val turning = now - turnedAt <= TURN_GRACE_MS + frame.latency
        seen = 0
        agreed = 0
        dark = 0
        walled = 0
        far = 0
        still = 0
        lagged = 0
        lag = 0.0
        cautious = 0
        for (i in points.indices) {
            val point = points[i]
            if (!onScreen(point) || fogged(point) || !clear(level, point)) {
                marks[i] = UNSEEN
                if (sights[i] == SEEN) cautious++
                continue
            }
            seen++
            marks[i] = mark(sights[i], turning, frame, point)
        }
        tallies.addLast(Tally(now, walled + far + still, lagged, lag))
        while (tallies.isNotEmpty() && now - tallies.first().at > MEMORY_MS) tallies.removeFirst()
    }

    private fun mark(sight: Byte, turning: Boolean, frame: ProbeSync.Frame, point: Vec3): Byte = when (sight) {
        SEEN -> AGREED.also { agreed++ }
        DARK -> GLOOMY.also { dark++ }
        HIDDEN -> WRONG.also { walled++ }
        OUT -> if (turning) {
            lagged++
            lag = max(lag, behind(frame, point))
            LAGGED
        } else {
            still++
            WRONG
        }
        else -> WRONG.also { far++ }
    }

    private fun turnPerTick(now: Long): Double {
        val before = look
        val elapsed = now - lookedAt
        look = cameraLook
        lookedAt = now
        if (before == null || elapsed <= 0L) return 0.0
        return Math.toDegrees(Math.acos(before.dot(cameraLook).coerceIn(-1.0, 1.0))) * TICK_MS / elapsed
    }

    private fun behind(frame: ProbeSync.Frame, point: Vec3): Double {
        val right = frame.forward.cross(Vec3(0.0, 1.0, 0.0)).let { if (it.lengthSqr() < 1.0e-6) Vec3(1.0, 0.0, 0.0) else it.normalize() }
        val up = right.cross(frame.forward).normalize()
        val toPoint = point.subtract(frame.eye)
        val ahead = toPoint.dot(frame.forward)
        val horizontal = Math.atan2(abs(toPoint.dot(right)), ahead) - (frame.halfH + frame.margin)
        val vertical = Math.atan2(abs(toPoint.dot(up)), ahead) - (frame.halfV + frame.margin)
        return Math.toDegrees(max(0.0, max(horizontal, vertical)))
    }

    fun renderHud(graphics: GuiGraphics) {
        val frame = frame ?: return
        if (!captured) return
        if (frame.frozen) drawFrozen(graphics, frame) else drawLive(graphics)
        for (point in frame.soon) dot(graphics, point, 1, YELLOW)
        panel(graphics, frame)
    }

    private fun drawLive(graphics: GuiGraphics) {
        for (i in points.indices) {
            when (marks[i]) {
                AGREED -> dot(graphics, points[i], 1, GREEN)
                GLOOMY -> dot(graphics, points[i], 1, BLUE)
                LAGGED -> dot(graphics, points[i], 1, ORANGE)
            }
        }
        for (i in points.indices) if (marks[i] == WRONG) dot(graphics, points[i], 2, RED)
    }

    private fun drawFrozen(graphics: GuiGraphics, frame: ProbeSync.Frame) {
        for (i in points.indices) {
            when (sights[i]) {
                HIDDEN -> dot(graphics, points[i], 0, WALLED)
                SEEN -> dot(graphics, points[i], 1, GREEN)
                DARK -> dot(graphics, points[i], 1, BLUE)
            }
        }
        val right = frame.forward.cross(Vec3(0.0, 1.0, 0.0)).let { if (it.lengthSqr() < 1.0e-6) Vec3(1.0, 0.0, 0.0) else it.normalize() }
        val up = right.cross(frame.forward).normalize()
        frustum(graphics, frame, right, up, 0f, WHITE)
        if (frame.halfH + frame.margin < Math.PI / 2 && frame.halfV + frame.margin < Math.PI / 2) frustum(graphics, frame, right, up, frame.margin, GREY)
        dot(graphics, frame.eye, 2, WHITE)
    }

    private fun frustum(graphics: GuiGraphics, frame: ProbeSync.Frame, right: Vec3, up: Vec3, margin: Float, colour: Int) {
        val width = tan((frame.halfH + margin).toDouble())
        val height = tan((frame.halfV + margin).toDouble())
        for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
            val edge = frame.forward.add(right.scale(sx * width)).add(up.scale(sy * height)).normalize()
            for (k in 1..FRUSTUM_DOTS) dot(graphics, frame.eye.add(edge.scale(frame.range * k.toDouble() / FRUSTUM_DOTS)), 0, colour)
        }
    }

    private fun panel(graphics: GuiGraphics, frame: ProbeSync.Frame) {
        val lines = ArrayList<Pair<String, Int>>()
        if (frame.frozen) {
            lines += "Проверка движка зрения — ЗАМОРОЖЕНО" to YELLOW
            lines += server(frame) to WHITE
            lines += "Белая точка — где была камера. Белые пунктиры — края кадра, серые — края с запасом на поворот." to WHITE
            lines += "Зелёные и синие точки сервер считал видимыми из белой точки, тёмно-красные — закрытыми стеной." to WHITE
            lines += "Обойди место и сравни. Снова «/substratum debug gaze freeze» — новый снимок, «/substratum debug gaze» — живой режим." to GREY
        } else {
            lines += "Проверка движка зрения — живой режим" to YELLOW
            lines += server(frame) to WHITE
            if (frame.windows > 0) lines += "Сквозь порталы ты смотришь ещё в ${frame.windows} мест(а) — сервер это учитывает" to WHITE
            lines += "Расчёт снимка на сервере: ${points.size} точек за %.2f мс (это цена самой проверки, не мода)".format(frame.micros / 1000.0) to GREY
            lines += "Видишь точек: $seen — совпало $agreed, сервер считает тёмными $dark" to WHITE
            lines += "Ошибки движка сейчас: за стеной $walled, за дальностью $far, вне кадра без поворота $still" to if (walled + far + still == 0) WHITE else RED
            lines += "Опережение поворота сейчас: $lagged точек, сервер отстал на ${lag.roundToInt()}°" to if (lagged == 0) WHITE else ORANGE
            lines += "Перестраховка (сервер считает видимыми, а ты не видишь): $cautious — не ошибка" to GREY
            val errors = tallies.maxOfOrNull { it.errors } ?: 0
            lines += if (errors == 0) {
                "ВЕРДИКТ: ВЕРНО — за 10 с ни одной ошибки движка" to GREEN
            } else {
                "ВЕРДИКТ: ОШИБКА — до $errors точек за 10 с ты видел, а сервер считал невидимыми" to RED
            }
            val worstLagged = tallies.maxOfOrNull { it.lagged } ?: 0
            val worstLag = tallies.maxOfOrNull { it.lag } ?: 0.0
            lines += "Опережение поворота за 10 с: до $worstLagged точек, до ${worstLag.roundToInt()}° — о рывке мыши сервер узнаёт только после него" to GREY
            lines += "■ зелёная — видишь, сервер знает   ■ синяя — видишь, для сервера там темно   ■ оранжевая — опережение поворота" to WHITE
            lines += "■ красная — ОШИБКА движка: видишь, а сервер нет   ■ жёлтая — куда скоро посмотришь (Level 0)" to WHITE
        }
        val font = Minecraft.getInstance().font
        val pose = graphics.pose()
        pose.pushPose()
        pose.scale(PANEL_SCALE, PANEL_SCALE, 1f)
        val width = lines.maxOf { font.width(it.first) } + 8
        graphics.fill(PANEL_X - 4, PANEL_Y - 4, PANEL_X + width, PANEL_Y + lines.size * LINE + 2, BACKDROP)
        lines.forEachIndexed { i, (text, colour) -> graphics.drawString(font, text, PANEL_X, PANEL_Y + i * LINE, colour, false) }
        pose.popPose()
    }

    private fun server(frame: ProbeSync.Frame): String {
        val kind = when (frame.kind) {
            Viewer.Kind.LOOKING -> "обычный взгляд"
            Viewer.Kind.SWEEPING -> "резкий поворот (считается, что видишь всё вокруг)"
            Viewer.Kind.EVERYWHERE -> "наблюдатель (видит всё)"
        }
        val perspective = when (frame.perspective) {
            ViewportSync.Perspective.FIRST_PERSON -> "1-е лицо"
            ViewportSync.Perspective.THIRD_PERSON_BACK -> "3-е лицо сзади"
            ViewportSync.Perspective.THIRD_PERSON_FRONT -> "3-е лицо спереди"
        }
        return "Сервер: $kind · запас ${Math.toDegrees(frame.margin.toDouble()).roundToInt()}° · дальность ${frame.range.roundToInt()} · " +
            "FOV ${frame.fov.roundToInt()}° · $perspective · пинг ${frame.latency} мс"
    }

    private fun dot(graphics: GuiGraphics, at: Vec3, radius: Int, colour: Int) {
        val clip = clip(at)
        if (clip.w <= 0f) return
        val x = clip.x / clip.w
        val y = clip.y / clip.w
        if (abs(x) > EDGE || abs(y) > EDGE) return
        val sx = ((x * 0.5f + 0.5f) * graphics.guiWidth()).roundToInt()
        val sy = ((0.5f - y * 0.5f) * graphics.guiHeight()).roundToInt()
        if (radius >= 2) graphics.fill(sx - radius - 1, sy - radius - 1, sx + radius + 2, sy + radius + 2, INK)
        graphics.fill(sx - radius, sy - radius, sx + radius + 1, sy + radius + 1, colour)
    }

    private fun clip(at: Vec3): Vector4f =
        Vector4f((at.x - camera.x).toFloat(), (at.y - camera.y).toFloat(), (at.z - camera.z).toFloat(), 1f).mul(viewProjection)

    private fun onScreen(at: Vec3): Boolean {
        val clip = clip(at)
        return clip.w > 0f && abs(clip.x) <= clip.w && abs(clip.y) <= clip.w && abs(clip.z) <= clip.w
    }

    private fun fogged(at: Vec3): Boolean {
        val dx = at.x - camera.x
        val dy = at.y - camera.y
        val dz = at.z - camera.z
        val distance = if (fogCylinder) max(hypot(dx, dz), abs(dy)) else Math.sqrt(dx * dx + dy * dy + dz * dz)
        return distance >= fogEnd
    }

    private fun clear(level: ClientLevel, at: Vec3): Boolean =
        level.clip(ClipContext(camera, at, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty())).type == HitResult.Type.MISS
}
