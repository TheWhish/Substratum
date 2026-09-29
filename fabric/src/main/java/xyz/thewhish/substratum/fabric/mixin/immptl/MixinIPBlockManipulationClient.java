package xyz.thewhish.substratum.fabric.mixin.immptl;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationClient;

@Mixin(BlockManipulationClient.class)
abstract class MixinIPBlockManipulationClient {

    @Redirect(
        method = "updatePointedBlock",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;getPosition()Lnet/minecraft/world/phys/Vec3;"
        )
    )
    private static Vec3 substratum$pickFromEye(Camera camera, float partialTick) {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? camera.getPosition() : player.getEyePosition(partialTick);
    }
}
