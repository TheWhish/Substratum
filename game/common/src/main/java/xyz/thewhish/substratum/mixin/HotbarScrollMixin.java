package xyz.thewhish.substratum.mixin;

import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.thewhish.substratum.level.Hands;

@Mixin(Inventory.class)
abstract class HotbarScrollMixin {

    @Shadow
    public int selected;

    @Inject(method = "swapPaint", at = @At("HEAD"), cancellable = true)
    private void substratum$scrollWithinHands(double direction, CallbackInfo ci) {
        selected = Hands.scroll(selected, direction);
        ci.cancel();
    }
}
