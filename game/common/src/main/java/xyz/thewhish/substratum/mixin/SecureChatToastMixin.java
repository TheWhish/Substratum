package xyz.thewhish.substratum.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
abstract class SecureChatToastMixin {

    @Shadow
    private boolean seenInsecureChatWarning;

    @Inject(method = "handleLogin", at = @At("HEAD"), require = 1, allow = 1)
    private void substratum$teamChatIsTrusted(ClientboundLoginPacket packet, CallbackInfo callback) {
        this.seenInsecureChatWarning = true;
    }
}
