package xyz.thewhish.substratum.fabric.mixin.immptl;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.teleportation.ClientTeleportationManager;
import qouteall.imm_ptl.core.teleportation.TeleportationUtil;
import xyz.thewhish.substratum.client.ShaftDarkness;
import xyz.thewhish.substratum.rift.PitPortals;

@Mixin(ClientTeleportationManager.class)
abstract class MixinIPClientTeleportationManager {

    @Inject(method = "teleportPlayer", at = @At("RETURN"))
    private static void substratum$onTeleported(TeleportationUtil.Teleportation teleportation, float partialTicks, CallbackInfo ci) {
        Portal portal = teleportation.portal();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && PitPortals.EXIT_TAG.equals(portal.portalTag) && player.level().dimension() == portal.getDestDim()) {
            ShaftDarkness.beginSkyFade(player);
        }
    }
}
