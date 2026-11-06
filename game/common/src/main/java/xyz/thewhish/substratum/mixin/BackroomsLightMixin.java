package xyz.thewhish.substratum.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.thewhish.substratum.client.BackroomsLight;

@Mixin(LightTexture.class)
abstract class BackroomsLightMixin {

    @Shadow private boolean updateLightTexture;
    @Shadow @Final private NativeImage lightPixels;
    @Shadow @Final private DynamicTexture lightTexture;
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "updateLightTexture", at = @At("HEAD"), cancellable = true)
    private void substratum$paintLevel0(float partialTick, CallbackInfo ci) {
        if (!this.updateLightTexture || !BackroomsLight.paint(this.minecraft, this.lightPixels, partialTick)) {
            return;
        }
        this.updateLightTexture = false;
        this.lightTexture.upload();
        ci.cancel();
    }
}
