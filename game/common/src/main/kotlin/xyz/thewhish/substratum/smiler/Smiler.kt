package xyz.thewhish.substratum.smiler

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import xyz.thewhish.substratum.gaze.Gaze

class Smiler(type: EntityType<Smiler>, level: Level) : Entity(type, level) {

    private var facingLost = true
    var vanished = false
    var drawnFrame = -1L
    var portalFrame = -1L
    var sweep: AABB? = null

    fun glitch(ticks: Int) {
        require(ticks > 0) { "a glitch needs at least one tick, got $ticks" }
        entityData.set(GLITCH_UNTIL, maxOf(entityData.get(GLITCH_UNTIL), level().gameTime + ticks))
    }

    fun glitching(): Boolean = level().gameTime < entityData.get(GLITCH_UNTIL)

    fun leave() {
        entityData.set(LEAVING, true)
    }

    fun leaving(): Boolean = entityData.get(LEAVING)

    fun lurk(hold: Int, fake: Boolean) {
        require(hold > 0) { "a lurking smiler needs a hold, got $hold" }
        entityData.set(HOLD, hold)
        entityData.set(FAKE, fake)
    }

    fun lurking(): Boolean = entityData.get(HOLD) > 0

    fun hold(): Int = entityData.get(HOLD)

    fun fake(): Boolean = entityData.get(FAKE)

    fun spring(victim: Int, eye: Vec3) {
        entityData.set(REVEAL, eye.toVector3f())
        entityData.set(VICTIM, victim)
    }

    fun victim(): Int = entityData.get(VICTIM)

    fun reveal(): Vec3 = Vec3(entityData.get(REVEAL))

    fun fizzle() {
        entityData.set(FIZZLED, true)
    }

    fun fizzled(): Boolean = entityData.get(FIZZLED)

    fun turnTowards(viewer: Vec3) {
        val target = Mth.wrapDegrees((Mth.atan2(x - viewer.x, viewer.z - z) * Mth.RAD_TO_DEG).toFloat())
        if (facingLost) {
            facingLost = false
            yRot = target
            yRotO = target
            return
        }
        val gap = Mth.wrapDegrees(target - yRot)
        yRot = Mth.wrapDegrees(yRot + Mth.clamp(gap * TURN_EASE, -TURN_LIMIT, TURN_LIMIT))
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(GLITCH_UNTIL, 0L)
        builder.define(LEAVING, false)
        builder.define(HOLD, 0)
        builder.define(FAKE, false)
        builder.define(VICTIM, NO_VICTIM)
        builder.define(REVEAL, Vector3f())
        builder.define(FIZZLED, false)
    }

    override fun tick() = Unit

    override fun lerpTo(x: Double, y: Double, z: Double, yRot: Float, xRot: Float, steps: Int) {
        if (position().distanceToSqr(x, y, z) < RESYNC_SQR) return
        setPos(x, y, z)
        setOldPosAndRot()
        facingLost = true
    }

    override fun shouldRenderAtSqrDistance(distance: Double): Boolean = distance < Gaze.GLOW_RANGE * Gaze.GLOW_RANGE

    override fun getBoundingBoxForCulling(): AABB = super.getBoundingBoxForCulling().inflate(FACE_OVERHANG).let { box -> sweep?.minmax(box) ?: box }

    override fun readAdditionalSaveData(tag: CompoundTag) = Unit

    override fun addAdditionalSaveData(tag: CompoundTag) = Unit

    companion object {
        const val WIDTH = 1.25f
        const val HEIGHT = 2.5f
        const val EYES = 2.4f
        const val FACE_CENTRE = 2.1
        const val FACE_HALF = 0.75
        const val TRACKING_CHUNKS = 6
        const val NO_VICTIM = -1
        const val FACE_CLEAR = Gaze.GLOW_RANGE - 8.0
        private const val FACE_OVERHANG = 0.4
        private const val TURN_EASE = 0.45f
        private const val TURN_LIMIT = 24f
        private const val RESYNC_SQR = 1.0E-4

        private val GLITCH_UNTIL: EntityDataAccessor<Long> =
            SynchedEntityData.defineId(Smiler::class.java, EntityDataSerializers.LONG)
        private val LEAVING: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(Smiler::class.java, EntityDataSerializers.BOOLEAN)
        private val HOLD: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(Smiler::class.java, EntityDataSerializers.INT)
        private val FAKE: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(Smiler::class.java, EntityDataSerializers.BOOLEAN)
        private val VICTIM: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(Smiler::class.java, EntityDataSerializers.INT)
        private val REVEAL: EntityDataAccessor<Vector3f> =
            SynchedEntityData.defineId(Smiler::class.java, EntityDataSerializers.VECTOR3)
        private val FIZZLED: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(Smiler::class.java, EntityDataSerializers.BOOLEAN)
    }
}
