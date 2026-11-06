package xyz.thewhish.substratum.neoforge

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.neoforge.client.model.data.ModelData
import net.neoforged.neoforge.client.model.data.ModelProperty
import xyz.thewhish.substratum.rift.RiftCutBlockEntity

class RiftCutNeoForgeBlockEntity(pos: BlockPos, state: BlockState) : RiftCutBlockEntity(pos, state) {

    override fun getModelData(): ModelData = ModelData.of(HOST, host)

    override fun setHost(state: BlockState) {
        super.setHost(state)
        requestModelDataUpdate()
    }

    companion object {
        val HOST: ModelProperty<BlockState> = ModelProperty()
    }
}
