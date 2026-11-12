package xyz.thewhish.substratum.client

import com.mojang.authlib.GameProfile
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import java.util.UUID

class Corpse(level: ClientLevel, owner: UUID?) : Figure(level, GameProfile(owner ?: UUID.randomUUID(), NAME)) {

    private val side = Collapse.side(gameProfile.id)

    override fun isSpectator(): Boolean = false

    override fun isCreative(): Boolean = false

    fun follow(yaw: Float, held: ItemStack) {
        yBodyRot = yaw
        yBodyRotO = yaw
        yHeadRot = yaw
        yHeadRotO = yaw
        if (!ItemStack.matches(mainHandItem, held)) setItemSlot(EquipmentSlot.MAINHAND, held.copy())
    }

    override fun slump(poseStack: PoseStack) = Collapse.lie(poseStack, side)

    override fun pose(model: PlayerModel<*>) = Collapse.rest(model, side)

    companion object {
        private const val NAME = "suit"
    }
}
