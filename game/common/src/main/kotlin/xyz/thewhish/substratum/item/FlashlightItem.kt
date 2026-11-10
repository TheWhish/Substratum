package xyz.thewhish.substratum.item

import net.minecraft.core.component.DataComponents
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomModelData
import net.minecraft.world.level.Level

class FlashlightItem(properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        val on = !isOn(stack)
        if (!level.isClientSide) {
            if (on) stack.set(DataComponents.CUSTOM_MODEL_DATA, LIT) else stack.remove(DataComponents.CUSTOM_MODEL_DATA)
            val click = if (on) SoundEvents.STONE_BUTTON_CLICK_ON else SoundEvents.STONE_BUTTON_CLICK_OFF
            level.playSound(null, player.x, player.y, player.z, click, SoundSource.PLAYERS, 0.35f, if (on) 1.9f else 1.6f)
        }
        return InteractionResultHolder.consume(stack)
    }

    companion object {
        private val LIT = CustomModelData(1)

        fun isOn(stack: ItemStack): Boolean = stack.get(DataComponents.CUSTOM_MODEL_DATA) == LIT

        fun power(stack: ItemStack): Float = if (stack.item is FlashlightItem && isOn(stack)) 1f else 0f

        fun onlySwitched(old: ItemStack, new: ItemStack): Boolean =
            old.item is FlashlightItem && old.item === new.item &&
                ItemStack.matches(old.copy().also { it.set(DataComponents.CUSTOM_MODEL_DATA, new.get(DataComponents.CUSTOM_MODEL_DATA)) }, new)
    }
}
