package xyz.thewhish.substratum.rift

import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import xyz.thewhish.substratum.Substratum
import kotlin.math.abs

object Squeeze {

    private val SLOW = Substratum.id("squeeze")

    private val MODIFIER = AttributeModifier(SLOW, -0.55, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)

    fun register() {
        TickEvent.SERVER_POST.register { server -> server.playerList.players.forEach(::tick) }
    }

    fun axisOf(entity: Entity): Direction.Axis? {
        val level = entity.level()
        val box = entity.boundingBox
        val positions = BlockPos.betweenClosed(
            BlockPos.containing(box.minX, box.minY, box.minZ),
            BlockPos.containing(box.maxX, box.maxY, box.maxZ)
        )
        for (pos in positions) {
            val state = level.getBlockState(pos)
            if (state.block !is RiftCutBlock) continue
            val axis = RiftCutBlock.axisOf(state)
            if (RiftCutBlock.holds(entity, pos, axis)) return axis
        }
        return null
    }

    fun brace(entity: LivingEntity) {
        val axis = axisOf(entity) ?: return
        pin(entity, axis)
    }

    private fun pin(entity: LivingEntity, axis: Direction.Axis) {
        entity.yBodyRot = across(entity.yBodyRot, axis)
    }

    internal fun across(current: Float, axis: Direction.Axis): Float {
        val base = if (axis == Direction.Axis.X) 0f else 90f
        return if (abs(Mth.wrapDegrees(current - base)) <= 90f) base else Mth.wrapDegrees(base + 180f)
    }

    private fun tick(player: ServerPlayer) {
        val axis = axisOf(player)
        if (axis != null) pin(player, axis)
        val speed = player.getAttribute(Attributes.MOVEMENT_SPEED) ?: return
        if ((axis != null) == (speed.getModifier(SLOW) != null)) return
        if (axis != null) speed.addTransientModifier(MODIFIER) else speed.removeModifier(SLOW)
    }
}
