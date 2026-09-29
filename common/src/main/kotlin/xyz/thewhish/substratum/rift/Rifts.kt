package xyz.thewhish.substratum.rift

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import xyz.thewhish.substratum.registry.ModBlocks

object Rifts {

    private const val MAX_HEIGHT = 8

    fun canHost(level: LevelReader, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        if (state.isAir || state.hasBlockEntity()) return false
        if (!state.fluidState.isEmpty) return false
        if (state.getDestroySpeed(level, pos) < 0f) return false
        return state.isCollisionShapeFullBlock(level, pos)
    }

    fun cut(level: ServerLevel, lower: BlockPos, mouth: Direction, through: Boolean): Boolean {
        val upper = lower.above()
        if (!canHost(level, lower) || !canHost(level, upper)) return false

        val cut = ModBlocks.RIFT_CUT.get().defaultBlockState()
            .setValue(RiftCutBlock.MOUTH, mouth)
            .setValue(RiftCutBlock.THROUGH, through)
        for (pos in listOf(lower, upper)) {
            val host = level.getBlockState(pos)
            level.setBlock(pos, cut, Block.UPDATE_ALL)
            val entity = level.getBlockEntity(pos) as? RiftCutBlockEntity ?: continue
            entity.setHost(host)
            level.sendBlockUpdated(pos, cut, cut, Block.UPDATE_ALL)
            // setBlock already lit this against the stone placeholder, redo it with the real host
            level.lightEngine.checkBlock(pos)
        }
        return true
    }

    fun bottomOf(level: ServerLevel, any: BlockPos): BlockPos {
        var bottom = any.immutable()
        var guard = MAX_HEIGHT
        while (guard-- > 0 && level.getBlockEntity(bottom.below()) is RiftCutBlockEntity) {
            bottom = bottom.below()
        }
        return bottom
    }

    fun close(level: ServerLevel, any: BlockPos): Int {
        var restored = 0
        var pos = bottomOf(level, any)
        while (restored < MAX_HEIGHT) {
            val entity = level.getBlockEntity(pos) as? RiftCutBlockEntity ?: break
            // read before setBlock, it drops the block entity together with the only copy of the host
            val host: BlockState = entity.host
            level.setBlock(pos, host, Block.UPDATE_ALL)
            restored++
            pos = pos.above()
        }
        return restored
    }
}
