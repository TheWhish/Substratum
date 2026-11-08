package xyz.thewhish.substratum.mixin;

import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ToastComponent.class)
abstract class ToastMixin {

    @Inject(method = "addToast", at = @At("HEAD"), cancellable = true)
    private void substratum$noToasts(Toast toast, CallbackInfo ci) {
        ci.cancel();
    }
}
