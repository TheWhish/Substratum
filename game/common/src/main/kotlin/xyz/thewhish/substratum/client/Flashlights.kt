package xyz.thewhish.substratum.client

import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f
import xyz.thewhish.substratum.item.FlashlightItem
import xyz.thewhish.substratum.level.SubstratumLevels
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

object Flashlights {

    const val MAX = 4
    const val NEAR = 9f
    const val RANGE = 52f
    const val FADE_FROM = 32f
    const val GAIN = 1.6f
    val COS_INNER = cos(Math.toRadians(11.0)).toFloat()
    val COS_OUTER = cos(Math.toRadians(32.0)).toFloat()
    val COLOR = floatArrayOf(1.0f, 0.8f, 0.58f)

    private const val REACH = RANGE + 16.0
    private const val LENS_FORWARD = 0.8
    private const val LENS_SIDE = 0.34
    private const val LENS_DROP = 0.24
    private const val LENS_PULL = 0.1
    private const val GRIP_FORWARD = 0.35
    private const val GRIP_SIDE = 0.38
    private const val GRIP_DROP = 0.8
    private const val LAG_SECONDS = 0.045f
    private const val FAINT = 0.002f
    private const val OWN_HAZE = 0.25f
    private const val DAZZLE_MAX = 0.22f
    private const val DAZZLE_GAIN = 1.5f
    private const val DAZZLE_SECONDS = 0.35f
    private const val FACE_FROM = 0.2f
    private const val FACE_FULL = 0.8f

    class Beam {
        var x = 0.0
        var y = 0.0
        var z = 0.0
        val direction = Vector3f()
        var power = 0f
        var haze = 1f
        var owner = 0
    }

    val beams = Array(MAX) { Beam() }
    var count = 0
        private set

    var dazzle = 0f
        private set

    private val aim = Vector3f()
    private var aiming = false
    private var viewerId = 0
    private var lastFrame = 0L

    fun update(minecraft: Minecraft, partialTick: Float) {
        val now = System.nanoTime()
        val seconds = if (lastFrame == 0L) 0f else (now - lastFrame) / 1e9f
        lastFrame = now
        count = 0
        val level = minecraft.level?.takeIf { it.dimension() == SubstratumLevels.LEVEL_0 }
        val viewer = minecraft.cameraEntity as? Player ?: minecraft.player
        if (level == null || viewer == null) {
            aiming = false
            dazzle = 0f
            return
        }
        if (hand(viewer) == null || viewer.id != viewerId) aiming = false
        viewerId = viewer.id
        val camera = minecraft.gameRenderer.mainCamera.position
        level.players()
            .filter { hand(it) != null && !it.isSpectator && (it === viewer || it.distanceToSqr(camera) < REACH * REACH) }
            .sortedBy { if (it === viewer) -1.0 else it.distanceToSqr(camera) }
            .take(MAX)
            .forEach { aim(beams[count++], it, it === viewer, minecraft, partialTick, seconds) }
        squint(level, viewer, partialTick, seconds)
    }

    private fun squint(level: Level, viewer: Player, partialTick: Float, seconds: Float) {
        val eye = viewer.getEyePosition(partialTick)
        val look = viewer.getViewVector(partialTick)
        var glare = 0f
        for (i in 0 until count) {
            val beam = beams[i]
            if (beam.owner == viewer.id) continue
            val amount = reach(beam, eye) * facing(beam, eye, look)
            if (amount >= FAINT && !blocked(level, viewer, beam, eye)) glare += amount
        }
        val target = DAZZLE_MAX * (1f - exp(-glare * DAZZLE_GAIN))
        dazzle += (target - dazzle) * (1f - exp(-seconds / DAZZLE_SECONDS))
    }

    fun light(level: Level, entity: Entity, point: Vec3): Float {
        var sum = 0f
        for (i in 0 until count) {
            val beam = beams[i]
            if (beam.owner == entity.id) continue
            val amount = reach(beam, point)
            if (amount < FAINT) continue
            if (blocked(level, entity, beam, point)) continue
            sum += amount
        }
        return sum
    }

    private fun hand(player: Player): InteractionHand? =
        InteractionHand.entries.firstOrNull { FlashlightItem.power(player.getItemInHand(it)) > 0f }

