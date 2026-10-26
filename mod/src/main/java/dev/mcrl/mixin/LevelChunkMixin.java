package dev.mcrl.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import dev.mcrl.terrain.TerrainEdits;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
	@Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)"
			+ "Lnet/minecraft/world/level/block/state/BlockState;", at = @At("RETURN"))
	private void mcrl$blockChanged(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
		// Null: nothing changed.
		if (cir.getReturnValue() == null) {
			return;
		}
		Level level = ((LevelChunk) (Object) this).getLevel();
		if (level instanceof ServerLevel server) {
			TerrainEdits.onBlockChanged(server, pos);
		} else if (level.isClientSide()) {
			TerrainEdits.onClientBlockChanged(pos);
		}
	}
}
