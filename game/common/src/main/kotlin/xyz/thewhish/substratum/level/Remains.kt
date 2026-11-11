package xyz.thewhish.substratum.level

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import xyz.thewhish.substratum.registry.ModEntities
import xyz.thewhish.substratum.registry.ModSounds
import java.util.Optional
import java.util.UUID

class Remains(type: EntityType<Remains>, level: Level) : Entity(type, level) {

    private val items = ArrayList<ItemStack>()

    val owner: UUID? get() = entityData.get(OWNER).orElse(null)

    val stocked: Boolean get() = entityData.get(STOCKED)

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(OWNER, Optional.empty())
        builder.define(STOCKED, false)
    }

    override fun readAdditionalSaveData(tag: CompoundTag) = Unit

    override fun addAdditionalSaveData(tag: CompoundTag) = Unit

    override fun isPickable(): Boolean = !isRemoved

    override fun getBoundingBoxForCulling(): AABB = boundingBox.inflate(CULL_REACH)

    override fun interact(player: Player, hand: InteractionHand): InteractionResult {
        if (player.isSpectator || hand != InteractionHand.MAIN_HAND || !stocked) return InteractionResult.PASS
        if (player.inventory.freeSlot < 0) return InteractionResult.FAIL
        if (level().isClientSide) return InteractionResult.SUCCESS
        var taken: Item? = null
        items.removeIf { stack ->
            val item = stack.item
            player.inventory.add(stack)
            if (stack.isEmpty && taken == null) taken = item
            stack.isEmpty
        }
        entityData.set(STOCKED, items.isNotEmpty())
        taken?.let { ModSounds.playPickup(level(), blockPosition(), it) }
        return InteractionResult.CONSUME
    }

    companion object {
        const val WIDTH = 1.0f
        const val HEIGHT = 0.5f
        const val TRACKING_CHUNKS = 8
        private const val CULL_REACH = 1.5
        private val OWNER: EntityDataAccessor<Optional<UUID>> =
            SynchedEntityData.defineId(Remains::class.java, EntityDataSerializers.OPTIONAL_UUID)
        private val STOCKED: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(Remains::class.java, EntityDataSerializers.BOOLEAN)

        fun leave(player: ServerPlayer) {
            val remains = Remains(ModEntities.REMAINS.get(), player.level())
            remains.moveTo(player.x, player.y, player.z, player.yRot, 0f)
            remains.entityData.set(OWNER, Optional.of(player.uuid))
            val inventory = player.inventory
            for (slot in 0 until inventory.containerSize) {
                val stack = inventory.removeItemNoUpdate(slot)
                if (!stack.isEmpty) remains.items.add(stack)
            }
            remains.entityData.set(STOCKED, remains.items.isNotEmpty())
            player.level().addFreshEntity(remains)
        }
    }
}
