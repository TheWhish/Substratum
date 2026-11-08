package xyz.thewhish.substratum.mixin;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.thewhish.substratum.client.SanityEffects;

@Mixin(SoundEngine.class)
abstract class HearingMixin {

    @Inject(method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F", at = @At("RETURN"), cancellable = true)
    private void substratum$fadeWorld(SoundInstance sound, CallbackInfoReturnable<Float> cir) {
        if (sound.getSource() == SoundSource.MASTER || sound.getSource() == SoundSource.VOICE) return;
        cir.setReturnValue(cir.getReturnValue() * SanityEffects.hearing());
    }
}
