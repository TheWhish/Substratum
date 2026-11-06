package xyz.thewhish.substratum

import dev.architectury.event.events.common.CommandRegistrationEvent
import net.minecraft.resources.ResourceLocation
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import xyz.thewhish.substratum.command.SubstratumCommand
import xyz.thewhish.substratum.config.ServerConfig
import xyz.thewhish.substratum.gaze.Watch
import xyz.thewhish.substratum.level.BackroomsDeath
import xyz.thewhish.substratum.level.BackroomsGuard
import xyz.thewhish.substratum.level.Epoch
import xyz.thewhish.substratum.level.Homecoming
import xyz.thewhish.substratum.level.LampBursts
import xyz.thewhish.substratum.level.LevelRules
import xyz.thewhish.substratum.level.PitExitGuard
import xyz.thewhish.substratum.network.ProbeSync
import xyz.thewhish.substratum.network.SanitySync
import xyz.thewhish.substratum.network.SmilerSync
import xyz.thewhish.substratum.network.VhsSync
import xyz.thewhish.substratum.network.ViewportSync
import xyz.thewhish.substratum.reality.Director
import xyz.thewhish.substratum.registry.ModBlockEntities
import xyz.thewhish.substratum.registry.ModBlocks
import xyz.thewhish.substratum.registry.ModChunkGenerators
import xyz.thewhish.substratum.registry.ModEntities
import xyz.thewhish.substratum.registry.ModItems
import xyz.thewhish.substratum.registry.ModSounds
import xyz.thewhish.substratum.rift.ExitSpawner
import xyz.thewhish.substratum.rift.RiftJournal
import xyz.thewhish.substratum.rift.RiftLifecycle
import xyz.thewhish.substratum.rift.RiftPortals
import xyz.thewhish.substratum.rift.RiftSpawnData
import xyz.thewhish.substratum.rift.RiftSpawner
import xyz.thewhish.substratum.rift.Squeeze
import xyz.thewhish.substratum.sanity.SanityTracker
import xyz.thewhish.substratum.smiler.SmilerDirector

object Substratum {
    const val ID = "substratum"

    val LOGGER: Logger = LoggerFactory.getLogger("Substratum")

    fun id(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(ID, path)

    fun init() {
        ServerConfig.register()
        ModSounds.register()
        ModBlocks.register()
        ModBlockEntities.register()
        ModEntities.register()
        ModItems.register()
        ModChunkGenerators.register()
        Epoch.register()
        Homecoming.register()
        SanityTracker.register()
        LampBursts.register()
        LevelRules.register()
        PitExitGuard.register()
        BackroomsGuard.register()
        BackroomsDeath.register()
        Squeeze.register()
        RiftLifecycle.register()
        RiftPortals.register()
        RiftJournal.register()
        RiftSpawnData.register()
        RiftSpawner.register()
        ExitSpawner.register()
        ViewportSync.register()
        Watch.register()
        SanitySync.register()
        VhsSync.register()
        SmilerSync.register()
        ProbeSync.register()
        SmilerDirector.register()
        Director.register()
        CommandRegistrationEvent.EVENT.register { dispatcher, _, _ -> SubstratumCommand.register(dispatcher) }
        LOGGER.info("Substratum initialised")
    }
}
