package xyz.thewhish.substratum.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.thewhish.substratum.config.ServerConfig;
import xyz.thewhish.substratum.level.SubstratumLevels;

@Mixin(LivingEntity.class)
abstract class DeathLootMixin {

    @Inject(method = "dropAllDeathLoot", at = @At("HEAD"), cancellable = true)
    private void substratum$noDeathLootInLevel0(ServerLevel level, DamageSource damageSource, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (level.dimension() != SubstratumLevels.INSTANCE.getLEVEL_0()) return;
        if (!(self instanceof Player) || ServerConfig.INSTANCE.getKeepInventory()) {
            ci.cancel();
        }
    }
}
