package dev.mcrl.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import dev.mcrl.entity.BallEntity;
import dev.mcrl.entity.CarEntity;
import dev.mcrl.match.StadiumConst;

@Mixin(LevelExtractor.class)
public abstract class StadiumBodiesVisibleMixin {
	// stadium sections are empty void, never compiled at range
	@WrapOperation(method = "isEntityVisible", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;isSectionCompiledAndVisible(Lnet/minecraft/core/BlockPos;J)Z"))
	private boolean mcrl$stadiumBodies(LevelRenderer renderer, BlockPos pos, long fadeStart, Operation<Boolean> original,
			@Local(argsOnly = true) Entity entity) {
		if ((entity instanceof CarEntity || entity instanceof BallEntity)
				&& entity.level().dimension().equals(StadiumConst.KEY)) {
			return true;
		}
		return original.call(renderer, pos, fadeStart);
	}
}
