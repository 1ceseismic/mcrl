package dev.mcrl.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import dev.mcrl.match.StadiumConst;

@Mixin(ChunkMap.class)
public abstract class ChunkMapViewMixin {
	@Shadow
	@Final
	private ServerLevel level;

	@Inject(method = "getPlayerViewDistance(Lnet/minecraft/server/level/ServerPlayer;)I", at = @At("RETURN"),
			cancellable = true)
	private void mcrl$stadiumView(ServerPlayer player, CallbackInfoReturnable<Integer> cir) {
		if (cir.getReturnValueI() < StadiumConst.VIEW_CHUNKS && level.dimension().equals(StadiumConst.KEY)) {
			cir.setReturnValue(StadiumConst.VIEW_CHUNKS);
		}
	}
}
