package xyz.thewhish.substratum.smiler.client

import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.EntityBoundSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import qouteall.imm_ptl.core.render.context_management.PortalRendering
import xyz.thewhish.substratum.client.LampFlicker
import xyz.thewhish.substratum.client.Shock
import xyz.thewhish.substratum.level.SubstratumLevels
import xyz.thewhish.substratum.network.SmilerSync
import xyz.thewhish.substratum.registry.ModSounds
import xyz.thewhish.substratum.smiler.Ambush
import xyz.thewhish.substratum.smiler.Smiler
import kotlin.math.exp

object Lunge {

    const val LENGTH = 9f
    private const val OBSERVER_LAG = 3f
    private const val STARE = Ambush.STARE.toFloat()
    private const val DISSOLVE = Ambush.DISSOLVE.toFloat()
    private const val DISSOLVE_NEAR = 6f
    private const val LONGEST = Ambush.LUNGE / 20.0
    private const val STOP = 0.35
    private const val START_SPEED = 3.0
    private const val SURGE = 120.0
    private const val TOP_SPEED = 45.0
    private const val TURN = 9.0
    private const val CLOSING = 2.5
    private const val LONGEST_FRAME = 0.1
    private const val TRAIL_STEP = 0.4
    private const val TRAIL = 64
    private const val NEAR_TRAIL = 0.01
    private const val FLINCH_RISE = 1.8f
    private const val FLINCH_REACH = 0.35f
    private const val FLINCH_SPREAD = 0.12f
    private const val FLINCH_END = 14f
    private const val SWEEP_MARGIN = 2.0

    class View(val centre: Vec3, val dissolve: Float, val near: Boolean)

    private class Event(val hold: Float, val fake: Boolean) {
        var seen = Float.NaN
        var start = Float.NaN
        var victim = Smiler.NO_VICTIM
        var reveal: Vec3 = Vec3.ZERO
        var lunge = Float.NaN
        var impact = Float.NaN
        var fade = Float.NaN
        var fadeLength = DISSOLVE
        var anchor: Vec3? = null
        var at: Vec3? = null
        var heading: Vec3 = Vec3.ZERO
        var moved = Float.NaN
        val trail = ArrayDeque<Vec3>()
        var rush: Rush? = null
    }

    private class Rush(private val event: Event, inside: Boolean) :
        AbstractTickableSoundInstance(ModSounds.SMILER_RUSH.get(), SoundSource.HOSTILE, SoundInstance.createUnseededRandom()) {

        init {
            relative = inside
            if (inside) attenuation = SoundInstance.Attenuation.NONE
            follow()
        }

        override fun tick() {
            if (!event.impact.isNaN()) stop() else follow()
        }

        private fun follow() {
            if (relative) return
            val at = event.at ?: return
            x = at.x
            y = at.y
            z = at.z
        }

        fun end() = stop()
    }

    private val events = HashMap<Int, Event>()
    private var clock = 0L

    fun tick(minecraft: Minecraft) {
        val level = minecraft.level ?: return
        if (minecraft.isPaused) return
        clock++
        events.entries.removeIf { (id, event) -> ((level.getEntity(id) as? Smiler)?.lurking() != true).also { if (it) event.rush?.end() } }
    }

    fun reset() {
        events.values.forEach { it.rush?.end() }
        events.clear()
    }

    fun now(partialTick: Float): Float = clock + partialTick

    @JvmStatic
    fun beforeFrame(partialTick: Float) {
        if (PortalRendering.isRendering()) return
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level?.takeIf { it.dimension() == SubstratumLevels.LEVEL_0 } ?: return
        val now = now(partialTick)
        for ((id, event) in events) {
            if (event.lunge.isNaN() || now < event.lunge || !event.impact.isNaN()) continue
            val smiler = level.getEntity(id) as? Smiler ?: continue
            chase(minecraft, level, smiler, event, now, partialTick)
        }
    }

