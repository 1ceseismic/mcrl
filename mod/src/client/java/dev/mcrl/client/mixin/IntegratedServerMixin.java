package dev.mcrl.client.mixin;

import java.util.function.BooleanSupplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;

import dev.mcrl.sim.PauseSource;
import dev.mcrl.sim.SimClock;
import dev.mcrl.sim.SimHost;
import dev.mcrl.sim.SimHosts;

@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin {
	// the 3-argument publishServer delegates to this one
	@Inject(method = "publishServer(Lnet/minecraft/server/MinecraftServer$MultiplayerScope;I)Z", at = @At("RETURN"))
	private void mcrl$realtimeOnLan(MinecraftServer.MultiplayerScope scope, int port,
			CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ()) {
			SimHosts.setClockAll(SimClock.selectedMode(false, true));
		}
	}

	@Inject(method = "unpublishServer()Z", at = @At("RETURN"))
	private void mcrl$pacedAfterLan(CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ()) {
			SimHosts.setClockAll(SimClock.selectedMode(false, false));
		}
	}

	// tick events stop while the server is paused
	@Inject(method = "tickServer(Ljava/util/function/BooleanSupplier;)V", at = @At("RETURN"))
	private void mcrl$pauseSimHosts(BooleanSupplier haveTime, CallbackInfo ci) {
		boolean paused = ((IntegratedServer) (Object) this).isPaused();
		for (SimHost host : SimHosts.all()) {
			host.setPaused(PauseSource.SERVER, paused);
		}
	}
}
