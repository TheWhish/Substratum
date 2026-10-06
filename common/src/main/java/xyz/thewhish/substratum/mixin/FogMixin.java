package xyz.thewhish.substratum.mixin;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Unique;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import xyz.thewhish.substratum.client.DepthFog;
import xyz.thewhish.substratum.client.ProbeView;
import xyz.thewhish.substratum.client.ShaftDarkness;
import xyz.thewhish.substratum.level.SubstratumLevels;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FogRenderer.class)
abstract class FogMixin {

    @Shadow
    private static float fogRed;

    @Shadow
    private static float fogGreen;

    @Shadow
    private static float fogBlue;

    @Unique
    private static final float DEEP_FOG_END = 1.75F;

    @Unique
    private static final float UNCULLED_FOG_ALPHA = 0.999F;

    @Inject(method = "setupFog", at = @At("RETURN"))
    private static void substratum$shaftFogDistance(
        Camera camera,
        FogRenderer.FogMode fogMode,
        float farPlaneDistance,
        boolean isFoggy,
        float partialTick,
        CallbackInfo ci
    ) {
        ClientLevel level = Minecraft.getInstance().level;
        boolean haze = fogMode == FogRenderer.FogMode.FOG_TERRAIN && DepthFog.applies(level, camera);
        if (haze) {
            RenderSystem.setShaderFogStart(DepthFog.start(farPlaneDistance, partialTick));
            RenderSystem.setShaderFogEnd(DepthFog.end(farPlaneDistance, partialTick));
            RenderSystem.setShaderFogShape(FogShape.SPHERE);
            float[] colour = RenderSystem.getShaderFogColor();
            RenderSystem.setShaderFogColor(colour[0], colour[1], colour[2], UNCULLED_FOG_ALPHA);
        }
        double amount = ShaftDarkness.darkAmount(level, camera);
        if (amount > 0.0) {
            float end = Mth.lerp((float) amount, haze ? DepthFog.end(farPlaneDistance, partialTick) : farPlaneDistance, DEEP_FOG_END);
            RenderSystem.setShaderFogStart(end * 0.25F);
            RenderSystem.setShaderFogEnd(end);
            RenderSystem.setShaderFogShape(FogShape.SPHERE);
        }
        if (fogMode == FogRenderer.FogMode.FOG_TERRAIN && level != null && level.dimension() == SubstratumLevels.INSTANCE.getLEVEL_0()) {
            DepthFog.remember(RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd());
        }
        if (fogMode == FogRenderer.FogMode.FOG_TERRAIN && !PortalRendering.isRendering()) {
            ProbeView.rememberFog(RenderSystem.getShaderFogEnd(), RenderSystem.getShaderFogShape());
        }
    }

    @Inject(method = "setupColor", at = @At("RETURN"))
    private static void substratum$shaftFogColor(
        Camera camera,
        float partialTick,
        ClientLevel level,
        int renderDistance,
        float darkenWorldAmount,
        CallbackInfo ci
    ) {
        if (DepthFog.applies(level, camera)) {
            fogRed = DepthFog.colour(0);
            fogGreen = DepthFog.colour(1);
            fogBlue = DepthFog.colour(2);
            RenderSystem.clearColor(fogRed, fogGreen, fogBlue, 0.0F);
        }
        double amount = ShaftDarkness.darkAmount(level, camera);
        if (amount <= 0.0) {
            return;
        }
        float keep = (float) (1.0 - amount);
        fogRed *= keep;
        fogGreen *= keep;
        fogBlue *= keep;
        RenderSystem.clearColor(fogRed, fogGreen, fogBlue, 0.0F);
    }
}