    private fun aim(beam: Beam, player: Player, own: Boolean, minecraft: Minecraft, partialTick: Float, seconds: Float) {
        val hand = hand(player) ?: return
        val arm = if (hand == InteractionHand.MAIN_HAND) player.mainArm else player.mainArm.opposite
        val side = if (arm == HumanoidArm.RIGHT) 1.0 else -1.0
        val camera = minecraft.gameRenderer.mainCamera
        val falling = if (own && minecraft.options.cameraType.isFirstPerson) null else Collapse.look(player, partialTick)
        val origin = when {
            falling != null -> held(falling.first, falling.second, side)
            own && minecraft.options.cameraType.isFirstPerson -> lens(camera, player, side)
            else -> grip(player, side, partialTick)
        }
        beam.x = origin.x
        beam.y = origin.y
        beam.z = origin.z
        when {
            falling != null -> falling.second.transform(beam.direction.set(0f, 0f, -1f))
            own && minecraft.options.cameraType.isFirstPerson -> beam.direction.set(camera.lookVector)
            else -> player.getViewVector(partialTick).let { beam.direction.set(it.x, it.y, it.z) }
        }
        if (own) follow(beam.direction, seconds)
        beam.power = FlashlightItem.power(player.getItemInHand(hand))
        beam.haze = if (own) OWN_HAZE else 1f
        beam.owner = player.id
    }

    private fun held(eye: Vec3, rotation: Quaternionf, side: Double): Vec3 {
        val offset = rotation.transform(Vector3f((GRIP_SIDE * side).toFloat(), -GRIP_DROP.toFloat(), -GRIP_FORWARD.toFloat()))
        return eye.add(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble())
    }

    private fun lens(camera: Camera, player: Player, side: Double): Vec3 {
        val eye = camera.position
        val look = camera.lookVector
        val up = camera.upVector
        val left = camera.leftVector
        val lens = eye.add(
            look.x * LENS_FORWARD - left.x * LENS_SIDE * side - up.x * LENS_DROP,
            look.y * LENS_FORWARD - left.y * LENS_SIDE * side - up.y * LENS_DROP,
            look.z * LENS_FORWARD - left.z * LENS_SIDE * side - up.z * LENS_DROP,
        )
        val hit = player.level().clip(ClipContext(eye, lens, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        if (hit.type == HitResult.Type.MISS) return lens
        return hit.location.add(eye.subtract(hit.location).normalize().scale(LENS_PULL))
    }

    private fun grip(player: Player, side: Double, partialTick: Float): Vec3 {
        val yaw = Math.toRadians(player.getViewYRot(partialTick).toDouble())
        val eye = player.getEyePosition(partialTick)
        return eye.add(-sin(yaw) * GRIP_FORWARD - cos(yaw) * GRIP_SIDE * side, -GRIP_DROP, cos(yaw) * GRIP_FORWARD - sin(yaw) * GRIP_SIDE * side)
    }

    private fun follow(target: Vector3f, seconds: Float) {
        if (aiming && aim.dot(target) > 0f) aim.lerp(target, 1f - exp(-seconds / LAG_SECONDS)).normalize() else aim.set(target)
        aiming = true
        target.set(aim)
    }

    private fun reach(beam: Beam, point: Vec3): Float {
        val dx = (point.x - beam.x).toFloat()
        val dy = (point.y - beam.y).toFloat()
        val dz = (point.z - beam.z).toFloat()
        val d2 = dx * dx + dy * dy + dz * dz
        if (d2 >= RANGE * RANGE || d2 < 1e-6f) return 0f
        val d = sqrt(d2)
        val cone = smoothstep(COS_OUTER, COS_INNER, beam.direction.dot(dx, dy, dz) / d)
        return beam.power * GAIN * cone * cone / (1f + d2 / (NEAR * NEAR)) * (1f - smoothstep(FADE_FROM, RANGE, d))
    }

    private fun facing(beam: Beam, eye: Vec3, look: Vec3): Float {
        val dx = beam.x - eye.x
        val dy = beam.y - eye.y
        val dz = beam.z - eye.z
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        if (distance < 1e-3) return 0f
        return smoothstep(FACE_FROM, FACE_FULL, ((dx * look.x + dy * look.y + dz * look.z) / distance).toFloat())
    }

    private fun blocked(level: Level, entity: Entity, beam: Beam, point: Vec3): Boolean =
        level.clip(ClipContext(Vec3(beam.x, beam.y, beam.z), point, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, entity)).type != HitResult.Type.MISS

    private fun smoothstep(from: Float, to: Float, value: Float): Float {
        val t = ((value - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
