package xyz.thewhish.substratum.client

import dev.architectury.event.events.client.ClientGuiEvent
import dev.architectury.event.events.client.ClientPlayerEvent
import dev.architectury.event.events.client.ClientTickEvent
import dev.architectury.registry.ReloadListenerRegistry
import dev.architectury.registry.client.level.entity.EntityRendererRegistry
import dev.architectury.registry.client.rendering.ColorHandlerRegistry
import dev.architectury.registry.client.rendering.RenderTypeRegistry
import net.minecraft.Util
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.BlockPos
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.minecraft.world.level.BlockAndTintGetter
import net.minecraft.world.level.Level
import qouteall.imm_ptl.core.ClientWorldLoader
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.level.SubstratumLevels
import xyz.thewhish.substratum.network.ViewportSync
import xyz.thewhish.substratum.registry.ModBlocks
import xyz.thewhish.substratum.registry.ModEntities
import xyz.thewhish.substratum.rift.RiftCutBlockEntity
import xyz.thewhish.substratum.rift.Squeeze
import xyz.thewhish.substratum.smiler.Smiler
import xyz.thewhish.substratum.smiler.client.SmilerRenderer

object SubstratumClient {

    fun init() {
        ColorHandlerRegistry.registerBlockColors({ _, level, pos, tintIndex -> hostTint(level, pos, tintIndex) }, ModBlocks.RIFT_CUT)
        RenderTypeRegistry.register(RenderType.cutout(), ModBlocks.ALMOND_WATER.get())
        EntityRendererRegistry.register(ModEntities.SMILER, ::SmilerRenderer)
        ClientTickEvent.CLIENT_LEVEL_POST.register { level ->
            level.players().forEach(Squeeze::brace)
            reportViewportIfChanged()
            turnSmilers(level)
        }
        ClientTickEvent.CLIENT_POST.register(::preparePortalWorlds)
        ClientTickEvent.CLIENT_POST.register(LampHum::tick)
        LightField.init()
        ClientTickEvent.CLIENT_POST.register(LightField::tick)
        ClientTickEvent.CLIENT_POST.register(LampFlicker::tick)
        ClientTickEvent.CLIENT_POST.register(BackroomsLight::tick)
        ClientTickEvent.CLIENT_POST.register(SanityEffects::tick)
        ClientTickEvent.CLIENT_POST.register(Atmosphere::tick)
        ClientTickEvent.CLIENT_POST.register(Vhs::tick)
        ClientGuiEvent.RENDER_HUD.register(Vhs::renderHud)
        ClientTickEvent.CLIENT_POST.register(ProbeView::tick)
        ClientGuiEvent.RENDER_HUD.register { graphics, _ -> ProbeView.renderHud(graphics) }
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
            Vhs.reset()
            ProbeView.reset()
        }
    }

    private val PORTAL_WORLDS = listOf(SubstratumLevels.LEVEL_0, Level.OVERWORLD)

    private fun preparePortalWorlds(minecraft: Minecraft) {
        val player = minecraft.player ?: return
        if (player.level() !== minecraft.level) return
        val known = ClientWorldLoader.dimIdToDimTypeId ?: return
        val served = player.connection.levels()
        for (dimension in PORTAL_WORLDS) {
            if (dimension in known && dimension in served) ClientWorldLoader.getWorld(dimension)
        }
    }

    private fun turnSmilers(level: ClientLevel) {
        val viewer = Minecraft.getInstance().cameraEntity?.eyePosition ?: return
        level.entitiesForRendering().forEach { if (it is Smiler) it.turnTowards(viewer) }
    }

    private const val FOV_STEP = 1.0
    private const val HAZE_STEP = 1f
    private const val REPORT_GAP_MS = 250L

    private var frameFov = -1.0
    private var lastFov = -1.0
    private var lastAspect = -1f
    private var lastHaze = -1f
    private var lastPerspective: ViewportSync.Perspective? = null
    private var lastReport = 0L

    @JvmStatic
    fun rememberFrameFov(fov: Double) {
        frameFov = fov
    }

    private fun reportViewportIfChanged() {
        val minecraft = Minecraft.getInstance()
        val window = minecraft.window
        if (window.height <= 0) return
        val fov = if (frameFov > 0.0) frameFov else minecraft.options.fov().get().toDouble()
        val aspect = window.width.toFloat() / window.height.toFloat()
        val perspective = perspectiveOf(minecraft.options.cameraType)
        val haze = if (minecraft.level?.dimension() == SubstratumLevels.LEVEL_0) DepthFog.haze(0f) else ViewportSync.MAX_HAZE
        val widened = perspective != lastPerspective || fov - lastFov > FOV_STEP || haze - lastHaze > HAZE_STEP || aspect > lastAspect
        val narrowed = lastFov - fov > FOV_STEP || lastHaze - haze > HAZE_STEP || aspect < lastAspect
        val now = Util.getMillis()
        if (!widened && (!narrowed || now - lastReport < REPORT_GAP_MS)) return
        lastFov = fov
        lastAspect = aspect
        lastHaze = haze
        lastPerspective = perspective
        lastReport = now
        ViewportSync.report(fov.toFloat(), aspect, perspective, haze)
    }

    private fun forgetReportedViewport() {
        frameFov = -1.0
        lastFov = -1.0
        lastAspect = -1f
        lastHaze = -1f
        lastPerspective = null
        lastReport = 0L
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
