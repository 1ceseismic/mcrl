package dev.mcrl.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;

import dev.mcrl.client.sound.RlSoundInstance;

@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
	// vanilla clamps pitch to 0.5..2; RL needs wider
	@Inject(method = "calculatePitch", at = @At("HEAD"), cancellable = true)
	private void mcrl$widePitch(SoundInstance instance, CallbackInfoReturnable<Float> cir) {
		if (instance instanceof RlSoundInstance) {
			cir.setReturnValue(RlSoundInstance.clampPitch(instance.getPitch()));
		}
	}
}
