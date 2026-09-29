package xyz.thewhish.substratum.client

import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.material.FogType
import xyz.thewhish.substratum.level.SubstratumLevels

object DepthFog {

    private const val START = 0.05f
    private const val END = SubstratumLevels.SIGHT_CHUNKS * 16
    private const val UNLIT = 0.6f

    var lastStart = 0f
        private set
    var lastEnd = END.toFloat()
        private set

    @JvmStatic
    fun remember(start: Float, end: Float) {
        lastStart = start
        lastEnd = end
    }

    @JvmStatic
    fun applies(level: ClientLevel?, camera: Camera): Boolean {
        if (level == null || level.dimension() != SubstratumLevels.LEVEL_0 || camera.fluidInCamera != FogType.NONE) return false
        val viewer = camera.entity as? LivingEntity ?: return true
        return !viewer.hasEffect(MobEffects.BLINDNESS) && !viewer.hasEffect(MobEffects.DARKNESS)
    }

    @JvmStatic
    fun start(farPlane: Float, partialTick: Float): Float = end(farPlane, partialTick) * START

    @JvmStatic
    fun end(farPlane: Float, partialTick: Float): Float = minOf(END * (1f - SanityEffects.closeIn(partialTick)), farPlane)

    @JvmStatic
    fun colour(index: Int): Float {
        val minecraft = Minecraft.getInstance()
        return UNLIT * BackroomsLight.tinted(BackroomsLight.brightness(minecraft, Irradiance.DARK.toFloat()), index)
    }
}
