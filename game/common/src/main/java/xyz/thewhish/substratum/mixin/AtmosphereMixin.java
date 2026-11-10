package xyz.thewhish.substratum.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.thewhish.substratum.client.Atmosphere;
import xyz.thewhish.substratum.client.MenuShot;
import xyz.thewhish.substratum.client.Probe;
import xyz.thewhish.substratum.client.ProbeView;
import xyz.thewhish.substratum.client.TerrainLight;
import xyz.thewhish.substratum.smiler.client.Vanish;

@Mixin(GameRenderer.class)
abstract class AtmosphereMixin {

    @Shadow @Final Minecraft minecraft;
    @Shadow private boolean renderHand;

    @WrapOperation(
        method = "renderLevel",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V")
    )
    private void substratum$haze(
        LevelRenderer renderer,
        DeltaTracker deltaTracker,
        boolean renderBlockOutline,
        Camera camera,
        GameRenderer gameRenderer,
        LightTexture lightTexture,
        Matrix4f view,
        Matrix4f projection,
        Operation<Void> original
    ) {
        Probe.push("вид мира");
        TerrainLight.beforeWorld(this.minecraft, camera, deltaTracker.getGameTimeDeltaPartialTick(false));
        original.call(renderer, deltaTracker, renderBlockOutline, camera, gameRenderer, lightTexture, view, projection);
        TerrainLight.afterWorld();
        Probe.popPush("Substratum: свет ламп и дымка");
        ProbeView.capture(camera, view, projection);
        MenuShot.afterWorld(camera, view, projection);
        Vanish.afterFrame(camera);
        Atmosphere.renderWorld(this.minecraft, deltaTracker.getGameTimeDeltaPartialTick(false), camera, view, projection, this.renderHand);
        Probe.pop();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void substratum$catchUp(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        if (renderLevel) Atmosphere.catchUp(this.minecraft);
    }

    @Inject(
        method = "render",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;doEntityOutline()V", shift = At.Shift.AFTER)
    )
    private void substratum$atmosphere(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        Probe.push("Substratum: свечение и плёнка");
        Atmosphere.render(this.minecraft, deltaTracker.getGameTimeDeltaTicks(), deltaTracker.getGameTimeDeltaPartialTick(false));
        Probe.pop();
    }
}
