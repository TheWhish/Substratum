package xyz.thewhish.substratum.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.world.food.FoodProperties
import net.minecraft.world.item.Item
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.item.AlmondWaterItem

object ModItems {
    private val ITEMS: DeferredRegister<Item> = DeferredRegister.create(Substratum.ID, Registries.ITEM)

    private val ALMOND_WATER_FOOD = FoodProperties.Builder()
        .nutrition(6)
        .saturationModifier(0.4f)
        .alwaysEdible()
        .build()

    val ALMOND_WATER: RegistrySupplier<Item> =
        ITEMS.register("almond_water") { AlmondWaterItem(Item.Properties().stacksTo(4).food(ALMOND_WATER_FOOD)) }

    fun register() {
        ITEMS.register()
    }
}
