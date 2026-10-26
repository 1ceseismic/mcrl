package dev.mcrl.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;

import dev.mcrl.match.StadiumConst;

@Mixin(FogRenderer.class)
public abstract class StadiumFogMixin {
	private static final float NO_FOG = 1e6f;

	@Inject(method = "setupFog(Lnet/minecraft/client/Camera;ILnet/minecraft/client/DeltaTracker;F"
			+ "Lnet/minecraft/client/multiplayer/ClientLevel;)Lnet/minecraft/client/renderer/fog/FogData;",
			at = @At("RETURN"))
	private void mcrl$noStadiumFog(Camera camera, int renderDistance, DeltaTracker delta, float darken, ClientLevel level,
			CallbackInfoReturnable<FogData> cir) {
		if (level.dimension().equals(StadiumConst.KEY)) { // fog.glsl takes the larger fog
			FogData f = cir.getReturnValue();
			f.environmentalStart = NO_FOG;
			f.environmentalEnd = NO_FOG;
			f.renderDistanceStart = NO_FOG;
			f.renderDistanceEnd = NO_FOG;
		}
	}
}
