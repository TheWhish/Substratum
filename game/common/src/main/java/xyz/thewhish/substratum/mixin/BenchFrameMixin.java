package xyz.thewhish.substratum.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.thewhish.substratum.client.Bench;
import xyz.thewhish.substratum.client.Probe;

@Mixin(GameRenderer.class)
abstract class BenchFrameMixin {

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"))
    private void substratum$frameStart(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        Bench.frameStart();
        Probe.frameStart();
    }

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("RETURN"))
    private void substratum$worldDone(DeltaTracker deltaTracker, CallbackInfo ci) {
        Bench.worldDone();
    }

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("RETURN"))
    private void substratum$frameEnd(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        Bench.frameEnd();
        Probe.frameEnd();
    }
}
