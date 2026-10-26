package dev.mcrl.client.camera;

import static dev.mcrl.client.camera.RlCamConstants.AIR_RATE_FAST;
import static dev.mcrl.client.camera.RlCamConstants.AIR_RATE_SLOW;
import static dev.mcrl.client.camera.RlCamConstants.AIR_VELOCITY_YAW_SPEED;
import static dev.mcrl.client.camera.RlCamConstants.DISTANCE_MIN_ADD;
import static dev.mcrl.client.camera.RlCamConstants.DISTANCE_RATE;
import static dev.mcrl.client.camera.RlCamConstants.DISTANCE_SPEED_SCALE;
import static dev.mcrl.client.camera.RlCamConstants.FOCUS_MAX_DISTANCE;
import static dev.mcrl.client.camera.RlCamConstants.FOCUS_OFFSET_RATE;
import static dev.mcrl.client.camera.RlCamConstants.FOCUS_RATE;
import static dev.mcrl.client.camera.RlCamConstants.FOV_RATE;
import static dev.mcrl.client.camera.RlCamConstants.GROUND_NORMAL_RATE;
import static dev.mcrl.client.camera.RlCamConstants.GROUND_ROT_RATE;
import static dev.mcrl.client.camera.RlCamConstants.MAX_LINEAR_SPEED;
import static dev.mcrl.client.camera.RlCamConstants.MAX_SPEED_FOV;
import static dev.mcrl.client.camera.RlCamConstants.PITCH_BLEND_RANGE;
import static dev.mcrl.client.camera.RlCamConstants.ROLL_SCALE;
import static dev.mcrl.client.camera.RlCamConstants.STIFFNESS_RATE_SCALE;
import static dev.mcrl.client.camera.RlCamConstants.SUPERSONIC_FOV;
import static dev.mcrl.client.camera.RlCamConstants.SUPERSONIC_FOV_RATE;
import static dev.mcrl.client.camera.RlCamConstants.TO_AIR_RATE;
import static dev.mcrl.client.camera.RlCamConstants.TO_GROUND_RATE;
import static dev.mcrl.client.camera.RlCamConstants.UNITS_PER_DEG;
import static dev.mcrl.client.camera.RlCamConstants.WALL_ROT_RATE;
import static dev.mcrl.client.camera.UeMath.clamp;
import static dev.mcrl.client.camera.UeMath.lerp;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.phys.Vec3;

public final class CarCamState {
	private boolean first = true, wasGround;
	private double blend;
	private Vec3 focusOffset = Vec3.ZERO, lagged = Vec3.ZERO, lastLocation = Vec3.ZERO;
	private Rot lastRot = Rot.ZERO;
	private double distance, fov;
	private @Nullable Pov last;
	private boolean groundFirst = true;
	private Vec3 groundNormal = Vec3.ZERO;
	private @Nullable Vec3 carNormal;
	private Rot groundRot = Rot.ZERO;
	private @Nullable Rot groundTarget;
	private boolean airFirst = true;
	private Rot airRot = Rot.ZERO;

	public void reset() {
		first = true;
		groundFirst = true;
		airFirst = true;
		carNormal = null;
		groundTarget = null;
		last = null;
	}

	public Pov update(CamInput in, CameraSettings s, boolean freeLooking, double dt) {
		if (!first && dt <= 0 && last != null) {
			return last;
		}
		double stiffness = s.stiffness();
		int pitchOff = pitchOffset(s);

		boolean ground = in.onGround() && !in.jumping();
		boolean landed = !first && ground && !wasGround;
		wasGround = ground;
		blend = first ? (ground ? 1 : 0) : clamp(blend + (ground ? TO_GROUND_RATE : -TO_AIR_RATE) * dt, 0, 1);

		Vec3 target = blend >= 1 ? in.carUp().scale(s.height()) : new Vec3(0, 0, s.height());
		focusOffset = first ? target : UeMath.vsmooth(focusOffset, target, FOCUS_OFFSET_RATE, dt);
		Vec3 focus = in.carPos().add(focusOffset);
		lagged = first ? focus
				: UeMath.clampWithin(UeMath.vsmooth(lagged, focus, FOCUS_RATE, dt), focus, FOCUS_MAX_DISTANCE);
		Vec3 f = first || freeLooking ? in.carFwd() : lastRot.vector();
		Vec3 out = lerp(lagged.add(f.scale(f.dot(focus.subtract(lagged)))), focus, stiffness);

		if (blend > 0) {
			ground(in, stiffness, pitchOff, landed, dt);
		} else {
			groundFirst = true;
		}
		if (blend < 1) {
			air(in, out, pitchOff, dt);
		} else {
			airFirst = true;
		}
		Rot rot = blend >= 1 ? groundRot : blend <= 0 ? airRot : UeMath.rlerp(airRot, groundRot, blend);

		distance = stepDistance(first, distance, in, s, rot, dt);
		fov = stepFov(first, fov, in, s, dt);

		if (blend > 0 && !freeLooking) {
			rot = rot.withRoll((int) (Rot.of(in.carRight()).pitch() * ROLL_SCALE * blend));
		}
		lastRot = rot;
		lastLocation = out.subtract(rot.vector().scale(distance));
		first = false;
		last = new Pov(out, rot, distance, fov);
		return last;
	}

