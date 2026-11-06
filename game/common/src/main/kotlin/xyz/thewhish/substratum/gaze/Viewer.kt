package xyz.thewhish.substratum.gaze

import net.minecraft.world.phys.Vec3
import xyz.thewhish.substratum.network.ViewportSync
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

class Viewer(
    val uuid: UUID,
    val eye: Vec3,
    val forward: Vec3,
    val viewport: ViewportSync.Viewport,
    val margin: Double = MIN_MARGIN,
    val range: Double = viewport.haze.toDouble(),
    val kind: Kind = Kind.LOOKING,
    val cylindrical: Boolean = false,
    val window: Window? = null,
    val stride: Double = 0.0,
) {

    enum class Kind { LOOKING, SWEEPING, EVERYWHERE }

    class Window(val centre: Vec3, val normal: Vec3, val axisW: Vec3, val axisH: Vec3, val halfW: Double, val halfH: Double) {

        fun crossing(from: Vec3, to: Vec3): Vec3? {
            val along = to.subtract(from)
            val facing = along.dot(normal)
            if (abs(facing) < 1.0e-9) return null
            val t = centre.subtract(from).dot(normal) / facing
            if (t < 0.0 || t > 1.0) return null
            val hit = from.add(along.scale(t))
            val local = hit.subtract(centre)
            return if (abs(local.dot(axisW)) <= halfW && abs(local.dot(axisH)) <= halfH) hit else null
        }
    }

    val right: Vec3 = forward.cross(WORLD_UP).let { if (it.lengthSqr() < 1.0e-6) Vec3(1.0, 0.0, 0.0) else it.normalize() }
    val up: Vec3 = right.cross(forward).normalize()
    val halfH: Double = viewport.halfHFov
    val halfV: Double = viewport.halfVFov
    private val reach: Double =
        if (halfH + margin >= PI / 2 || halfV + margin >= PI / 2) PI else atan(hypot(tan(halfH + margin), tan(halfV + margin)))

    fun frames(point: Vec3): Boolean {
        if (kind != Kind.LOOKING) return true
        return within(point, margin)
    }

    fun mayFrame(centre: Vec3, radius: Double): Boolean {
        if (kind != Kind.LOOKING || reach >= PI) return true
        val toCentre = centre.subtract(eye)
        val distance = toCentre.length()
        if (distance <= radius) return true
        val spread = reach + asin(radius / distance)
        return spread >= PI || toCentre.dot(forward) >= distance * cos(spread)
    }

    fun onScreen(point: Vec3): Boolean = within(point, 0.0)

    fun steady(): Viewer = Viewer(uuid, eye, forward, viewport, margin, range, kind, cylindrical, window)

    fun through(eye: Vec3, forward: Vec3, window: Window): Viewer = Viewer(uuid, eye, forward, viewport, margin, range, kind, cylindrical, window)

    fun reaches(point: Vec3, range: Double): Boolean {
        val dx = point.x - eye.x
        val dz = point.z - eye.z
        val dy = if (cylindrical) 0.0 else point.y - eye.y
        return dx * dx + dy * dy + dz * dz <= range * range
    }

    fun centrality(point: Vec3): Double {
        val toPoint = point.subtract(eye)
        val ahead = toPoint.dot(forward)
        if (ahead <= 0.0) return 0.0
        val off = max(atan2(abs(toPoint.dot(right)), ahead) / halfH, atan2(abs(toPoint.dot(up)), ahead) / halfV)
        return (1.0 - off).coerceIn(0.0, 1.0)
    }

    private val screenTanH = tan(halfH)
    private val screenTanV = tan(halfV)
    private val frameTanH = if (halfH + margin < PI / 2) tan(halfH + margin) else Double.NaN
    private val frameTanV = if (halfV + margin < PI / 2) tan(halfV + margin) else Double.NaN

    private fun within(point: Vec3, margin: Double): Boolean {
        val dx = point.x - eye.x
        val dy = point.y - eye.y
        val dz = point.z - eye.z
        val ahead = dx * forward.x + dy * forward.y + dz * forward.z
        val sideways = abs(dx * right.x + dy * right.y + dz * right.z)
        val upward = abs(dx * up.x + dy * up.y + dz * up.z)
        val tanH = if (margin == 0.0) screenTanH else frameTanH
        val tanV = if (margin == 0.0) screenTanV else frameTanV
        if (!tanH.isNaN() && !tanV.isNaN()) return ahead >= 0.0 && sideways <= ahead * tanH && upward <= ahead * tanV
        return atan2(sideways, ahead) <= halfH + margin && atan2(upward, ahead) <= halfV + margin
    }

    companion object {
        const val CAMERA_DISTANCE = 4.0
        const val CAMERA_PROBE = 0.1
        val MIN_MARGIN: Double = Math.toRadians(6.0)
        val SWEEP: Double = Math.toRadians(12.0)
        private const val DISPLAY_TICKS = 3
        private const val STEP = 0.4
        private const val MAX_STRIDE = 6.0
        private const val BLIND_RANGE = 5.0
        private const val BLIND_FADE = 20.0
        private val WORLD_UP = Vec3(0.0, 1.0, 0.0)

        fun ahead(latencyMs: Int): Int = latencyMs.coerceAtLeast(0) / 50 + DISPLAY_TICKS

        fun margin(turnPerTick: Double, latencyMs: Int): Double = min(PI, max(MIN_MARGIN, turnPerTick * ahead(latencyMs)))

        fun stride(speedPerTick: Double, latencyMs: Int): Double = min(MAX_STRIDE, max(STEP, speedPerTick) * ahead(latencyMs))

        fun kind(spectator: Boolean, turnPerTick: Double, margin: Double): Kind = when {
            spectator -> Kind.EVERYWHERE
            turnPerTick >= SWEEP || margin >= PI -> Kind.SWEEPING
            else -> Kind.LOOKING
        }

        fun range(haze: Double, blindTicks: Int?): Double {
            if (blindTicks == null) return haze
            val fade = if (blindTicks < 0) 1.0 else min(1.0, blindTicks / BLIND_FADE)
            return min(haze, haze + (BLIND_RANGE - haze) * fade)
        }

        fun camera(eye: Vec3, look: Vec3, perspective: ViewportSync.Perspective, zoom: (Vec3) -> Double): Pair<Vec3, Vec3> =
            when (perspective) {
                ViewportSync.Perspective.FIRST_PERSON -> eye to look
                ViewportSync.Perspective.THIRD_PERSON_BACK -> look.scale(-1.0).let { eye.add(it.scale(zoom(it))) } to look
                ViewportSync.Perspective.THIRD_PERSON_FRONT -> eye.add(look.scale(zoom(look))) to look.scale(-1.0)
            }

        fun probes(eye: Vec3): List<Vec3> = (0 until 8).map {
            Vec3(
                eye.x + ((it and 1) * 2 - 1) * CAMERA_PROBE,
                eye.y + ((it shr 1 and 1) * 2 - 1) * CAMERA_PROBE,
                eye.z + ((it shr 2 and 1) * 2 - 1) * CAMERA_PROBE,
            )
        }
    }
}
