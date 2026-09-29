package xyz.thewhish.substratum.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.block.entity.BlockEntityType
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.rift.RiftCutBlockEntity

object ModBlockEntities {
    private val BLOCK_ENTITIES: DeferredRegister<BlockEntityType<*>> =
        DeferredRegister.create(Substratum.ID, Registries.BLOCK_ENTITY_TYPE)

    var factory: BlockEntityType.BlockEntitySupplier<RiftCutBlockEntity> =
        BlockEntityType.BlockEntitySupplier { pos, state -> RiftCutBlockEntity(pos, state) }

    @Suppress("TYPE_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    val RIFT_CUT: RegistrySupplier<BlockEntityType<RiftCutBlockEntity>> =
        BLOCK_ENTITIES.register("rift_cut") {
            BlockEntityType.Builder.of({ pos, state -> factory.create(pos, state) }, ModBlocks.RIFT_CUT.get()).build(null)
        }

    fun register() {
        BLOCK_ENTITIES.register()
    }
}
