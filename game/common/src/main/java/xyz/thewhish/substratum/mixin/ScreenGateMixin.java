package xyz.thewhish.substratum.mixin;

import dev.architectury.platform.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.social.SocialInteractionsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class ScreenGateMixin {

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void substratum$noVanillaScreens(Screen screen, CallbackInfo ci) {
        if (screen instanceof AdvancementsScreen || screen instanceof SocialInteractionsScreen) {
            ci.cancel();
            return;
        }
        if (Platform.isDevelopmentEnvironment()) return;
        if (screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen) ci.cancel();
    }

    @Inject(method = "openChatScreen", at = @At("HEAD"), cancellable = true)
    private void substratum$noChat(String prefix, CallbackInfo ci) {
        if (!Platform.isDevelopmentEnvironment()) ci.cancel();
    }
}
