package xyz.thewhish.substratum.mixin;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.renderer.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import xyz.thewhish.substratum.client.WatchedHands;

@Mixin(ItemInHandRenderer.class)
abstract class WatchedArmMixin {

    @ModifyArg(
        method = "renderPlayerArm",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;getRenderer(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/client/renderer/entity/EntityRenderer;"
        )
    )
    private Entity substratum$watchedRenderer(Entity player) {
        return player instanceof AbstractClientPlayer local ? WatchedHands.owner(local) : player;
    }

    @ModifyArg(
        method = "renderPlayerArm",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/player/PlayerRenderer;renderRightHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;)V"
        ),
        index = 3
    )
    private AbstractClientPlayer substratum$watchedRightArm(AbstractClientPlayer player) {
        return WatchedHands.owner(player);
    }

    @ModifyArg(
        method = "renderPlayerArm",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/player/PlayerRenderer;renderLeftHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;)V"
        ),
        index = 3
    )
    private AbstractClientPlayer substratum$watchedLeftArm(AbstractClientPlayer player) {
        return WatchedHands.owner(player);
    }
}
