package xyz.thewhish.substratum.mixin;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.thewhish.substratum.client.LightField;

@Mixin(EntityRenderer.class)
abstract class EntityLightMixin<T extends Entity> {

    @Inject(method = "getBlockLightLevel", at = @At("HEAD"), cancellable = true)
    private void substratum$smoothLight(T entity, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (entity.isOnFire()) return;
        int light = LightField.entityLight(entity.level(), entity, pos);
        if (light >= 0) cir.setReturnValue(light);
    }
}
