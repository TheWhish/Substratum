package xyz.thewhish.substratum.fabric.mixin.immptl;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.miscellaneous.GcMonitor;

@Mixin(GcMonitor.class)
abstract class MixinIPGcMonitor {

    @Inject(method = "initClient", at = @At("HEAD"), cancellable = true)
    private static void substratum$silenceMemoryWarnings(CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "informMemoryNotEnoughClient", at = @At("HEAD"), cancellable = true)
    private static void substratum$silenceMemoryChat(CallbackInfo ci) {
        ci.cancel();
    }
}
