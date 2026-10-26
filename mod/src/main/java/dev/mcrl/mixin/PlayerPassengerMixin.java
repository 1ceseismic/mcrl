package dev.mcrl.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import dev.mcrl.entity.CarEntity;

@Mixin(Player.class)
public abstract class PlayerPassengerMixin {
	@Inject(method = "wantsToStopRiding()Z", at = @At("HEAD"), cancellable = true)
	private void mcrl$staysInCar(CallbackInfoReturnable<Boolean> cir) {
		if (CarEntity.carriesDriver((Entity) (Object) this)) {
			cir.setReturnValue(false);
		}
	}
}
