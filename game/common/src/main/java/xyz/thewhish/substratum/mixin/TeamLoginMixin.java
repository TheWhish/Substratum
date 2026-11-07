package xyz.thewhish.substratum.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.thewhish.substratum.network.Gate;

@Mixin(ServerLoginPacketListenerImpl.class)
abstract class TeamLoginMixin {

    @Shadow
    public abstract void disconnect(Component reason);

    @Redirect(
            method = "handleHello",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/core/UUIDUtil;createOfflineProfile(Ljava/lang/String;)Lcom/mojang/authlib/GameProfile;"
            ),
            require = 1,
            allow = 1
    )
    private GameProfile substratum$teamProfile(String name) {
        return Gate.profile(name);
    }

    @Inject(method = "startClientVerification", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void substratum$onlyParticipants(GameProfile profile, CallbackInfo callback) {
        if (!Gate.admits(profile)) {
            this.disconnect(Component.translatable("menu.substratum.not_participant"));
            callback.cancel();
        }
    }
}
