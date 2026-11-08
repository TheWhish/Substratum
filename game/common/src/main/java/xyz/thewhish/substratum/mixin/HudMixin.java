package xyz.thewhish.substratum.mixin;

import dev.architectury.platform.Platform;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.PlayerRideableJumping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
abstract class HudMixin {

    @Inject(method = {"renderPlayerHealth", "renderVehicleHealth"}, at = @At("HEAD"), cancellable = true)
    private void substratum$noHealth(GuiGraphics graphics, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "renderExperienceBar", at = @At("HEAD"), cancellable = true)
    private void substratum$noExperienceBar(GuiGraphics graphics, int x, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "renderJumpMeter", at = @At("HEAD"), cancellable = true)
    private void substratum$noJumpMeter(PlayerRideableJumping mount, GuiGraphics graphics, int x, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(
            method = {"renderExperienceLevel", "renderEffects", "renderTabList", "renderScoreboardSidebar"},
            at = @At("HEAD"),
            cancellable = true
    )
    private void substratum$noVanillaOverlay(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "renderChat", at = @At("HEAD"), cancellable = true)
    private void substratum$noChat(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
        if (!Platform.isDevelopmentEnvironment()) ci.cancel();
    }
}
