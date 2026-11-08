package xyz.thewhish.substratum.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.level.Remains
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

    val REMAINS: RegistrySupplier<EntityType<Remains>> = ENTITIES.register("remains") {
        EntityType.Builder.of<Remains>(::Remains, MobCategory.MISC)
            .sized(Remains.WIDTH, Remains.HEIGHT)
            .noSave()
            .clientTrackingRange(Remains.TRACKING_CHUNKS)
            .build("remains")
    }

    fun register() {
        ENTITIES.register()
    }
}
