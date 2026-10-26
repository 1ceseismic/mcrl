package dev.mcrl.client.camera;

import static dev.mcrl.client.camera.RlCamConstants.BLEND_EXPONENT;
import static dev.mcrl.client.camera.RlCamConstants.BLEND_TIME_MAX;

import net.minecraft.world.phys.Vec3;

public final class CamBlend {
	private static final double END_EPS = 1e-9;
	private Vec3 focus = Vec3.ZERO;
	private Rot rot = Rot.ZERO;
	private double distance, fov;
	private double blendTime, remaining;
	private boolean switchFrame;

	public static double blendTime(float transitionSpeed) {
		return UeMath.lerp(BLEND_TIME_MAX, 0, UeMath.clamp(transitionSpeed - 1, 0, 1));
	}

	public void start(Pov old, Pov fresh, double blendTime) {
		this.blendTime = blendTime;
		remaining = Math.max(0, blendTime);
		focus = old.focus().subtract(fresh.focus());
		rot = old.rot().minus(fresh.rot()).normalized();
		distance = old.distance() - fresh.distance();
		fov = old.fov() - fresh.fov();
		switchFrame = true;
	}

	public Pov apply(Pov fresh, double dt) {
		if (remaining <= 0) {
			return fresh;
		}
		if (switchFrame) {
			switchFrame = false;
		} else {
			remaining -= dt;
			if (remaining <= END_EPS) {
				remaining = 0;
				return fresh;
			}
		}
		double w = Math.pow(remaining / blendTime, BLEND_EXPONENT);
		return new Pov(fresh.focus().add(focus.scale(w)), fresh.rot().plus(rot.scale(w)).normalized(),
				fresh.distance() + distance * w, fresh.fov() + fov * w);
	}

	public void reset() {
		remaining = 0;
		switchFrame = false;
	}
}
