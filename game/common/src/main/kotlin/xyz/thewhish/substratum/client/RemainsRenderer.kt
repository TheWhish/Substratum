package xyz.thewhish.substratum.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.inventory.InventoryMenu
import xyz.thewhish.substratum.level.Remains
import java.util.WeakHashMap

class RemainsRenderer(context: EntityRendererProvider.Context) : EntityRenderer<Remains>(context) {

    private val corpses = WeakHashMap<Remains, Corpse>()

    override fun render(remains: Remains, yaw: Float, partialTick: Float, poseStack: PoseStack, buffers: MultiBufferSource, light: Int) {
        val level = remains.level() as? ClientLevel ?: return
        val corpse = corpses.getOrPut(remains) { Corpse(level, remains.owner) }
        corpse.follow(remains.tickCount + partialTick, remains.yRot)
        Minecraft.getInstance().entityRenderDispatcher.getRenderer(corpse).render(corpse, remains.yRot, partialTick, poseStack, buffers, light)
    }

    override fun getTextureLocation(remains: Remains): ResourceLocation = InventoryMenu.BLOCK_ATLAS
}
