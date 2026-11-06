package xyz.thewhish.substratum.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import xyz.thewhish.substratum.client.Atmosphere;

@Mixin(LevelRenderer.class)
abstract class SceneDepthMixin {

    @Shadow @Final private Minecraft minecraft;
    @Shadow private PostChain transparencyChain;

    @WrapOperation(
        method = "renderLevel",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/PostChain;process(F)V")
    )
    private void substratum$captureSceneDepth(PostChain chain, float frameTicks, Operation<Void> original) {
        if (chain == this.transparencyChain) {
            Atmosphere.captureSceneDepth(this.minecraft);
        }
        original.call(chain, frameTicks);
    }
}
