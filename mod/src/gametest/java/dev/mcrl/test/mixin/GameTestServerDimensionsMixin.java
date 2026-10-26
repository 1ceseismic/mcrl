package dev.mcrl.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.WorldLoader;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;

@Mixin(GameTestServer.class)
public abstract class GameTestServerDimensionsMixin {
	@WrapOperation(method = "lambda$create$1", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/levelgen/WorldDimensions;bake(Lnet/minecraft/core/Registry;)"
					+ "Lnet/minecraft/world/level/levelgen/WorldDimensions$Complete;"))
	private static WorldDimensions.Complete mcrlTest$datapackDimensions(WorldDimensions dims, Registry<LevelStem> empty,
			Operation<WorldDimensions.Complete> original, @Local(argsOnly = true) WorldLoader.DataLoadContext ctx) {
		return original.call(dims, ctx.datapackDimensions().lookupOrThrow(Registries.LEVEL_STEM));
	}
}
