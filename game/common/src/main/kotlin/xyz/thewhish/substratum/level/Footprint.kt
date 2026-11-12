package xyz.thewhish.substratum.level

import net.minecraft.util.Mth
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

object Footprint {
    const val FRONT = 0.05
    const val BACK = 2.1
    const val HALF_WIDTH = 0.72
    const val HEIGHT = 0.82
    const val LEGS_REACH = 0.75
    const val LEGS_HEIGHT = 0.42
    const val TORSO = 0.32
    private const val TORSO_WEIGHT = 4
    private const val EDGE = 0.08
    private const val SAMPLE_HEIGHT = 0.25
    private const val TURN_STEP = 20f
    private const val TURN_STEPS = 9

    private val SHAPE = listOf(
        AABB(-HALF_WIDTH, 0.0, -LEGS_REACH, HALF_WIDTH, LEGS_HEIGHT, FRONT),
        AABB(-HALF_WIDTH, 0.0, -BACK, HALF_WIDTH, HEIGHT, -LEGS_REACH),
    )
    private val TURNS = listOf(0f) + (1..TURN_STEPS).flatMap { listOf(it * TURN_STEP, -it * TURN_STEP) }
    private val SHIFTS = listOf(Vec3.ZERO) + listOf(0.15, 0.3, 0.45).flatMap { reach ->
        (0 until 8).map { Vec3(reach * Mth.cos(it * Mth.PI / 4), 0.0, reach * Mth.sin(it * Mth.PI / 4)) }
    }
    private val ALONG =(1..8).map { -it * BACK / 8 }
    private val ACROSS = (-4..4).map { it * (HALF_WIDTH + EDGE) / 4 }.map { it to if (kotlin.math.abs(it) <= TORSO) TORSO_WEIGHT else 1 }

    fun bounds(origin: Vec3, yaw: Float): AABB {
        val corners = listOf(FRONT, -BACK).flatMap { along -> listOf(-HALF_WIDTH, HALF_WIDTH).map { across -> world(origin, yaw, across, 0.0, along) } }
        return AABB(corners.minOf { it.x }, origin.y, corners.minOf { it.z }, corners.maxOf { it.x }, origin.y + HEIGHT, corners.maxOf { it.z })
    }

    fun hit(origin: Vec3, yaw: Float, from: Vec3, to: Vec3): Boolean {
        val start = local(origin, yaw, from)
        val end = local(origin, yaw, to)
        return SHAPE.any { it.clip(start, end).isPresent }
    }

    data class Landing(val yaw: Float, val shift: Vec3)

    fun landing(origin: Vec3, yaw: Float, solid: (Vec3) -> Boolean): Landing {
        val options = SHIFTS
            .filter { !solid(origin.add(it).add(0.0, SAMPLE_HEIGHT, 0.0)) }
            .flatMap { shift -> TURNS.map { Landing(yaw + it, shift) } }
        val scored = options.asSequence().map { it to blocked(origin.add(it.shift), it.yaw, solid) }
        return (scored.firstOrNull { it.second == 0 } ?: scored.minBy { it.second }).first
    }

    fun blocked(origin: Vec3, yaw: Float, solid: (Vec3) -> Boolean): Int =
        ALONG.sumOf { along -> ACROSS.sumOf { (across, weight) -> if (solid(world(origin, yaw, across, SAMPLE_HEIGHT, along))) weight else 0 } }

    private fun forward(yaw: Float): Vec3 = Vec3(-Mth.sin(yaw * Mth.DEG_TO_RAD).toDouble(), 0.0, Mth.cos(yaw * Mth.DEG_TO_RAD).toDouble())

    private fun world(origin: Vec3, yaw: Float, across: Double, up: Double, along: Double): Vec3 {
        val forward = forward(yaw)
        return origin.add(forward.z * across + forward.x * along, up, -forward.x * across + forward.z * along)
    }

    private fun local(origin: Vec3, yaw: Float, point: Vec3): Vec3 {
        val forward = forward(yaw)
        val offset = point.subtract(origin)
        return Vec3(offset.x * forward.z - offset.z * forward.x, offset.y, offset.x * forward.x + offset.z * forward.z)
    }
}
