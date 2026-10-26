package dev.mcrl.client.camera;

import static dev.mcrl.client.camera.RlCamConstants.MOUSE_DEG_PER_UNIT;
import static dev.mcrl.client.camera.RlCamConstants.MOUSE_HOLD;
import static dev.mcrl.client.camera.RlCamConstants.SWIVEL_DIE_RATE;
import static dev.mcrl.client.camera.RlCamConstants.SWIVEL_PITCH_MAX;
import static dev.mcrl.client.camera.RlCamConstants.SWIVEL_PITCH_MIN;
import static dev.mcrl.client.camera.RlCamConstants.SWIVEL_SPEED_REF;
import static dev.mcrl.client.camera.RlCamConstants.SWIVEL_YAW_FAST;
import static dev.mcrl.client.camera.RlCamConstants.SWIVEL_YAW_SLOW;
import static dev.mcrl.client.camera.RlCamConstants.UNITS_PER_DEG;
import static dev.mcrl.client.camera.UeMath.clamp;
import static dev.mcrl.client.camera.UeMath.lerp;

public final class Swivel {
	private double pitch, yaw;
	private double stickX, stickY;
	private double mouseHold;
	private double yawMax = SWIVEL_YAW_SLOW;
	private boolean invert;

	public void stick(double x, double y) {
		stickX = x;
		stickY = y;
	}

	public void mouse(double dx, double dy) {
		if (dx == 0 && dy == 0) {
			return;
		}
		double k = MOUSE_DEG_PER_UNIT * UNITS_PER_DEG;
		yaw = clamp(yaw + dx * k, -yawMax, yawMax);
		pitch = clamp(pitch - (invert ? -1 : 1) * dy * k, SWIVEL_PITCH_MIN, SWIVEL_PITCH_MAX);
		mouseHold = MOUSE_HOLD;
	}

	public Rot update(double speed, CameraSettings s, double dt) {
		invert = s.invertSwivel();
		yawMax = lerp(SWIVEL_YAW_SLOW, SWIVEL_YAW_FAST, clamp(speed / SWIVEL_SPEED_REF, 0, 1));
		double wantPitch, wantYaw;
		if (mouseHold > 1e-9) {
			wantPitch = pitch;
			wantYaw = clamp(yaw, -yawMax, yawMax);
			mouseHold -= dt;
		} else {
			double y = invert ? -stickY : stickY;
			wantPitch = y >= 0 ? y * SWIVEL_PITCH_MAX : -y * SWIVEL_PITCH_MIN;
			wantYaw = stickX * yawMax;
		}
		pitch = approach(pitch, wantPitch, s.swivelSpeed(), dt);
		yaw = approach(yaw, wantYaw, s.swivelSpeed(), dt);
		return new Rot(pitch, yaw, 0);
	}

	public boolean freeLooking() {
		return Math.abs(pitch) + Math.abs(yaw) > 1;
	}

	public void reset() {
		pitch = 0;
		yaw = 0;
		stickX = 0;
		stickY = 0;
		mouseHold = 0;
		yawMax = SWIVEL_YAW_SLOW;
	}

	private static double approach(double current, double desired, double speed, double dt) {
		double rate = Math.abs(desired) < Math.abs(current) ? speed * SWIVEL_DIE_RATE : speed;
		return lerp(current, desired, Math.min(rate * dt, 1));
	}
}
