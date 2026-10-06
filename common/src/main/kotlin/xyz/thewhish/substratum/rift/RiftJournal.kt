package xyz.thewhish.substratum.rift

import dev.architectury.event.events.common.LifecycleEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.Level
import net.minecraft.world.level.saveddata.SavedData
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.level.SubstratumLevels

class RiftJournal private constructor(private val anchors: MutableSet<Pair<ResourceKey<Level>, BlockPos>>) : SavedData() {

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        val list = ListTag()
        for ((dimension, pos) in anchors) {
            list.add(CompoundTag().apply {
                putString(DIMENSION, dimension.location().toString())
                putLong(POS, pos.asLong())
            })
        }
        tag.put(ANCHORS, list)
        return tag
    }

    companion object {
        private const val NAME = "${Substratum.ID}_rifts"
        private const val ANCHORS = "anchors"
        private const val DIMENSION = "dimension"
        private const val POS = "pos"

        @Suppress("TYPE_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
        private val FACTORY = Factory({ RiftJournal(HashSet()) }, { tag, _ -> load(tag) }, null)

        fun register() {
            LifecycleEvent.SERVER_STARTED.register(::recover)
        }

        fun add(server: MinecraftServer, dimension: ResourceKey<Level>, pos: BlockPos) {
            if (dimension == SubstratumLevels.LEVEL_0) return
            val journal = of(server)
            if (!journal.anchors.add(dimension to pos.immutable())) return
            journal.setDirty()
            server.overworld().dataStorage.save()
        }

        fun remove(server: MinecraftServer, dimension: ResourceKey<Level>, pos: BlockPos) {
            val journal = of(server)
            if (journal.anchors.remove(dimension to pos)) journal.setDirty()
        }

        private fun recover(server: MinecraftServer) {
            val journal = of(server)
            if (journal.anchors.isEmpty()) return
            for ((dimension, pos) in journal.anchors) {
                val level = server.getLevel(dimension) ?: continue
                level.getChunk(pos)
                val restored = Rifts.close(level, pos)
                Substratum.LOGGER.info("Restored {} rift block(s) left open at {} in {} by an unclean shutdown", restored, pos, dimension.location())
            }
            journal.anchors.clear()
            journal.setDirty()
        }

        private fun of(server: MinecraftServer): RiftJournal = server.overworld().dataStorage.computeIfAbsent(FACTORY, NAME)

        private fun load(tag: CompoundTag): RiftJournal {
            val anchors = HashSet<Pair<ResourceKey<Level>, BlockPos>>()
            for (entry in tag.getList(ANCHORS, Tag.TAG_COMPOUND.toInt())) {
                entry as CompoundTag
                val location = ResourceLocation.tryParse(entry.getString(DIMENSION)) ?: continue
                anchors.add(ResourceKey.create(Registries.DIMENSION, location) to BlockPos.of(entry.getLong(POS)))
            }
            return RiftJournal(anchors)
        }
    }
}
