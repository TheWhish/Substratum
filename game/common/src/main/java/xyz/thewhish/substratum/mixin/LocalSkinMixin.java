package xyz.thewhish.substratum.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.thewhish.substratum.client.LocalSkin;

@Mixin(PlayerInfo.class)
abstract class LocalSkinMixin {

    @Shadow
    public abstract GameProfile getProfile();

    @Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
    private void substratum$launcherSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        PlayerSkin skin = LocalSkin.of(getProfile());
        if (skin != null) cir.setReturnValue(skin);
    }
}
