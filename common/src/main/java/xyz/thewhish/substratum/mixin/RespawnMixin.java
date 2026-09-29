package xyz.thewhish.substratum.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.thewhish.substratum.level.Homecoming;
import xyz.thewhish.substratum.level.SubstratumLevels;

@Mixin(ServerPlayer.class)
abstract class RespawnMixin {

    @Inject(method = "findRespawnPositionAndUseSpawnBlock", at = @At("HEAD"), cancellable = true)
    private void substratum$wakeUpAwayFromLevel0(
        boolean keepInventory,
        DimensionTransition.PostDimensionTransition post,
        CallbackInfoReturnable<DimensionTransition> cir
    ) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (self.level().dimension() == SubstratumLevels.INSTANCE.getLEVEL_0()) {
            cir.setReturnValue(Homecoming.INSTANCE.respawn(self, post));
        }
    }
}
