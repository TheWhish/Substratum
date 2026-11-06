package xyz.thewhish.substratum.rift

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import qouteall.imm_ptl.core.api.PortalAPI
import qouteall.imm_ptl.core.portal.Portal
import qouteall.q_misc_util.my_util.DQuaternion
import xyz.thewhish.substratum.worldgen.MazeLayout

object PitPortals {

    const val EXIT_TAG = "substratum:pit_exit"
    const val TELEPORT_TAG = "substratum:pit_teleport"

    fun open(floorLevel: ServerLevel, holeCenter: BlockPos, targetDimension: ResourceKey<Level>, destination: Vec3, tag: String): Portal {
        val portal = Portal(Portal.ENTITY_TYPE, floorLevel)
        PortalAPI.setPortalOrthodoxShape(portal, Direction.UP, box(holeCenter))
        faceUp(portal)
        PortalAPI.setPortalTransformation(portal, targetDimension, destination, DQuaternion.identity, 1.0)
        portal.portalTag = tag
        portal.setIsVisible(false)
        portal.setInteractable(false)
        portal.setCrossPortalCollisionEnabled(false)
        floorLevel.addFreshEntity(portal)
        return portal
    }

    fun isPit(portal: Portal): Boolean = portal.portalTag == EXIT_TAG || portal.portalTag == TELEPORT_TAG

    fun holeCenter(portal: Portal): BlockPos = BlockPos.containing(portal.originPos).below()

    fun at(level: ServerLevel, holeCenter: BlockPos): Portal? {
        val found = level.getEntitiesOfClass(Portal::class.java, AABB.ofSize(origin(holeCenter), 0.5, 0.5, 0.5), ::isPit)
        val portal = found.firstOrNull() ?: return null
        found.drop(1).forEach { it.discard() }
        return portal
    }

    private fun faceUp(portal: Portal) {
        if (portal.normal.y >= 0) return
        portal.setOrientation(portal.axisH, portal.axisW)
    }

    private fun origin(holeCenter: BlockPos): Vec3 = Vec3(holeCenter.x + 0.5, holeCenter.y + 1.0, holeCenter.z + 0.5)

    private fun box(center: BlockPos): AABB {
        val half = MazeLayout.PIT_HOLE / 2.0
        return AABB(
            center.x + 0.5 - half, center.y.toDouble(), center.z + 0.5 - half,
            center.x + 0.5 + half, center.y + 1.0, center.z + 0.5 + half
        )
    }
}
