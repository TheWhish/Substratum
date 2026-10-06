package xyz.thewhish.substratum.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.smiler.Smiler

object ModEntities {
    private val ENTITIES: DeferredRegister<EntityType<*>> = DeferredRegister.create(Substratum.ID, Registries.ENTITY_TYPE)

    val SMILER: RegistrySupplier<EntityType<Smiler>> = ENTITIES.register("smiler") {
        EntityType.Builder.of<Smiler>(::Smiler, MobCategory.MISC)
            .sized(Smiler.WIDTH, Smiler.HEIGHT)
            .eyeHeight(Smiler.EYES)
            .noSave()
            .clientTrackingRange(Smiler.TRACKING_CHUNKS)
            .updateInterval(1)
            .build("smiler")
    }

    fun register() {
        ENTITIES.register()
    }
}
