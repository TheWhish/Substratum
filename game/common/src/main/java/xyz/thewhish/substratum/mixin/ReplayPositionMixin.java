package xyz.thewhish.substratum.mixin;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamDecoder;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IEPlayerPositionLookS2CPacket;
import qouteall.imm_ptl.core.network.ImmPtlNetworkConfig;
import xyz.thewhish.substratum.network.ReplayPosition;

@Mixin(ClientboundPlayerPositionPacket.class)
abstract class ReplayPositionMixin {

    @ModifyArg(method = "<clinit>", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/Packet;codec(Lnet/minecraft/network/codec/StreamMemberEncoder;Lnet/minecraft/network/codec/StreamDecoder;)Lnet/minecraft/network/codec/StreamCodec;"), index = 1)
    private static StreamDecoder<FriendlyByteBuf, ClientboundPlayerPositionPacket> substratum$acceptVanillaLayout(StreamDecoder<FriendlyByteBuf, ClientboundPlayerPositionPacket> decoder) {
        return buf -> {
            if (!ImmPtlNetworkConfig.doesServerHaveImmPtl() || !ReplayPosition.lacksDimension(buf)) return decoder.decode(buf);
            FriendlyByteBuf completed = new FriendlyByteBuf(Unpooled.buffer());
            completed.writeBytes(buf);
            completed.writeResourceKey(Level.OVERWORLD);
            ClientboundPlayerPositionPacket packet = decoder.decode(completed);
            ((IEPlayerPositionLookS2CPacket) packet).ip_setPlayerDimension(null);
            return packet;
        };
    }

    @Inject(method = "handle(Lnet/minecraft/network/protocol/game/ClientGamePacketListener;)V", at = @At("HEAD"))
    private void substratum$inheritDimension(ClientGamePacketListener listener, CallbackInfo ci) {
        IEPlayerPositionLookS2CPacket packet = (IEPlayerPositionLookS2CPacket) this;
        Minecraft minecraft = Minecraft.getInstance();
        if (packet.ip_getPlayerDimension() != null || !minecraft.isSameThread() || minecraft.player == null) return;
        packet.ip_setPlayerDimension(minecraft.player.level().dimension());
    }
}
