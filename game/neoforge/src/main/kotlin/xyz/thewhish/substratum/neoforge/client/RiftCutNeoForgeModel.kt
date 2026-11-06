package xyz.thewhish.substratum.neoforge.client

import net.minecraft.client.renderer.ItemBlockRenderTypes
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.block.model.BakedQuad
import net.minecraft.client.renderer.block.model.ItemOverrides
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.core.Direction
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.neoforge.client.ChunkRenderTypeSet
import net.neoforged.neoforge.client.model.IDynamicBakedModel
import net.neoforged.neoforge.client.model.data.ModelData
import xyz.thewhish.substratum.neoforge.RiftCutNeoForgeBlockEntity
import xyz.thewhish.substratum.rift.client.RiftCutQuads

class RiftCutNeoForgeModel(private val wrapped: BakedModel) : IDynamicBakedModel {

    override fun getQuads(
        state: BlockState?,
        side: Direction?,
        rand: RandomSource,
        data: ModelData,
        renderType: RenderType?
    ): List<BakedQuad> {
        val cut = state ?: return emptyList()
        val host = data.get(RiftCutNeoForgeBlockEntity.HOST) ?: return emptyList()
        return RiftCutQuads.of(cut, host, side, rand)
    }

    override fun getRenderTypes(state: BlockState, rand: RandomSource, data: ModelData): ChunkRenderTypeSet {
        val host = data.get(RiftCutNeoForgeBlockEntity.HOST) ?: return ChunkRenderTypeSet.none()
        return ItemBlockRenderTypes.getRenderLayers(host)
    }

    override fun useAmbientOcclusion(): Boolean = wrapped.useAmbientOcclusion()

    override fun isGui3d(): Boolean = wrapped.isGui3d

    override fun usesBlockLight(): Boolean = wrapped.usesBlockLight()

    override fun isCustomRenderer(): Boolean = false

    override fun getParticleIcon(): TextureAtlasSprite = wrapped.particleIcon

    override fun getOverrides(): ItemOverrides = wrapped.overrides
}
