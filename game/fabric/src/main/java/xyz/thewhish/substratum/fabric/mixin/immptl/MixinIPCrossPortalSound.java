package xyz.thewhish.substratum.fabric.mixin.immptl;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import qouteall.imm_ptl.core.teleportation.CrossPortalSound;
import xyz.thewhish.substratum.level.SubstratumLevels;

@Mixin(CrossPortalSound.class)
abstract class MixinIPCrossPortalSound {

    @Unique
    private static final float OUTER_WORLD_VOLUME = 0.25F;

    @ModifyVariable(method = "createCrossPortalSound", at = @At("HEAD"), argsOnly = true, name = "soundVol")
    private static float substratum$muffleOuterWorld(float soundVol) {
        LocalPlayer player = Minecraft.getInstance().player;
        boolean inLevel0 = player != null && player.level().dimension() == SubstratumLevels.INSTANCE.getLEVEL_0();
        return inLevel0 ? soundVol * OUTER_WORLD_VOLUME : soundVol;
    }
}
