package xyz.thewhish.substratum.rift

import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.Tag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import xyz.thewhish.substratum.registry.ModBlockEntities

open class RiftCutBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(ModBlockEntities.RIFT_CUT.get(), pos, state) {

    var host: BlockState = Blocks.STONE.defaultBlockState()
        private set

    open fun setHost(state: BlockState) {
        host = state
        setChanged()
        val level = level ?: return
        if (level.isClientSide) level.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_ALL)
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        if (tag.contains(HOST, Tag.TAG_COMPOUND.toInt())) {
            setHost(NbtUtils.readBlockState(registries.lookupOrThrow(Registries.BLOCK), tag.getCompound(HOST)))
        }
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        tag.put(HOST, NbtUtils.writeBlockState(host))
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag =
        saveWithoutMetadata(registries)

    override fun getUpdatePacket(): Packet<ClientGamePacketListener>? =
        ClientboundBlockEntityDataPacket.create(this)

    private companion object {
        const val HOST = "host"
    }
}
