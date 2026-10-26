package dev.mcrl.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.mcrl.entity.CarChoices;
import dev.mcrl.test.Taps;

@Mixin(CarChoices.class)
public abstract class CarChoicesScanMixin {
	@Inject(method = "carsOf", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/MinecraftServer;getAllLevels()Ljava/lang/Iterable;", ordinal = 1))
	private static void mcrlTest$countFullScan(CallbackInfoReturnable<?> cir) {
		Taps.carScans++;
	}
}
