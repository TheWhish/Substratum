package xyz.thewhish.substratum.registry

import com.mojang.serialization.MapCodec
import dev.architectury.registry.registries.DeferredRegister
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.chunk.ChunkGenerator
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.worldgen.MazeChunkGenerator

object ModChunkGenerators {
    private val CHUNK_GENERATORS: DeferredRegister<MapCodec<out ChunkGenerator>> =
        DeferredRegister.create(Substratum.ID, Registries.CHUNK_GENERATOR)

    fun register() {
        CHUNK_GENERATORS.register("maze") { MazeChunkGenerator.CODEC }
        CHUNK_GENERATORS.register()
    }
}
