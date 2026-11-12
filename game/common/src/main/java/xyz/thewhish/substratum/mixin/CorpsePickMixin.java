package xyz.thewhish.substratum.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import xyz.thewhish.substratum.level.Remains;

import java.util.function.Predicate;

@Mixin(GameRenderer.class)
abstract class CorpsePickMixin {

    @WrapOperation(
        method = "pick(Lnet/minecraft/world/entity/Entity;DDF)Lnet/minecraft/world/phys/HitResult;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/projectile/ProjectileUtil;getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;"
        )
    )
    private EntityHitResult substratum$pickCorpseShape(Entity viewer, Vec3 from, Vec3 to, AABB area, Predicate<Entity> filter, double reach,
                                                       Operation<EntityHitResult> original) {
        return original.call(viewer, from, to, area, filter.and(entity -> !(entity instanceof Remains remains) || remains.hitBy(from, to)), reach);
    }
}
