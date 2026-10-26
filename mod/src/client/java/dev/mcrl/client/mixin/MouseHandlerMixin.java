package dev.mcrl.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;

import dev.mcrl.client.camera.CameraHost;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@WrapOperation(method = "turnPlayer(D)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
	private void mcrl$swivel(LocalPlayer player, double dx, double dy, Operation<Void> original) {
		if (CameraHost.chasing(Minecraft.getInstance())) {
			CameraHost.mouse(dx, dy);
		} else {
			original.call(player, dx, dy);
		}
	}
}
