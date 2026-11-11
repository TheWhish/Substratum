package xyz.thewhish.substratum.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.thewhish.substratum.item.AlmondWaterItem;
import xyz.thewhish.substratum.item.FlashlightItem;

@Mixin(ItemInHandRenderer.class)
abstract class HeldFlashlightMixin {

    @Shadow
    private ItemStack mainHandItem;

    @Inject(method = "tick", at = @At("HEAD"))
    private void substratum$keepSwitchedFlashlight(CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        if (FlashlightItem.Companion.onlySwitched(mainHandItem, player.getMainHandItem())) mainHandItem = player.getMainHandItem();
    }

    @Inject(method = "itemUsed", at = @At("HEAD"), cancellable = true)
    private void substratum$noDipOnUse(InteractionHand hand, CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        Item item = player.getItemInHand(hand).getItem();
        if (item instanceof FlashlightItem || item instanceof AlmondWaterItem) ci.cancel();
    }
}
