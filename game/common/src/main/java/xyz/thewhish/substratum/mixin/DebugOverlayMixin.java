package xyz.thewhish.substratum.mixin;

import dev.architectury.platform.Platform;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DebugScreenOverlay.class)
abstract class DebugOverlayMixin {

    @Inject(method = "showDebugScreen", at = @At("HEAD"), cancellable = true)
    private void substratum$noDebugScreen(CallbackInfoReturnable<Boolean> cir) {
        if (!Platform.isDevelopmentEnvironment()) cir.setReturnValue(false);
    }
}
