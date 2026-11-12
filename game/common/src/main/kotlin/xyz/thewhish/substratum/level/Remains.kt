package xyz.thewhish.substratum.level

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
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
import net.minecraft.world.phys.Vec3
import xyz.thewhish.substratum.registry.ModEntities
import xyz.thewhish.substratum.registry.ModSounds
import java.util.Optional
import java.util.UUID

class Remains(type: EntityType<Remains>, level: Level) : Entity(type, level) {

    private val items = ArrayList<ItemStack>()
    private var heldStack = ItemStack.EMPTY

    val owner: UUID? get() = entityData.get(OWNER).orElse(null)

    val stocked: Boolean get() = entityData.get(STOCKED)

    val held: ItemStack get() = entityData.get(HELD)

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(OWNER, Optional.empty())
        builder.define(STOCKED, false)
        builder.define(HELD, ItemStack.EMPTY)
    }

    override fun readAdditionalSaveData(tag: CompoundTag) = Unit

    override fun addAdditionalSaveData(tag: CompoundTag) = Unit

    override fun isPickable(): Boolean = !isRemoved

    override fun getBoundingBoxForCulling(): AABB = boundingBox.inflate(CULL_REACH)

    override fun makeBoundingBox(): AABB = Footprint.bounds(position(), yRot)

    fun hitBy(from: Vec3, to: Vec3): Boolean = Footprint.hit(position(), yRot, from, to)

    override fun recreateFromPacket(packet: ClientboundAddEntityPacket) {
        super.recreateFromPacket(packet)
        setPos(x, y, z)
    }

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
        if (heldStack.isEmpty) entityData.set(HELD, ItemStack.EMPTY)
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
        private val HELD: EntityDataAccessor<ItemStack> =
            SynchedEntityData.defineId(Remains::class.java, EntityDataSerializers.ITEM_STACK)

        fun landing(player: ServerPlayer): Footprint.Landing {
            val level = player.level()
            return Footprint.landing(player.position(), player.yRot) { point ->
                val pos = BlockPos.containing(point)
                val shape = level.getBlockState(pos).getCollisionShape(level, pos)
                !shape.isEmpty && shape.bounds().move(pos).contains(point)
            }
        }

        fun leave(player: ServerPlayer, landing: Footprint.Landing) {
            val inventory = player.inventory
            spawn(player, landing, List(inventory.containerSize, inventory::removeItemNoUpdate), inventory.selected)
        }

        fun stage(player: ServerPlayer) {
            val inventory = player.inventory
            spawn(player, landing(player), List(inventory.containerSize) { inventory.getItem(it).copy() }, inventory.selected)
        }

        private fun spawn(player: ServerPlayer, landing: Footprint.Landing, stacks: List<ItemStack>, selected: Int) {
            val remains = Remains(ModEntities.REMAINS.get(), player.level())
            val at = player.position().add(landing.shift)
            remains.moveTo(at.x, at.y, at.z, landing.yaw, 0f)
            remains.entityData.set(OWNER, Optional.of(player.uuid))
            remains.heldStack = stacks.getOrElse(selected) { ItemStack.EMPTY }
            remains.entityData.set(HELD, remains.heldStack.copy())
            remains.items.addAll(stacks.filterNot { it.isEmpty })
            remains.entityData.set(STOCKED, remains.items.isNotEmpty())
            player.level().addFreshEntity(remains)
        }
    }
}
