package xyz.thewhish.substratum.level

import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import xyz.thewhish.substratum.Substratum

object SubstratumLevels {
    const val SIGHT_CHUNKS = 4

    val LEVEL_0: ResourceKey<Level> = ResourceKey.create(Registries.DIMENSION, Substratum.id("level_0"))
}
