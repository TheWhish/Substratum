package xyz.thewhish.substratum.mixin;

import net.minecraft.world.entity.player.Abilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Abilities.class)
abstract class WalkSpeedMixin {

    @Unique
    private static final float SUBSTRATUM_WALK = 0.11F;

    @Inject(method = "getWalkingSpeed", at = @At("HEAD"), cancellable = true)
    private void substratum$pace(CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(SUBSTRATUM_WALK);
    }
}
