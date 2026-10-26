package dev.mcrl.client.mixin;

import java.util.concurrent.CompletableFuture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.blaze3d.audio.SoundBuffer;

import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;

import dev.mcrl.client.sound.RlSoundLibrary;

@Mixin(SoundBufferLibrary.class)
public abstract class SoundBufferLibraryMixin {
	@Inject(method = "getCompleteBuffer", at = @At("HEAD"), cancellable = true)
	private void mcrl$rlBuffer(Identifier path, CallbackInfoReturnable<CompletableFuture<SoundBuffer>> cir) {
		CompletableFuture<SoundBuffer> f = RlSoundLibrary.game().buffer(path);
		if (f != null) {
			cir.setReturnValue(f);
		}
	}

	@Inject(method = "clear", at = @At("HEAD"))
	private void mcrl$clearRl(CallbackInfo ci) {
		RlSoundLibrary.game().clear();
	}
}
