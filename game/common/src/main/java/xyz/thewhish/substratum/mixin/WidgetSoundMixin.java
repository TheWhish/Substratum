package xyz.thewhish.substratum.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.thewhish.substratum.registry.ModSounds;

@Mixin(AbstractWidget.class)
abstract class WidgetSoundMixin {

    @Shadow protected boolean isHovered;
    @Shadow public boolean active;

    @Unique
    private boolean substratum$wasHovered;

    @Inject(method = "playDownSound", at = @At("HEAD"), cancellable = true)
    private void substratum$press(SoundManager sounds, CallbackInfo ci) {
        sounds.play(SimpleSoundInstance.forUI(ModSounds.INSTANCE.getMENU_PRESS().get(), 1f));
        ci.cancel();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void substratum$rememberHover(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        this.substratum$wasHovered = this.isHovered;
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void substratum$hover(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (this.active && this.isHovered && !this.substratum$wasHovered) {
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(ModSounds.INSTANCE.getMENU_HOVER().get(), 1f));
        }
    }
}
