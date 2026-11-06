package xyz.thewhish.substratum.fabric.mixin.immptl;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationServer;

@Mixin(ServerPlayerGameMode.class)
abstract class MixinIPServerPlayerGameMode {

    @Shadow
    private boolean isDestroyingBlock;
    @Shadow
    private boolean hasDelayedDestroy;

    @Unique
    private BlockManipulationServer.Context substratum$destroyRedirect;
    @Unique
    private boolean substratum$appliedDestroyRedirect;

    @Inject(method = "handleBlockBreakAction", at = @At("RETURN"))
    private void substratum$captureDestroyRedirect(
        BlockPos blockPos, ServerboundPlayerActionPacket.Action action, Direction direction, int maxBuildHeight, int sequence, CallbackInfo ci
    ) {
        BlockManipulationServer.Context context = BlockManipulationServer.REDIRECT_CONTEXT.get();
        substratum$destroyRedirect = context != null && (this.isDestroyingBlock || this.hasDelayedDestroy) ? context : null;
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void substratum$applyDestroyRedirect(CallbackInfo ci) {
        if (substratum$destroyRedirect != null && BlockManipulationServer.REDIRECT_CONTEXT.get() == null) {
            BlockManipulationServer.REDIRECT_CONTEXT.set(substratum$destroyRedirect);
            substratum$appliedDestroyRedirect = true;
        }
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void substratum$clearDestroyRedirect(CallbackInfo ci) {
        if (substratum$appliedDestroyRedirect) {
            BlockManipulationServer.REDIRECT_CONTEXT.remove();
            substratum$appliedDestroyRedirect = false;
        }
        if (!this.isDestroyingBlock && !this.hasDelayedDestroy) {
            substratum$destroyRedirect = null;
        }
    }
}
