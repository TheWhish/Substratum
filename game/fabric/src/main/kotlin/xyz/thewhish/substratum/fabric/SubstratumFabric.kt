package xyz.thewhish.substratum.fabric

import net.fabricmc.api.ModInitializer
import xyz.thewhish.substratum.Substratum

object SubstratumFabric : ModInitializer {
    override fun onInitialize() {
        Substratum.init()
    }
}
