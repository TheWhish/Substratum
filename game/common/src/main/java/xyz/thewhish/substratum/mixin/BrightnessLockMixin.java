package xyz.thewhish.substratum.mixin;

import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(OptionInstance.class)
abstract class BrightnessLockMixin {

    @Shadow @Final Component caption;

    @ModifyVariable(method = "set", at = @At("HEAD"), argsOnly = true)
    private Object substratum$lockBrightness(Object value) {
        return this.caption.getContents() instanceof TranslatableContents contents && contents.getKey().equals("options.gamma") ? 0.0 : value;
    }
}
