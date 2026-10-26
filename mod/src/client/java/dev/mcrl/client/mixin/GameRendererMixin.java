package dev.mcrl.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;

import com.mojang.renderpearl.api.textures.GpuTextureView;

import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;

import dev.mcrl.client.camera.CameraHost;
import dev.mcrl.client.link.FrameEvents;
import dev.mcrl.client.link.SimLinks;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "update(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"))
	private void mcrl$simLink(DeltaTracker deltaTracker, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		SimLinks.onFrame(mc);
		FrameEvents.dispatch(mc);
	}

	// a detached camera alone still draws the hand
	@Inject(method = "renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
			+ "Lnet/minecraft/client/renderer/state/level/PlayerRenderState;"
			+ "Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V", at = @At("HEAD"), cancellable = true)
	private void mcrl$noHandInChase(CameraRenderState camera, PlayerRenderState player, GpuTextureView target,
			CallbackInfo ci) {
		if (CameraHost.chasing(Minecraft.getInstance())) {
			ci.cancel();
		}
	}

	// the frozen look ray would outline a stale block
	@Inject(method = "shouldRenderBlockOutline()Z", at = @At("HEAD"), cancellable = true)
	private void mcrl$noBlockOutlineInChase(CallbackInfoReturnable<Boolean> cir) {
		if (CameraHost.chasing(Minecraft.getInstance())) {
			cir.setReturnValue(false);
		}
	}
}
