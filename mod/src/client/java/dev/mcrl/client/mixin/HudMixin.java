package dev.mcrl.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;

import dev.mcrl.client.camera.CameraHost;

@Mixin(Hud.class)
public abstract class HudMixin {
	@Inject(method = "extractCrosshair(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
			at = @At("HEAD"), cancellable = true)
	private void mcrl$noCrosshairInChase(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
		if (CameraHost.chasing(Minecraft.getInstance())) {
			ci.cancel();
		}
	}
}
