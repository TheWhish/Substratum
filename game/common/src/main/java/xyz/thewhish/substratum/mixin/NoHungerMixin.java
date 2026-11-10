package xyz.thewhish.substratum.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodConstants;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FoodData.class)
abstract class NoHungerMixin {

    @Shadow private int foodLevel;
    @Shadow private int lastFoodLevel;
    @Shadow private float saturationLevel;
    @Shadow private float exhaustionLevel;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void substratum$noHunger(Player player, CallbackInfo ci) {
        this.foodLevel = FoodConstants.MAX_FOOD;
        this.lastFoodLevel = FoodConstants.MAX_FOOD;
        this.saturationLevel = FoodConstants.START_SATURATION;
        this.exhaustionLevel = 0.0F;
        ci.cancel();
    }
}
