package xyz.thewhish.substratum.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ItemInHandRenderer.class)
public interface ArmInvoker {

    @Invoker("renderArmWithItem")
    void substratum$renderArm(AbstractClientPlayer player, float partialTick, float pitch, InteractionHand hand, float swing,
                              ItemStack stack, float equipProgress, PoseStack pose, MultiBufferSource buffers, int light);
}
