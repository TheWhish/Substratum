package xyz.thewhish.substratum.mixin;

import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.thewhish.substratum.level.Hands;

@Mixin(Inventory.class)
abstract class InventoryMixin {

    @Inject(method = "getFreeSlot", at = @At("HEAD"), cancellable = true)
    private void substratum$onlyHands(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(Hands.freeSlot((Inventory) (Object) this));
    }
}
