package dev.mcrl.client.mixin;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.client.camera.CamSpace;
import dev.mcrl.client.camera.CamSpace.Angles;
import dev.mcrl.client.camera.CamSpace.View;
import dev.mcrl.client.camera.CameraHost;

@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	private boolean detached;
	@Shadow
	private Level level;
	@Shadow
	private Entity entity;
	@Shadow
	@Final
	private Quaternionf rotation;
	@Shadow
	@Final
	private Vector3f forwards;
	@Shadow
	@Final
	private Vector3f up;
	@Shadow
	@Final
	private Vector3f left;
	@Shadow
	private int matrixPropertiesDirty;

	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	protected abstract void move(float forward, float up, float right);

	@Shadow
	private float getMaxZoom(float maxZoom) {
		throw new AssertionError("shadowed");
	}

	@Inject(method = "alignWithEntity(F)V", at = @At("TAIL"))
	private void mcrl$rlCamera(float partialTick, CallbackInfo ci) {
		View v = CameraHost.view(Minecraft.getInstance());
		if (v == null) {
			return;
		}
		BlockHitResult hit = level.clip(new ClipContext(v.car(), CamSpace.clipEnd(v.car(), v.focus()),
				ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, entity));
		boolean miss = hit.getType() == HitResult.Type.MISS;
		Vec3 pivot = CamSpace.pivot(v.car(), v.focus(), miss ? null : hit.getLocation(),
				miss ? null : hit.getDirection().getUnitVec3());
		setPosition(pivot);
		mcrl$rotate(v.base());
		move(-(float) CameraHost.zoom(v, getMaxZoom((float) v.distance())), 0, 0);
		move((float) v.shakeFwd(), (float) v.shakeUp(), (float) v.shakeRight());
		mcrl$rotate(v.shaken());
		detached = true;
	}

	private void mcrl$rotate(Angles a) {
		setRotation(a.yRot(), a.xRot());
		rotation.set(CamSpace.cameraRotation(a.yRot(), a.xRot(), a.roll()));
		rotation.transform(0, 0, -1, forwards);
		rotation.transform(0, 1, 0, up);
		rotation.transform(-1, 0, 0, left);
		matrixPropertiesDirty |= 3; // as vanilla's setRotation does
	}

	@Inject(method = "calculateFov(F)F", at = @At("HEAD"), cancellable = true)
	private void mcrl$fov(float partialTick, CallbackInfoReturnable<Float> cir) {
		if (CameraHost.chasing(Minecraft.getInstance())) {
			cir.setReturnValue(CameraHost.verticalFov());
		}
	}
}