    @JvmStatic
    fun afterFrame(camera: Camera, partialTick: Float) {
        if (PortalRendering.isRendering()) return
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level?.takeIf { it.dimension() == SubstratumLevels.LEVEL_0 } ?: return
        val now = now(partialTick)
        for (entity in level.entitiesForRendering()) {
            if (entity !is Smiler || !entity.lurking()) continue
            val event = events.getOrPut(entity.id) { Event(entity.hold().toFloat(), entity.fake()) }
            follow(minecraft, level, camera, entity, event, now, partialTick)
            entity.sweep = sweep(level, entity, event, partialTick)
        }
    }

    private fun follow(minecraft: Minecraft, level: ClientLevel, camera: Camera, smiler: Smiler, event: Event, now: Float, partialTick: Float) {
        val player = minecraft.player ?: return
        if (event.victim == Smiler.NO_VICTIM && smiler.victim() != Smiler.NO_VICTIM) spring(smiler, event, player.id, now)
        if (event.victim == Smiler.NO_VICTIM && event.fade.isNaN()) {
            if (smiler.fizzled()) return crumble(level, smiler, event, now, centre(smiler), DISSOLVE)
            if (event.seen.isNaN() && player.isAlive && !player.isSpectator && minecraft.cameraEntity === player && Vanish.visible(level, camera, smiler)) {
                event.seen = now
                SmilerSync.spotted(smiler.id, player.getEyePosition(partialTick))
            }
            return
        }
        if (event.fake && event.anchor == null && !event.fade.isNaN() && now >= event.fade) crumble(level, smiler, event, event.fade, centre(smiler), DISSOLVE)
    }

    private fun spring(smiler: Smiler, event: Event, self: Int, now: Float) {
        event.victim = smiler.victim()
        event.reveal = smiler.reveal()
        val mine = event.victim == self && !event.seen.isNaN()
        event.start = if (mine) event.seen else now + OBSERVER_LAG
        if (event.fake) {
            event.fade = event.start + event.hold + STARE
            return
        }
        event.lunge = maxOf(event.start + event.hold, if (mine) now else event.start)
    }

    private fun chase(minecraft: Minecraft, level: ClientLevel, smiler: Smiler, event: Event, now: Float, partialTick: Float) {
        val eye = level.getEntity(event.victim)?.getEyePosition(partialTick) ?: event.reveal
        val at = event.at ?: launch(minecraft, smiler, event, eye)
        if (event.trail.last().distanceToSqr(eye) > TRAIL_STEP * TRAIL_STEP) {
            event.trail.addLast(eye)
            if (event.trail.size > TRAIL) event.trail.removeAt(1)
        }
        val elapsed = (now - event.lunge) / 20.0
        val frame = ((now - event.moved) / 20.0).coerceIn(0.0, LONGEST_FRAME)
        event.moved = now
        val sees = clear(level, at, eye)
        val target = if (sees) eye else event.trail.lastOrNull { it.distanceToSqr(at) > NEAR_TRAIL && clear(level, at, it) } ?: event.trail.first()
        val toward = target.subtract(at)
        val distance = toward.length()
        val step = minOf(TOP_SPEED, START_SPEED + SURGE * elapsed * elapsed) * frame
        if (sees && distance - STOP <= step || elapsed > LONGEST) {
            val face = eye.subtract(at).let { if (it.lengthSqr() < 1.0E-6) event.heading else it.normalize() }
            return hit(minecraft, level, smiler, event, now, eye.subtract(face.scale(STOP)))
        }
        val wanted = if (distance < 1.0E-6) event.heading else toward.scale(1.0 / distance)
        var heading = if (distance < CLOSING) wanted else event.heading.add(wanted.subtract(event.heading).scale(minOf(1.0, TURN * frame))).normalize()
        if (!clear(level, at, at.add(heading.scale(step + STOP)))) heading = wanted
        event.heading = heading
        event.at = at.add(heading.scale(step))
    }

