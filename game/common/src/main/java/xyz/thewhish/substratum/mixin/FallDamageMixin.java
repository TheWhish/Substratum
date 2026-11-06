package xyz.thewhish.substratum.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.thewhish.substratum.level.PitExitGuard;

@Mixin(LivingEntity.class)
abstract class FallDamageMixin {

    @Inject(method = "causeFallDamage", at = @At("HEAD"), cancellable = true)
    private void substratum$cancelExitFall(float fallDistance, float damageMultiplier, DamageSource source, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof ServerPlayer player && PitExitGuard.INSTANCE.consumeFallImmunity(player)) {
            cir.setReturnValue(false);
        }
    }
}
