package xyz.thewhish.substratum.item

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.UseAnim
import net.minecraft.world.level.Level
import xyz.thewhish.substratum.sanity.SanityTracker

class AlmondWaterItem(properties: Properties) : Item(properties) {

    override fun getUseAnimation(stack: ItemStack): UseAnim = UseAnim.DRINK

    override fun finishUsingItem(stack: ItemStack, level: Level, entity: LivingEntity): ItemStack {
        if (!level.isClientSide) {
            entity.activeEffects
                .mapNotNull { instance -> instance.effect.takeIf { it.value().category == MobEffectCategory.HARMFUL } }
                .forEach(entity::removeEffect)

            if (entity is ServerPlayer) SanityTracker.rewind(entity, REWIND_TICKS)
        }
        return super.finishUsingItem(stack, level, entity)
    }

    private companion object {
        const val REWIND_TICKS = 150 * 20
    }
}
