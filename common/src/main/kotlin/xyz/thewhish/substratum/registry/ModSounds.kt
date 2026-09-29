package xyz.thewhish.substratum.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.sounds.SoundEvent
import xyz.thewhish.substratum.Substratum

object ModSounds {
    private val SOUNDS = DeferredRegister.create(Substratum.ID, Registries.SOUND_EVENT)

    val HUM_60HZ: RegistrySupplier<SoundEvent> = register("hum_60hz")
    val LAMP_BUZZ: RegistrySupplier<SoundEvent> = register("lamp_buzz")
    val LAMP_CRACKLE: RegistrySupplier<SoundEvent> = register("lamp_crackle")
    val LAMP_FLICKER: RegistrySupplier<SoundEvent> = register("lamp_flicker")
    val LAMP_POP: RegistrySupplier<SoundEvent> = register("lamp_pop")

    fun register() {
        SOUNDS.register()
    }

    private fun register(name: String): RegistrySupplier<SoundEvent> =
        SOUNDS.register(name) { SoundEvent.createVariableRangeEvent(Substratum.id(name)) }
}
