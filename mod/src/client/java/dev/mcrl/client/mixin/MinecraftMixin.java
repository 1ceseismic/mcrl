package dev.mcrl.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

import dev.mcrl.client.camera.CameraHost;
import dev.mcrl.client.link.LocalSimLink;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "startAttack()Z", at = @At("HEAD"), cancellable = true)
	private void mcrl$noAttackWhileDriving(CallbackInfoReturnable<Boolean> cir) {
		if (LocalSimLink.ridingCar((Minecraft) (Object) this)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "startUseItem()V", at = @At("HEAD"), cancellable = true)
	private void mcrl$noUseWhileDriving(CallbackInfo ci) {
		if (LocalSimLink.ridingCar((Minecraft) (Object) this)) {
			ci.cancel();
		}
	}

	@Inject(method = "continueAttack(Z)V", at = @At("HEAD"), cancellable = true)
	private void mcrl$noContinuedAttackWhileDriving(boolean down, CallbackInfo ci) {
		if (LocalSimLink.ridingCar((Minecraft) (Object) this)) {
			ci.cancel();
		}
	}

	@Inject(method = "handleKeybinds()V", at = @At("HEAD"))
	private void mcrl$drainClashingKeysWhileDriving(CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;
		Options o = mc.options;
		if (LocalSimLink.ridingCar(mc)) {
			drain(o.keyDrop);
			drain(o.keyInventory);
			drain(o.keySwapOffhand);
			drain(o.keyQuickActions);
		}
		if (CameraHost.view(mc) != null) {
			drain(o.keyTogglePerspective);
		}
	}

	private static void drain(KeyMapping k) {
		while (k.consumeClick()) {
		}
	}
}
