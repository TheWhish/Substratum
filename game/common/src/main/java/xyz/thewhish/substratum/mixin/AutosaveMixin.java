package xyz.thewhish.substratum.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(MinecraftServer.class)
abstract class AutosaveMixin {

    @Unique
    private static final float SECONDS_BETWEEN_SAVES = 120.0F;

    @ModifyConstant(
            method = "computeNextAutosaveInterval",
            constant = @Constant(floatValue = 300.0F),
            require = 1,
            allow = 1
    )
    private float substratum$saveEveryTwoMinutes(float seconds) {
        return SECONDS_BETWEEN_SAVES;
    }
}
