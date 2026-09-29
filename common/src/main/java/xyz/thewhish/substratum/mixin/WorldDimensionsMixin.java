package xyz.thewhish.substratum.mixin;

import com.mojang.serialization.Lifecycle;
import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.thewhish.substratum.Substratum;

@Mixin(WorldDimensions.class)
abstract class WorldDimensionsMixin {

    @Shadow
    @Final
    private static int VANILLA_DIMENSION_COUNT;

    @Redirect(
            method = "bake",
            at = @At(value = "INVOKE", target = "Ljava/util/List;size()I"),
            require = 1,
            allow = 1
    )
    private int substratum$extraDimensionsAreNotExperimental(List<?> entries) {
        return Math.min(entries.size(), VANILLA_DIMENSION_COUNT);
    }

    @Inject(method = "checkStability", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private static void substratum$ownDimensionsAreStable(
            ResourceKey<LevelStem> key,
            LevelStem stem,
            CallbackInfoReturnable<Lifecycle> callback
    ) {
        if (Substratum.ID.equals(key.location().getNamespace())) {
            callback.setReturnValue(Lifecycle.stable());
        }
    }
}
