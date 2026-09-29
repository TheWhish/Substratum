package xyz.thewhish.substratum.fabric.client

import net.fabricmc.fabric.api.renderer.v1.RendererAccess
import net.fabricmc.fabric.api.renderer.v1.material.BlendMode
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial
import net.fabricmc.fabric.api.renderer.v1.model.ForwardingBakedModel
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext
import net.minecraft.client.renderer.ItemBlockRenderTypes
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.RandomSource
import net.minecraft.world.level.BlockAndTintGetter
import net.minecraft.world.level.block.state.BlockState
import xyz.thewhish.substratum.rift.RiftCutBlockEntity
import xyz.thewhish.substratum.rift.client.RiftCutQuads
import java.util.function.Supplier

class RiftCutFabricModel(model: BakedModel) : ForwardingBakedModel() {

    init {
        wrapped = model
    }

    override fun isVanillaAdapter(): Boolean = false

    override fun emitBlockQuads(
        blockView: BlockAndTintGetter,
        state: BlockState,
        pos: BlockPos,
        randomSupplier: Supplier<RandomSource>,
        context: RenderContext
    ) {
        val host = (blockView.getBlockEntity(pos) as? RiftCutBlockEntity)?.host ?: return
        val material = material(host) ?: return
        val emitter = context.emitter
        for (side in SIDES) {
            for (quad in RiftCutQuads.faces(state, host, side, randomSupplier.get())) {
                emitter.fromVanilla(quad, material, side)
                emitter.emit()
            }
        }

        val light = LevelRenderer.getLightColor(blockView, state, pos)
        for (quad in RiftCutQuads.walls(state, host)) {
            emitter.fromVanilla(quad, material, null)
            for (vertex in 0 until 4) emitter.lightmap(vertex, light)
            emitter.emit()
        }
    }

    private fun material(host: BlockState): RenderMaterial? {
        val renderer = RendererAccess.INSTANCE.renderer ?: return null
        return renderer.materialFinder()
            .clear()
            .blendMode(BlendMode.fromRenderLayer(ItemBlockRenderTypes.getChunkRenderType(host)))
            .find()
    }

    private companion object {
        val SIDES: Array<Direction?> = arrayOf(*Direction.entries.toTypedArray(), null)
    }
}
