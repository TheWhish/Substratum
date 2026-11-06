package xyz.thewhish.substratum.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.thewhish.substratum.level.PitExitGuard;

@Mixin(Entity.class)
abstract class FallDistanceResetMixin {

    @Inject(method = "resetFallDistance", at = @At("HEAD"))
    private void substratum$clearExitFallOnReset(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self instanceof ServerPlayer player) {
            PitExitGuard.INSTANCE.clearFallImmunity(player);
        }
    }
}
