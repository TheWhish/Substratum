package xyz.thewhish.substratum.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.thewhish.substratum.client.SanityEffects;

@Mixin(GameRenderer.class)
abstract class FovMixin {

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void substratum$breathe(Camera camera, float partialTick, boolean useFovSetting, CallbackInfoReturnable<Double> cir) {
        if (useFovSetting) cir.setReturnValue(cir.getReturnValueD() + SanityEffects.fovOffset(partialTick));
    }
}
