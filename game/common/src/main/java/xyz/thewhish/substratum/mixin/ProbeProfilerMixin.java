package xyz.thewhish.substratum.mixin;

import java.util.function.Supplier;
import net.minecraft.util.profiling.InactiveProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.thewhish.substratum.client.Probe;

@Mixin(InactiveProfiler.class)
abstract class ProbeProfilerMixin {

    @Inject(method = "push(Ljava/lang/String;)V", at = @At("HEAD"))
    private void substratum$push(String name, CallbackInfo ci) {
        if (Probe.enabled) Probe.push(name);
    }

    @Inject(method = "push(Ljava/util/function/Supplier;)V", at = @At("HEAD"))
    private void substratum$pushLazy(Supplier<String> name, CallbackInfo ci) {
        if (Probe.enabled) Probe.push(name);
    }

    @Inject(method = "pop", at = @At("HEAD"))
    private void substratum$pop(CallbackInfo ci) {
        if (Probe.enabled) Probe.pop();
    }

    @Inject(method = "popPush(Ljava/lang/String;)V", at = @At("HEAD"))
    private void substratum$popPush(String name, CallbackInfo ci) {
        if (Probe.enabled) Probe.popPush(name);
    }

    @Inject(method = "popPush(Ljava/util/function/Supplier;)V", at = @At("HEAD"))
    private void substratum$popPushLazy(Supplier<String> name, CallbackInfo ci) {
        if (Probe.enabled) Probe.popPush(name);
    }
}
