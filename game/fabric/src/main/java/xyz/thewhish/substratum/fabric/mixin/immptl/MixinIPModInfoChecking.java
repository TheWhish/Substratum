package xyz.thewhish.substratum.fabric.mixin.immptl;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.IPModInfoChecking;

@Mixin(IPModInfoChecking.class)
abstract class MixinIPModInfoChecking {

    @Environment(EnvType.CLIENT)
    @Inject(method = "initClient", at = @At("HEAD"), cancellable = true)
    private static void substratum$silenceClient(CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "initDedicatedServer", at = @At("HEAD"), cancellable = true)
    private static void substratum$silenceServer(CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "checkShaderpack", at = @At("HEAD"), cancellable = true)
    private static void substratum$silenceShaderpack(CallbackInfo ci) {
        ci.cancel();
    }
}
