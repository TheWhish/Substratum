package xyz.thewhish.substratum.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.world.item.Item
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.item.AlmondWaterItem
import xyz.thewhish.substratum.item.FlashlightItem

object ModItems {
    private val ITEMS: DeferredRegister<Item> = DeferredRegister.create(Substratum.ID, Registries.ITEM)

    val ALMOND_WATER: RegistrySupplier<Item> =
        ITEMS.register("almond_water") { AlmondWaterItem(Item.Properties().stacksTo(1)) }

    val FLASHLIGHT: RegistrySupplier<Item> = ITEMS.register("flashlight") { FlashlightItem(Item.Properties().stacksTo(1)) }

    fun register() {
        ITEMS.register()
    }
}
