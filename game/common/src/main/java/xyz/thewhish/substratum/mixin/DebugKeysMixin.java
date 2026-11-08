package xyz.thewhish.substratum.mixin;

import dev.architectury.platform.Platform;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(KeyboardHandler.class)
abstract class DebugKeysMixin {

    @Inject(method = {"handleDebugKeys", "handleChunkDebugKeys"}, at = @At("HEAD"), cancellable = true)
    private void substratum$noDebugKeys(int key, CallbackInfoReturnable<Boolean> cir) {
        if (!Platform.isDevelopmentEnvironment()) cir.setReturnValue(false);
    }
}
