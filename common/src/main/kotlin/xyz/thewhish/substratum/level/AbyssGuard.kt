package xyz.thewhish.substratum.level

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import xyz.thewhish.substratum.worldgen.MazeChunkGenerator

object AbyssGuard {

    private const val EFFECT_TICKS = 60
    private const val FOOTING_RADIUS = 32
    private const val CONTACT_REACH = 2.0

    fun reject(entity: Entity, floor: BlockPos) {
        if (entity !is ServerPlayer || entity.y - floor.y > CONTACT_REACH) return
        val level = entity.serverLevel()
        val generator = level.chunkSource.generator as? MazeChunkGenerator ?: return

        entity.addEffect(MobEffectInstance(MobEffects.BLINDNESS, EFFECT_TICKS))
        entity.addEffect(MobEffectInstance(MobEffects.CONFUSION, EFFECT_TICKS))
        entity.playNotifySound(SoundEvents.ELDER_GUARDIAN_CURSE, entity.soundSource, 1.0f, 0.7f)

        val footing = generator.findFooting(entity.blockX, entity.blockZ, FOOTING_RADIUS)
        entity.teleportTo(
            level,
            footing[0] + 0.5,
            (MazeChunkGenerator.FLOOR_Y + 1).toDouble(),
            footing[1] + 0.5,
            entity.yRot,
            entity.xRot
        )
        entity.resetFallDistance()
    }
}
