package dev.mcrl.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import dev.mcrl.entity.CarEntity;

@Mixin(LivingEntity.class)
public abstract class LivingEntityPassengerMixin {
	@Inject(method = "canBreatheUnderwater()Z", at = @At("HEAD"), cancellable = true)
	private void mcrl$breathesInCar(CallbackInfoReturnable<Boolean> cir) {
		if (CarEntity.carriesDriver((Entity) (Object) this)) {
			cir.setReturnValue(true);
		}
	}
}
