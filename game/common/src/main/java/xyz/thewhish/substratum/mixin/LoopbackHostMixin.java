package xyz.thewhish.substratum.mixin;

import java.net.InetAddress;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(IntegratedServer.class)
abstract class LoopbackHostMixin {

    @ModifyArg(
            method = "publishServer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerConnectionListener;startTcpServerListener(Ljava/net/InetAddress;I)V"
            ),
            index = 0,
            require = 1,
            allow = 1
    )
    private InetAddress substratum$onlyThisComputer(InetAddress address) {
        return InetAddress.getLoopbackAddress();
    }
}
