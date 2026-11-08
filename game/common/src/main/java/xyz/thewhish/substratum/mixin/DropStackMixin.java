package xyz.thewhish.substratum.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(LocalPlayer.class)
abstract class DropStackMixin {

    @ModifyVariable(method = "drop", at = @At("HEAD"), argsOnly = true, name = "bl")
    private boolean substratum$oneAtATime(boolean fullStack) {
        return false;
    }
}
