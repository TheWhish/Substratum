package xyz.thewhish.substratum.client

import dev.architectury.event.events.client.ClientPlayerEvent
import dev.architectury.event.events.client.ClientTickEvent
import dev.architectury.registry.ReloadListenerRegistry
import dev.architectury.registry.client.rendering.ColorHandlerRegistry
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.minecraft.world.level.BlockAndTintGetter
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.network.ViewportSync
import xyz.thewhish.substratum.registry.ModBlocks
import xyz.thewhish.substratum.rift.RiftCutBlockEntity
import xyz.thewhish.substratum.rift.Squeeze

object SubstratumClient {

    fun init() {
        ColorHandlerRegistry.registerBlockColors({ _, level, pos, tintIndex -> hostTint(level, pos, tintIndex) }, ModBlocks.RIFT_CUT)
        ClientTickEvent.CLIENT_LEVEL_POST.register { level ->
            level.players().forEach(Squeeze::brace)
            reportViewportIfChanged()
        }
        ClientTickEvent.CLIENT_POST.register(LampHum::tick)
        LightField.init()
        ClientTickEvent.CLIENT_POST.register(LightField::tick)
        ClientTickEvent.CLIENT_POST.register(LampFlicker::tick)
        ClientTickEvent.CLIENT_POST.register(Blackout::tick)
        ClientTickEvent.CLIENT_POST.register(BackroomsLight::tick)
        ClientTickEvent.CLIENT_POST.register(SanityEffects::tick)
        ClientTickEvent.CLIENT_POST.register(Atmosphere::tick)
        ClientConfig.load()
        ReloadListenerRegistry.register(PackType.CLIENT_RESOURCES, ResourceManagerReloadListener {
            ClientConfig.load()
            Atmosphere.reset()
        }, Substratum.id("atmosphere"))
        ClientPlayerEvent.CLIENT_PLAYER_JOIN.register {
            forgetReportedViewport()
            ShaftDarkness.reset()
        }
        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register {
            ShaftDarkness.reset()
            SanityClient.reset()
            Blackout.reset()
        }
    }

    private var lastFov = -1
    private var lastAspect = -1f
    private var lastPerspective: ViewportSync.Perspective? = null

    private fun reportViewportIfChanged() {
        val minecraft = Minecraft.getInstance()
        val window = minecraft.window
        if (window.height <= 0) return
        val fov = minecraft.options.fov().get()
        val aspect = window.width.toFloat() / window.height.toFloat()
        val perspective = perspectiveOf(minecraft.options.cameraType)
        if (fov == lastFov && aspect == lastAspect && perspective == lastPerspective) return
        lastFov = fov
        lastAspect = aspect
        lastPerspective = perspective
        ViewportSync.report(fov.toFloat(), aspect, perspective)
    }

    private fun forgetReportedViewport() {
        lastFov = -1
        lastAspect = -1f
        lastPerspective = null
    }

    private fun perspectiveOf(type: CameraType): ViewportSync.Perspective = when (type) {
        CameraType.FIRST_PERSON -> ViewportSync.Perspective.FIRST_PERSON
        CameraType.THIRD_PERSON_FRONT -> ViewportSync.Perspective.THIRD_PERSON_FRONT
        else -> ViewportSync.Perspective.THIRD_PERSON_BACK
    }

    private fun hostTint(level: BlockAndTintGetter?, pos: BlockPos?, tintIndex: Int): Int {
        if (level == null || pos == null) return NO_TINT
        val host = (level.getBlockEntity(pos) as? RiftCutBlockEntity)?.host ?: return NO_TINT
        return Minecraft.getInstance().blockColors.getColor(host, level, pos, tintIndex)
    }

    private const val NO_TINT = -1
}
