package xyz.thewhish.substratum.fabric.mixin.immptl;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.IPModMainClient;

@Mixin(IPModMainClient.class)
abstract class MixinIPModMainClient {

    @Inject(method = "showNvidiaVideoCardWarning", at = @At("HEAD"), cancellable = true)
    private static void substratum$silenceNvidiaWarning(CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "showQuiltWarning", at = @At("HEAD"), cancellable = true)
    private static void substratum$silenceQuiltWarning(CallbackInfo ci) {
        ci.cancel();
    }
}
