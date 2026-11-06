package xyz.thewhish.substratum.smiler.client

import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.shapes.CollisionContext
import qouteall.imm_ptl.core.render.context_management.PortalRendering
import xyz.thewhish.substratum.level.SubstratumLevels
import xyz.thewhish.substratum.network.SmilerSync
import xyz.thewhish.substratum.smiler.Smiler
import xyz.thewhish.substratum.smiler.Spots

object Vanish {

    var frame = 0L
        private set

    @JvmStatic
    fun afterFrame(camera: Camera) {
        if (PortalRendering.isRendering()) return
        val level = Minecraft.getInstance().level?.takeIf { it.dimension() == SubstratumLevels.LEVEL_0 } ?: return
        for (entity in level.entitiesForRendering()) {
            if (entity !is Smiler || entity.vanished || !entity.leaving() || visible(level, camera, entity)) continue
            entity.vanished = true
            SmilerSync.gone(entity.id)
        }
        frame++
    }

    private fun visible(level: ClientLevel, camera: Camera, smiler: Smiler): Boolean {
        if (smiler.drawnFrame != frame) return false
        if (smiler.portalFrame == frame) return true
        val eye = camera.position
        return Spots.face(smiler.position(), eye).any {
            level.clip(ClipContext(eye, it, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty())).type == HitResult.Type.MISS
        }
    }
}
