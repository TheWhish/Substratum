package xyz.thewhish.substratum.item

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemUtils
import net.minecraft.world.item.UseAnim
import net.minecraft.world.level.Level
import xyz.thewhish.substratum.sanity.SanityTracker

class AlmondWaterItem(properties: Properties) : Item(properties) {

    override fun getUseAnimation(stack: ItemStack): UseAnim = UseAnim.NONE

    override fun getUseDuration(stack: ItemStack, entity: LivingEntity): Int = DRINK_TICKS

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> =
        ItemUtils.startUsingInstantly(level, player, hand)

    override fun finishUsingItem(stack: ItemStack, level: Level, entity: LivingEntity): ItemStack {
        if (!level.isClientSide) {
            entity.activeEffects
                .mapNotNull { instance -> instance.effect.takeIf { it.value().category == MobEffectCategory.HARMFUL } }
                .forEach(entity::removeEffect)
            if (entity is ServerPlayer) SanityTracker.rewind(entity, REWIND_TICKS)
        }
        stack.shrink(1)
        return stack
    }

    companion object {
        const val DRINK_TICKS = 44
        private const val REWIND_TICKS = 5_000
    }
}