	private void ground(CamInput in, double stiffness, int pitchOff, boolean landed, double dt) {
		Vec3 n = in.groundNormal() != null ? in.groundNormal() : carNormal != null ? carNormal : in.carUp();
		carNormal = n;
		groundNormal = groundFirst || landed ? n
				: UeMath.normal(UeMath.vsmooth(groundNormal, n, GROUND_NORMAL_RATE, dt));
		Vec3 u = groundNormal;
		Vec3 r = u.cross(in.carFwd()); // unnormalised, as in RL
		Rot newRot;
		if (r.length() < 1e-6) {
			newRot = groundTarget != null ? groundTarget
					: new Rot(pitchOff, Rot.of(u.dot(in.carFwd()) < 0 ? in.carUp() : in.carUp().reverse()).yaw(), 0);
		} else {
			Vec3 f = r.cross(u);
			double carPitch = Rot.of(f).pitch();
			double pitchBlend = Math.min(Math.abs(carPitch) / PITCH_BLEND_RANGE, 1);
			newRot = Rot.of(lerp(f, carPitch > 0 ? u.reverse() : u, pitchBlend));
			newRot = newRot.withPitch(newRot.pitch() + Math.min(pitchOff, 16384 - newRot.pitch()));
		}
		groundTarget = newRot;
		if (groundFirst) {
			groundRot = newRot;
		} else {
			double k = lerp(1, STIFFNESS_RATE_SCALE, stiffness);
			Rot onGround = stiffness < 1 ? UeMath.rsmooth(groundRot, newRot, GROUND_ROT_RATE * k, dt) : newRot;
			Rot onWall = UeMath.rsmooth(groundRot, newRot, WALL_ROT_RATE * k, dt);
			groundRot = UeMath.rlerp(onGround, onWall, 1 - Math.abs(u.z));
		}
		groundFirst = false;
	}

	private void air(CamInput in, Vec3 focus, int pitchOff, double dt) {
		Vec3 v = in.velocity();
		double speed2d = Math.sqrt(v.x * v.x + v.y * v.y);
		if (airFirst) {
			double a = Math.min(speed2d / AIR_VELOCITY_YAW_SPEED, 1);
			airRot = new Rot(pitchOff, UeMath.rlerp(Rot.of(in.carFwd()), Rot.of(v), a).yaw(), 0);
		} else {
			Rot desired = Rot.of(focus.subtract(lastLocation));
			double rate = lerp(AIR_RATE_SLOW, AIR_RATE_FAST, Math.min(v.length() / MAX_LINEAR_SPEED, 1));
			// RL's per-frame min(rate/60, 1), frame-rate independent
			airRot = UeMath.rlerp(airRot, desired, 1 - Math.pow(1 - Math.min(rate / 60, 1), 60 * dt));
			if (v.dot(airRot.vector()) < 0) {
				airRot = airRot.withPitch(airRot.pitch() * Math.pow(1 - Math.min(speed2d / MAX_LINEAR_SPEED, 1), 60 * dt));
			}
		}
		airFirst = false;
	}

	static int pitchOffset(CameraSettings s) {
		return (int) (s.angle() * UNITS_PER_DEG);
	}

	static double stepDistance(boolean first, double distance, CamInput in, CameraSettings s, Rot rot, double dt) {
		double target = s.distance()
				+ Math.max(in.velocity().dot(rot.vector()) * DISTANCE_SPEED_SCALE * (1 - s.stiffness()), DISTANCE_MIN_ADD);
		return first ? target : UeMath.smooth(distance, target, DISTANCE_RATE, dt);
	}

	static double stepFov(boolean first, double fov, CamInput in, CameraSettings s, double dt) {
		double target = in.supersonic() ? s.fov() + SUPERSONIC_FOV
				: s.fov() + MAX_SPEED_FOV * Math.min(in.velocity().length() / MAX_LINEAR_SPEED, 1);
		if (first) {
			return target;
		}
		double step = (in.supersonic() ? SUPERSONIC_FOV_RATE : FOV_RATE) * dt;
		return fov + clamp(target - fov, -step, step);
	}
}
