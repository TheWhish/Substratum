package xyz.thewhish.substratum.fabric.mixin.immptl;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.teleportation.ClientTeleportationManager;
import xyz.thewhish.substratum.client.Atmosphere;

@Mixin(ClientTeleportationManager.class)
abstract class MixinIPClientTeleportationManager {

    @Inject(method = "changePlayerDimension", at = @At("RETURN"))
    private static void substratum$settleAtmosphere(LocalPlayer player, ClientLevel fromWorld, ClientLevel toWorld, Vec3 newEyePos, CallbackInfo ci) {
        Atmosphere.settle(Minecraft.getInstance());
    }
}
