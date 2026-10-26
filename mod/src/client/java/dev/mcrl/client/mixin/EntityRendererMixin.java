package dev.mcrl.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;

import dev.mcrl.entity.CarEntity;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
	@Inject(method = "shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDDF)Z",
			at = @At("HEAD"), cancellable = true)
	private void mcrl$hideDriver(Entity entity, Frustum frustum, double camX, double camY, double camZ, float partialTick,
			CallbackInfoReturnable<Boolean> cir) {
		if (entity.getVehicle() instanceof CarEntity) {
			cir.setReturnValue(false);
		}
	}
}
