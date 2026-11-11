package xyz.thewhish.substratum.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.thewhish.substratum.client.Suit;

@Mixin(PlayerModel.class)
abstract class SuitAnimationMixin {

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void substratum$animate(LivingEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
        Suit.animate((PlayerModel<?>) (Object) this, entity, limbSwing, limbSwingAmount, ageInTicks);
    }

    @Inject(method = "translateToHand", at = @At("TAIL"))
    private void substratum$holdInGlove(HumanoidArm arm, PoseStack pose, CallbackInfo ci) {
        Suit.holdInGlove((PlayerModel<?>) (Object) this, arm, pose);
    }
}
