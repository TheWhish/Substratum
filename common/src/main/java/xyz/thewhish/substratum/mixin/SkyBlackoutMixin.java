package xyz.thewhish.substratum.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.thewhish.substratum.client.ShaftDarkness;

@Mixin(LevelRenderer.class)
abstract class SkyBlackoutMixin {

    @Unique
    private static final float SKY_HIDDEN_ABOVE = 0.1F;

    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private void substratum$hideSkyDuringCrossing(
        Matrix4f frustumMatrix,
        Matrix4f projectionMatrix,
        float partialTick,
        Camera camera,
        boolean isFoggy,
        Runnable skyFogSetup,
        CallbackInfo ci
    ) {
        if (ShaftDarkness.darkAmount(Minecraft.getInstance().level, camera) > SKY_HIDDEN_ABOVE) {
            ci.cancel();
        }
    }
}
