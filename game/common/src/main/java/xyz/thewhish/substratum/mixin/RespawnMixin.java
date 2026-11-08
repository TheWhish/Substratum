package xyz.thewhish.substratum.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.thewhish.substratum.level.Arrival;

@Mixin(ServerPlayer.class)
abstract class RespawnMixin {

    @Inject(method = "findRespawnPositionAndUseSpawnBlock", at = @At("HEAD"), cancellable = true)
    private void substratum$wakeUpInLevel0(
        boolean keepInventory,
        DimensionTransition.PostDimensionTransition post,
        CallbackInfoReturnable<DimensionTransition> cir
    ) {
        cir.setReturnValue(Arrival.INSTANCE.respawn((ServerPlayer) (Object) this, post));
    }
}
