package xyz.thewhish.substratum.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.thewhish.substratum.client.Collapse;
import xyz.thewhish.substratum.client.Figure;
import xyz.thewhish.substratum.client.SuitMarks;

@Mixin(PlayerRenderer.class)
abstract class SuitRendererMixin extends LivingEntityRenderer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

    SuitRendererMixin(EntityRendererProvider.Context context, PlayerModel<AbstractClientPlayer> model, float shadow) {
        super(context, model, shadow);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void substratum$marks(EntityRendererProvider.Context context, boolean slim, CallbackInfo ci) {
        addLayer(new SuitMarks(this));
    }

    @Inject(
        method = "setupRotations(Lnet/minecraft/client/player/AbstractClientPlayer;Lcom/mojang/blaze3d/vertex/PoseStack;FFFF)V",
        at = @At("TAIL")
    )
    private void substratum(AbstractClientPlayer player, PoseStack poseStack, float bob, float yaw, float partialTick, float scale, CallbackInfo ci) {
        if (player instanceof Figure figure) figure.slump(poseStack);
        else Collapse.slump(player, poseStack, partialTick);
    }

    @Inject(
        method = "renderNameTag(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void substratum$hideName(AbstractClientPlayer player, Component name, PoseStack poseStack, MultiBufferSource buffer, int light, float partialTick, CallbackInfo ci) {
        ci.cancel();
    }
}
