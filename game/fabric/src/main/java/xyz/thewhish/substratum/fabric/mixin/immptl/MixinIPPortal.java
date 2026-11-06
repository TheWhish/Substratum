package xyz.thewhish.substratum.fabric.mixin.immptl;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.portal.Portal;
import xyz.thewhish.substratum.level.PitExitGuard;

@Mixin(Portal.class)
abstract class MixinIPPortal {

    @Inject(method = "onEntityTeleportedOnServer", at = @At("HEAD"))
    private void substratum$onCrossed(Entity entity, CallbackInfo ci) {
        PitExitGuard.INSTANCE.onPortalCrossed((Portal) (Object) this, entity);
    }
}
