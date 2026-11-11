package xyz.thewhish.substratum.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import xyz.thewhish.substratum.client.Drinking;

@Mixin(GameRenderer.class)
abstract class DrinkingViewMixin {

    @WrapOperation(
        method = "renderLevel",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;bobHurt(Lcom/mojang/blaze3d/vertex/PoseStack;F)V")
    )
    private void substratum$tipTheHead(GameRenderer renderer, PoseStack pose, float partialTick, Operation<Void> original) {
        original.call(renderer, pose, partialTick);
        float pitch = Drinking.viewPitch();
        if (pitch != 0f) pose.mulPose(Axis.XP.rotationDegrees(pitch));
    }
}
