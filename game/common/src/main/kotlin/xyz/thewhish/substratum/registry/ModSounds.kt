package xyz.thewhish.substratum.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.Item
import net.minecraft.world.level.Level
import xyz.thewhish.substratum.Substratum

object ModSounds {
    private const val PICKUP_PITCH = 0.94f
    private const val PICKUP_PITCH_SPREAD = 0.12f
    private val SOUNDS = DeferredRegister.create(Substratum.ID, Registries.SOUND_EVENT)

    val HUM_60HZ: RegistrySupplier<SoundEvent> = register("hum_60hz")
    val LAMP_BUZZ: RegistrySupplier<SoundEvent> = register("lamp_buzz")
    val LAMP_CRACKLE: RegistrySupplier<SoundEvent> = register("lamp_crackle")
    val LAMP_FLICKER: RegistrySupplier<SoundEvent> = register("lamp_flicker")
    val LAMP_POP: RegistrySupplier<SoundEvent> = register("lamp_pop")
    val VHS_BURST: RegistrySupplier<SoundEvent> = register("vhs_burst")
    val MENU_MUSIC: RegistrySupplier<SoundEvent> = register("music.menu")
    val MENU_HOVER: RegistrySupplier<SoundEvent> = register("menu.hover")
    val MENU_PRESS: RegistrySupplier<SoundEvent> = register("menu.press")
    private val PICKUP_BOTTLE: RegistrySupplier<SoundEvent> = register("item.pickup.bottle")
    private val PICKUP_FLASHLIGHT: RegistrySupplier<SoundEvent> = register("item.pickup.flashlight")

    fun register() {
        SOUNDS.register()
    }

    fun playPickup(level: Level, pos: BlockPos, item: Item) {
        val sound = if (item === ModItems.FLASHLIGHT.get()) PICKUP_FLASHLIGHT.get() else PICKUP_BOTTLE.get()
        level.playSound(null, pos, sound, SoundSource.PLAYERS, 1f, PICKUP_PITCH + level.random.nextFloat() * PICKUP_PITCH_SPREAD)
    }

    private fun register(name: String): RegistrySupplier<SoundEvent> =
        SOUNDS.register(name) { SoundEvent.createVariableRangeEvent(Substratum.id(name)) }
}
