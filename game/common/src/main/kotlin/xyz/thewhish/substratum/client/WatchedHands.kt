package xyz.thewhish.substratum.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.ItemInHandRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import xyz.thewhish.substratum.item.FlashlightItem
import xyz.thewhish.substratum.mixin.ArmInvoker

object WatchedHands {
    private const val EQUIP_STEP = 0.4f
    private const val SWAP_BELOW = 0.1f
    private const val SWAY_FOLLOW = 0.5f
    private const val SWAY = 0.1f
    private const val STRIDE = 0.6f
    private const val BOB_LIMIT = 0.1f
    private const val BOB_FOLLOW = 0.4f

    private var owner = -1
    private var item: ItemStack = ItemStack.EMPTY
    private var height = 1f
    private var previous = 1f
    private var xBob = 0f
    private var xBobO = 0f
    private var yBob = 0f
    private var yBobO = 0f
    private var swinging = false
    private var swingTime = 0
    private var sinceSwing = Int.MAX_VALUE / 2

    private fun watched(minecraft: Minecraft): AbstractClientPlayer? {
        val camera = minecraft.cameraEntity as? AbstractClientPlayer ?: return null
        return camera.takeIf { it !== minecraft.player && !it.isSpectator && minecraft.player?.isSpectator == true }
    }

    @JvmStatic
    fun showing(): Boolean = watched(Minecraft.getInstance()) != null

    @JvmStatic
    fun owner(fallback: AbstractClientPlayer): AbstractClientPlayer = watched(Minecraft.getInstance()) ?: fallback

    fun tick(minecraft: Minecraft) {
        previous = height
        val player = watched(minecraft)
        if (player == null) {
            owner = -1
            return
        }
        walk(player)
        if (player.id != owner) return adopt(player)
        xBobO = xBob
        yBobO = yBob
        xBob += (player.xRot - xBob) * SWAY_FOLLOW
        yBob += Mth.wrapDegrees(player.yRot - yBob) * SWAY_FOLLOW
        sinceSwing = if (player.swinging && (!swinging || player.swingTime < swingTime)) 0 else sinceSwing + 1
        swinging = player.swinging
        swingTime = player.swingTime
        val held = player.mainHandItem
        if (ItemStack.matches(item, held) || FlashlightItem.onlySwitched(item, held)) item = held
        val strength = Mth.clamp((sinceSwing + 1f) / player.currentItemAttackStrengthDelay, 0f, 1f)
        height += Mth.clamp((if (item === held) strength * strength * strength else 0f) - height, -EQUIP_STEP, EQUIP_STEP)
        if (height < SWAP_BELOW) item = held
    }

    private fun adopt(player: AbstractClientPlayer) {
        owner = player.id
        item = player.mainHandItem
        height = 1f
        previous = 1f
        xBob = player.xRot
        xBobO = xBob
        yBob = player.yRot
        yBobO = yBob
        swinging = player.swinging
        swingTime = player.swingTime
        sinceSwing = Int.MAX_VALUE / 2
    }

    private fun walk(player: AbstractClientPlayer) {
        val stride = Mth.length(player.x - player.xo, player.z - player.zo).toFloat()
        player.walkDist += stride * STRIDE
        val bob = if (player.onGround()) minOf(BOB_LIMIT, stride) else 0f
        player.bob = player.oBob + (bob - player.oBob) * BOB_FOLLOW
    }

    @JvmStatic
    fun render(hands: ItemInHandRenderer, partialTick: Float, pose: PoseStack, buffers: MultiBufferSource.BufferSource): Boolean {
        val minecraft = Minecraft.getInstance()
        val player = watched(minecraft) ?: return false
        val adopted = player.id == owner
        val stack = if (adopted) item else player.mainHandItem
        val swing = if ((player.swingingArm ?: InteractionHand.MAIN_HAND) == InteractionHand.MAIN_HAND) player.getAttackAnim(partialTick) else 0f
        val pitch = player.getViewXRot(partialTick)
        if (adopted) {
            pose.mulPose(Axis.XP.rotationDegrees((pitch - Mth.lerp(partialTick, xBobO, xBob)) * SWAY))
            pose.mulPose(Axis.YP.rotationDegrees(Mth.wrapDegrees(player.getViewYRot(partialTick) - Mth.rotLerp(partialTick, yBobO, yBob)) * SWAY))
        }
        val equip = if (adopted) 1f - Mth.lerp(partialTick, previous, height) else 0f
        val light = minecraft.entityRenderDispatcher.getPackedLightCoords(player, partialTick)
        (hands as ArmInvoker).`substratum$renderArm`(player, partialTick, pitch, InteractionHand.MAIN_HAND, swing, stack, equip, pose, buffers, light)
        buffers.endBatch()
        return true
    }
}
