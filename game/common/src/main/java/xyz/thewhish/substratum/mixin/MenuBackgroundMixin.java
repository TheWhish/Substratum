package xyz.thewhish.substratum.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.thewhish.substratum.client.menu.Menus;

@Mixin(Screen.class)
abstract class MenuBackgroundMixin {

    @Shadow
    public int width;

    @Shadow
    public int height;

    @Inject(method = "renderPanorama", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void substratum$ownBackground(GuiGraphics graphics, float partialTick, CallbackInfo callback) {
        graphics.fillGradient(0, 0, this.width, this.height, Menus.SKY, Menus.FLOOR);
        callback.cancel();
    }
}