    private fun launch(minecraft: Minecraft, smiler: Smiler, event: Event, eye: Vec3): Vec3 {
        val from = centre(smiler)
        event.at = from
        event.heading = eye.subtract(from).normalize()
        event.moved = event.lunge
        event.trail.addLast(event.reveal)
        event.trail.addLast(eye)
        val inside = experiencing(minecraft, event)
        if (inside) Shock.begin(event.victim, event.lunge, from, owner = event.victim == minecraft.player?.id)
        event.rush = Rush(event, inside).also(minecraft.soundManager::play)
        return from
    }

    private fun hit(minecraft: Minecraft, level: ClientLevel, smiler: Smiler, event: Event, now: Float, anchor: Vec3) {
        event.impact = now
        event.at = anchor
        event.rush?.end()
        val near = experiencing(minecraft, event)
        LampFlicker.stutter(level, anchor)
        crumble(level, smiler, event, now, anchor, if (near) DISSOLVE_NEAR else DISSOLVE)
        if (near) return Shock.hit(now)
        level.playLocalSound(anchor.x, anchor.y, anchor.z, ModSounds.SMILER_HIT.get(), SoundSource.HOSTILE, 1f, 1f, false)
        level.getEntity(event.victim)?.let { minecraft.soundManager.play(EntityBoundSoundInstance(ModSounds.SHOCK_BREATH.get(), SoundSource.PLAYERS, 1f, 1f, it, smiler.id.toLong())) }
    }

    private fun crumble(level: ClientLevel, smiler: Smiler, event: Event, at: Float, anchor: Vec3, length: Float) {
        event.fade = at
        event.fadeLength = length
        event.anchor = anchor
        level.playLocalSound(anchor.x, anchor.y, anchor.z, ModSounds.SMILER_DISSOLVE.get(), SoundSource.HOSTILE, 1f, 0.9f + 0.2f * (smiler.id % 7) / 6f, false)
    }

    private fun clear(level: ClientLevel, from: Vec3, to: Vec3): Boolean =
        level.clip(ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty())).type == HitResult.Type.MISS

    private fun sweep(level: ClientLevel, smiler: Smiler, event: Event, partialTick: Float): AABB? {
        if (event.victim == Smiler.NO_VICTIM) return null
        val eye = level.getEntity(event.victim)?.getEyePosition(partialTick) ?: event.reveal
        val at = event.at ?: centre(smiler)
        return AABB(centre(smiler), event.reveal).minmax(AABB(eye, eye)).minmax(AABB(at, at)).inflate(SWEEP_MARGIN)
    }

    private fun experiencing(minecraft: Minecraft, event: Event): Boolean = minecraft.cameraEntity?.id == event.victim

    private fun centre(smiler: Smiler): Vec3 = smiler.position().add(0.0, Smiler.FACE_CENTRE, 0.0)

    fun view(smiler: Smiler, partialTick: Float): View? {
        if (!smiler.lurking()) return null
        val event = events[smiler.id] ?: return View(centre(smiler), -1f, false)
        val now = now(partialTick)
        val anchor = event.anchor
        if (!event.fade.isNaN() && now >= event.fade && anchor != null) {
            return View(anchor, ((now - event.fade) / event.fadeLength).coerceAtLeast(0f), event.fadeLength == DISSOLVE_NEAR)
        }
        return View(event.at ?: centre(smiler), -1f, false)
    }

    fun flinch(model: PlayerModel<*>, entity: LivingEntity) {
        val event = events.values.firstOrNull { it.victim == entity.id && !it.impact.isNaN() } ?: return
        val t = now(Minecraft.getInstance().timer.getGameTimeDeltaPartialTick(false)) - event.impact
        if (t < 0f || t > FLINCH_END) return
        val kick = recoil(t)
        model.rightArm.xRot -= FLINCH_REACH * kick
        model.leftArm.xRot -= FLINCH_REACH * kick
        model.rightArm.zRot += FLINCH_SPREAD * kick
        model.leftArm.zRot -= FLINCH_SPREAD * kick
    }

    fun recoil(t: Float): Float = if (t <= 0f) 0f else t / FLINCH_RISE * exp(1f - t / FLINCH_RISE)
}
