package xyz.thewhish.substratum.mixin;

import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.thewhish.substratum.client.Collapse;

@Mixin(Camera.class)
abstract class FallingCameraMixin {

    @Shadow @Final private static Vector3f FORWARDS;
    @Shadow @Final private static Vector3f UP;
    @Shadow @Final private static Vector3f LEFT;
    @Shadow @Final private Vector3f forwards;
    @Shadow @Final private Vector3f up;
    @Shadow @Final private Vector3f left;
    @Shadow @Final private Quaternionf rotation;
    @Shadow private Vec3 position;
    @Shadow private float xRot;
    @Shadow private float yRot;

    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Inject(method = "setup", at = @At("TAIL"))
    private void substratum$fall(BlockGetter level, Entity entity, boolean detached, boolean mirrored, float partialTick, CallbackInfo ci) {
        if (detached) return;
        Vec3 eye = Collapse.aim(entity, partialTick, position, rotation);
        if (eye == null) return;
        setPosition(eye);
        FORWARDS.rotate(rotation, forwards);
        UP.rotate(rotation, up);
        LEFT.rotate(rotation, left);
        yRot = (float) Math.toDegrees(Mth.atan2(-forwards.x, forwards.z));
        xRot = (float) Math.toDegrees(Math.asin(Mth.clamp(-forwards.y, -1f, 1f)));
    }
}
