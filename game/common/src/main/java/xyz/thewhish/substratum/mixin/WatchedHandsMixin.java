package xyz.thewhish.substratum.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import xyz.thewhish.substratum.client.Collapse;
import xyz.thewhish.substratum.client.WatchedHands;

@Mixin(GameRenderer.class)
abstract class WatchedHandsMixin {

    @ModifyExpressionValue(
        method = "renderItemInHand",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;getPlayerMode()Lnet/minecraft/world/level/GameType;")
    )
    private GameType substratum$showWatchedHands(GameType mode) {
        return WatchedHands.showing() ? GameType.ADVENTURE : mode;
    }

    @WrapOperation(
        method = "renderItemInHand",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V"
        )
    )
    private void substratum$renderWatchedHands(ItemInHandRenderer hands, float partialTick, PoseStack pose, MultiBufferSource.BufferSource buffers,
                                               LocalPlayer player, int light, Operation<Void> original) {
        Collapse.hands(pose, partialTick);
        if (!WatchedHands.render(hands, partialTick, pose, buffers)) original.call(hands, partialTick, pose, buffers, player, light);
    }
}
