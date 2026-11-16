package xyz.thewhish.substratum.mixin;

import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.thewhish.substratum.client.Shock;

@Mixin(LocalPlayer.class)
abstract class ShakenPaceMixin {

    @Shadow public Input input;

    @Inject(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/Input;tick(ZF)V", shift = At.Shift.AFTER))
    private void substratum$stagger(CallbackInfo ci) {
        float pace = Shock.pace();
        if (pace >= 1f) return;
        this.input.leftImpulse *= pace;
        this.input.forwardImpulse *= pace;
    }
}
