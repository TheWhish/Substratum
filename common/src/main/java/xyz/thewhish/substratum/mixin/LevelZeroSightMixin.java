package xyz.thewhish.substratum.mixin;

import java.util.function.Consumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import qouteall.imm_ptl.core.chunk_loading.ChunkLoader;
import qouteall.imm_ptl.core.chunk_loading.ChunkVisibility;
import xyz.thewhish.substratum.level.SubstratumLevels;

@Mixin(value = ChunkVisibility.class, remap = false)
abstract class LevelZeroSightMixin {

    @Unique
    private static final int SIGHT_MARGIN = 1;

    @ModifyVariable(method = "foreachBaseChunkLoaders", at = @At("HEAD"), argsOnly = true, name = "func")
    private static Consumer<ChunkLoader> substratum$limitLevelZero(Consumer<ChunkLoader> consumer) {
        return loader -> consumer.accept(
            loader.dimension() == SubstratumLevels.INSTANCE.getLEVEL_0()
                ? new ChunkLoader(loader.dimension(), loader.x(), loader.z(), Math.min(loader.radius(), SubstratumLevels.SIGHT_CHUNKS) + SIGHT_MARGIN)
                : loader
        );
    }
}
