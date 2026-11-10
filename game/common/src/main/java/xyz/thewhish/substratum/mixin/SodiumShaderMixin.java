package xyz.thewhish.substratum.mixin;

import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.thewhish.substratum.client.TerrainLight;

@Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader", remap = false)
abstract class SodiumShaderMixin {

    @Inject(method = "getShaderSource", at = @At("RETURN"), cancellable = true)
    private static void substratum$terrainLight(ResourceLocation name, CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(TerrainLight.patch(name, cir.getReturnValue()));
    }
}
