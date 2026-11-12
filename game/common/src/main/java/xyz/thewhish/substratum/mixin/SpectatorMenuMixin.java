package xyz.thewhish.substratum.mixin;

import net.minecraft.client.gui.components.spectator.SpectatorGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpectatorGui.class)
abstract class SpectatorMenuMixin {

    @Inject(method = "onHotbarSelected", at = @At("HEAD"), cancellable = true)
    private void substratum$noMenuFromKeys(int slot, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "onMouseMiddleClick", at = @At("HEAD"), cancellable = true)
    private void substratum$noMenuFromWheel(CallbackInfo ci) {
        ci.cancel();
    }
}
