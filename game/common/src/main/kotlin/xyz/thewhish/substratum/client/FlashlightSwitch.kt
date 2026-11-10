package xyz.thewhish.substratum.client

import dev.architectury.registry.item.ItemPropertiesRegistry
import net.minecraft.Util
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.item.FlashlightItem
import xyz.thewhish.substratum.registry.ModItems

object FlashlightSwitch {
    private const val CLICK_NANOS = 200_000_000L

    private class Motion(val on: Boolean, val since: Long)

    private val motions = HashMap<Int, Motion>()

    fun register() {
        ModItems.FLASHLIGHT.listen { item ->
            ItemPropertiesRegistry.register(item, Substratum.id("switch")) { stack, _, entity, _ -> travel(stack, entity) }
        }
    }

    fun reset() = motions.clear()

    private fun travel(stack: ItemStack, entity: LivingEntity?): Float {
        val on = FlashlightItem.isOn(stack)
        val rest = if (on) 1f else 0f
        val hand = when {
            entity == null -> return rest
            entity.mainHandItem === stack -> 0
            entity.offhandItem === stack -> 1
            else -> return rest
        }
        val key = entity.id * 2 + hand
        val motion = motions[key]
        if (motion == null || motion.on != on) motions[key] = Motion(on, if (motion == null) 0L else Util.getNanos())
        val t = ((Util.getNanos() - motions.getValue(key).since).toFloat() / CLICK_NANOS).coerceIn(0f, 1f)
        val eased = t * t * (3 - 2 * t)
        return if (on) eased else 1 - eased
    }
}
