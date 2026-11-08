package xyz.thewhish.substratum.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocalPlayer.class)
abstract class DropStackMixin {

    @Inject(method = "drop(Z)Z", at = @At("HEAD"), cancellable = true)
    private void substratum$oneAtATime(boolean fullStack, CallbackInfoReturnable<Boolean> cir) {
        if (fullStack) cir.setReturnValue(((LocalPlayer) (Object) this).drop(false));
    }
}
