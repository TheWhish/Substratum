package xyz.thewhish.substratum.rift

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import qouteall.imm_ptl.core.api.PortalAPI
import qouteall.imm_ptl.core.portal.Portal
import qouteall.q_misc_util.my_util.DQuaternion
import xyz.thewhish.substratum.level.Epoch

object RiftPortals {

    private const val SLIT_HEIGHT = 2.0

    private const val TAG = "substratum:rift"

    private val stale = ArrayList<Portal>()

    private val tag: String get() = "$TAG@${Epoch.id}"

    fun register() {
        TickEvent.SERVER_POST.register { sweep() }
        LifecycleEvent.SERVER_STOPPING.register { stale.clear() }
    }

    fun onLoad(entity: Entity) {
        if (entity is Portal && entity.portalTag?.startsWith(TAG) == true && entity.portalTag != tag) stale.add(entity)
    }

    private fun sweep() {
        if (stale.isEmpty()) return
        stale.forEach { if (!it.isRemoved) it.discard() }
        stale.clear()
    }

    fun open(level: ServerLevel, link: PortalLink, targetDimension: ResourceKey<Level>) {
        close(level, link, targetDimension)
        val portal = Portal(Portal.ENTITY_TYPE, level)
        PortalAPI.setPortalOrthodoxShape(portal, link.mouth, slitBox(link))
        PortalAPI.setPortalTransformation(
            portal,
            targetDimension,
            destination(link),
            yawBetween(link.forward, link.out),
            1.0
        )
        portal.portalTag = tag
        level.addFreshEntity(portal)
        val reverse = PortalAPI.createReversePortal(portal)
        reverse.portalTag = tag
        reverse.level().addFreshEntity(reverse)
    }

    fun close(level: ServerLevel, link: PortalLink, targetDimension: ResourceKey<Level>) {
        discard(level, link.rift)
        level.server.getLevel(targetDimension)?.let { discard(it, link.target) }
    }

    private fun discard(level: ServerLevel, around: BlockPos) {
        level.getEntitiesOfClass(Portal::class.java, AABB(around).inflate(2.0)) { it.portalTag?.startsWith(TAG) == true }
            .forEach { it.discard() }
    }

    internal fun slitBox(link: PortalLink): AABB {
        val half = RiftCutBlock.CUT / 2.0
        val x = link.rift.x.toDouble()
        val y = link.rift.y.toDouble()
        val z = link.rift.z.toDouble()
        return if (link.mouth.axis == Direction.Axis.Z) {
            AABB(x + 0.5 - half, y, z, x + 0.5 + half, y + SLIT_HEIGHT, z + 1.0)
        } else {
            AABB(x, y, z + 0.5 - half, x + 1.0, y + SLIT_HEIGHT, z + 0.5 + half)
        }
    }

    internal fun destination(link: PortalLink): Vec3 = Vec3(
        link.target.x + 0.5 + link.out.stepX * 0.5,
        link.target.y + SLIT_HEIGHT / 2.0,
        link.target.z + 0.5 + link.out.stepZ * 0.5
    )

    internal fun destinationBox(link: PortalLink): AABB = corridorFace(link.target, link.out)

    internal fun corridorFace(pos: BlockPos, out: Direction): AABB {
        val half = RiftCutBlock.CUT / 2.0
        val face = Vec3(pos.x + 0.5 + out.stepX * 0.5, pos.y + SLIT_HEIGHT / 2.0, pos.z + 0.5 + out.stepZ * 0.5)
        return if (out.axis == Direction.Axis.Z) {
            AABB(face.x - half, face.y - SLIT_HEIGHT / 2.0, face.z - 0.5, face.x + half, face.y + SLIT_HEIGHT / 2.0, face.z + 0.5)
        } else {
            AABB(face.x - 0.5, face.y - SLIT_HEIGHT / 2.0, face.z - half, face.x + 0.5, face.y + SLIT_HEIGHT / 2.0, face.z + half)
        }
    }

    private fun step(direction: Direction): Vec3 =
        Vec3(direction.stepX.toDouble(), 0.0, direction.stepZ.toDouble())

    private fun yawBetween(forward: Direction, out: Direction): DQuaternion =
        if (out == forward.opposite) {
            DQuaternion.rotationByRadians(Vec3(0.0, 1.0, 0.0), Math.PI)
        } else {
            DQuaternion.getRotationBetween(step(forward), step(out))
        }
}
