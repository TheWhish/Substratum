package xyz.thewhish.substratum.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.Music;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.thewhish.substratum.client.menu.Menus;

@Mixin(Minecraft.class)
abstract class MenuMusicMixin {

    @Shadow
    @Nullable
    public LocalPlayer player;

    @Inject(method = "getSituationalMusic", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void substratum$menuMusic(CallbackInfoReturnable<Music> callback) {
        if (this.player == null) {
            callback.setReturnValue(Menus.MUSIC);
        }
    }
}
