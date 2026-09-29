package xyz.thewhish.substratum.fabric

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.rift.RiftLifecycle
import xyz.thewhish.substratum.rift.RiftPortals

object SubstratumFabric : ModInitializer {
    override fun onInitialize() {
        Substratum.init()
        ServerChunkEvents.CHUNK_UNLOAD.register { level, chunk -> RiftLifecycle.closeIfTracked(level, chunk.pos) }
        ServerEntityEvents.ENTITY_LOAD.register { entity, _ -> RiftPortals.onLoad(entity) }
    }
}
