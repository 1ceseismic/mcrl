package dev.mcrl.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.Entity;

import dev.mcrl.entity.BallEntity;
import dev.mcrl.entity.CarEntity;
import dev.mcrl.match.StadiumConst;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityRangeMixin {
	@Shadow
	@Final
	private Entity entity;

	@Inject(method = "getEffectiveRange()I", at = @At("RETURN"), cancellable = true)
	private void mcrl$stadiumRange(CallbackInfoReturnable<Integer> cir) {
		if ((entity instanceof CarEntity || entity instanceof BallEntity) && cir.getReturnValueI() < StadiumConst.VIEW_BLOCKS
				&& entity.level().dimension().equals(StadiumConst.KEY)) {
			cir.setReturnValue(StadiumConst.VIEW_BLOCKS);
		}
	}
}
