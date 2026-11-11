package xyz.thewhish.substratum.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.thewhish.substratum.client.Drinking;

@Mixin(ItemInHandRenderer.class)
abstract class DrinkingHandMixin {

    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void substratum$holdTheBottle(AbstractClientPlayer player, float partialTick, float pitch, InteractionHand hand, float swing,
                                         ItemStack stack, float equipProgress, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (hand != InteractionHand.MAIN_HAND) return;
        if (Drinking.renderFirstPerson(player, stack, equipProgress, pose, buffers, light, (ItemInHandRenderer) (Object) this)) ci.cancel();
    }
}
