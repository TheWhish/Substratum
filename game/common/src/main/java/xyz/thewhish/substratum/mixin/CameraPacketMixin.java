package xyz.thewhish.substratum.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.thewhish.substratum.client.Feed;

@Mixin(ClientPacketListener.class)
abstract class CameraPacketMixin {

    @Inject(method = "handleSetCamera", at = @At("TAIL"))
    private void substratum$awaitCamera(ClientboundSetCameraPacket packet, CallbackInfo ci) {
        Feed.camera(packet);
    }
}
