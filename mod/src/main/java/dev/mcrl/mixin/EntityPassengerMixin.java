package dev.mcrl.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.entity.CarEntity;

@Mixin(Entity.class)
public abstract class EntityPassengerMixin {
	@Inject(method = "isInWall()Z", at = @At("HEAD"), cancellable = true)
	private void mcrl$notInWallInCar(CallbackInfoReturnable<Boolean> cir) {
		if (CarEntity.carriesDriver((Entity) (Object) this)) {
			cir.setReturnValue(false);
		}
	}

	// The public entry: pistons call it from outside Entity.
	@Inject(method = "applyEffectsFromBlocks(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)V",
			at = @At("HEAD"), cancellable = true)
	private void mcrl$noBlockEffectsInCar(Vec3 from, Vec3 to, CallbackInfo ci) {
		if (CarEntity.carriesDriver((Entity) (Object) this)) {
			ci.cancel();
		}
	}

	// Every other block-effect path ends here.
	@Inject(method = "applyEffectsFromBlocks(Ljava/util/List;)V", at = @At("HEAD"), cancellable = true)
	private void mcrl$noBlockEffectsAlongMovementsInCar(List<?> movements, CallbackInfo ci) {
		if (CarEntity.carriesDriver((Entity) (Object) this)) {
			ci.cancel();
		}
	}
}
