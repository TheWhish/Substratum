package xyz.thewhish.substratum.mixin;

import com.llamalad7.mixinextras.injector.WrapWithCondition;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.thewhish.substratum.client.Collapse;

@Mixin(MouseHandler.class)
abstract class FrozenMouseMixin {

    @WrapWithCondition(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private boolean substratum$stillWhileFalling(LocalPlayer player, double yaw, double pitch) {
        return !Collapse.frozen();
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void substratum$noScrollWhileFalling(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (Collapse.frozen()) ci.cancel();
    }
}
