package xyz.thewhish.substratum.mixin;

import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import xyz.thewhish.substratum.client.menu.Menus;

@Mixin(OptionInstance.class)
abstract class OptionLockMixin {

    @Shadow @Final Component caption;

    @ModifyVariable(method = "set", at = @At("HEAD"), argsOnly = true)
    private Object substratum$lockOption(Object value) {
        if (!(this.caption.getContents() instanceof TranslatableContents contents)) return value;
        return switch (contents.getKey()) {
            case "options.gamma" -> Menus.GAMMA;
            case "options.fov" -> Menus.FOV;
            default -> value;
        };
    }
}
