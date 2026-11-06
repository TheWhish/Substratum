package xyz.thewhish.substratum.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.status.ChunkStatus

internal fun scanBlocks(level: ClientLevel, center: BlockPos, range: Int, test: (BlockState) -> Boolean): List<BlockPos> {
    LightField.sourcesNear(level, center, range, test)?.let { return it }
    val found = ArrayList<BlockPos>()
    val cursor = BlockPos.MutableBlockPos()
    for (chunkX in SectionPos.blockToSectionCoord(center.x - range)..SectionPos.blockToSectionCoord(center.x + range)) {
        for (chunkZ in SectionPos.blockToSectionCoord(center.z - range)..SectionPos.blockToSectionCoord(center.z + range)) {
            val chunk = level.chunkSource.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) ?: continue
            for (sectionY in SectionPos.blockToSectionCoord(center.y - range)..SectionPos.blockToSectionCoord(center.y + range)) {
                val index = chunk.getSectionIndexFromSectionY(sectionY)
                if (index !in chunk.sections.indices) continue
                val section = chunk.sections[index]
                if (section.hasOnlyAir() || !section.maybeHas(test)) continue
                for (x in 0 until 16) for (y in 0 until 16) for (z in 0 until 16) {
                    if (!test(section.getBlockState(x, y, z))) continue
                    cursor.set(SectionPos.sectionToBlockCoord(chunkX, x), SectionPos.sectionToBlockCoord(sectionY, y), SectionPos.sectionToBlockCoord(chunkZ, z))
                    if (cursor.distSqr(center) <= range.toDouble() * range) found.add(cursor.immutable())
                }
            }
        }
    }
    return found
}
