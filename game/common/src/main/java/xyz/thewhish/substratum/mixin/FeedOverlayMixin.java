package xyz.thewhish.substratum.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import xyz.thewhish.substratum.client.Feed;

@Mixin(GameRenderer.class)
abstract class FeedOverlayMixin {

    @WrapOperation(
        method = "render",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V")
    )
    private void substratum$coverEverything(Gui gui, GuiGraphics graphics, DeltaTracker delta, Operation<Void> original) {
        original.call(gui, graphics, delta);
        Feed.renderOverlay(graphics, delta.getGameTimeDeltaPartialTick(false));
    }
}
