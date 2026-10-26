package dev.mcrl.sim;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.MemorySegment;

import dev.mcrl.math.Pose;
import dev.mcrl.natives.Layouts;

public record CarState(Pose pose, double vx, double vy, double vz, double wx, double wy, double wz,
		float boost, int flags, float[] wheelLocal, float steerAngle, int wheelContact, int ballHits,
		float ballHitSpeed, float stepDz) {
	public static final int FLAG_ON_GROUND = 1, FLAG_SUPERSONIC = 2, FLAG_JUMPED = 4, FLAG_DOUBLE_JUMPED = 8,
			FLAG_FLIPPED = 16, FLAG_DEMOED = 32, FLAG_BOOSTING = 64;
	public static final int FLAG_HELD = 128, FLAG_RESTORED = 256;

	public CarState {
		if (wheelLocal == null) {
			wheelLocal = new float[12];
		} else if (wheelLocal.length != 12) {
			throw new IllegalArgumentException("wheelLocal must have 12 floats");
		}
	}

	public static CarState of(Pose pose, double vx, double vy, double vz, double wx, double wy, double wz) {
		return new CarState(pose, vx, vy, vz, wx, wy, wz, 0f, 0, null, 0f, 0, 0, 0f, 0f);
	}

	public void writeTo(MemorySegment s) {
		Layouts.writePose(s, pose);
		Layouts.writeVec(s, Layouts.BODY_VEL, vx, vy, vz);
		Layouts.writeVec(s, Layouts.BODY_ANGVEL, wx, wy, wz);
		s.set(JAVA_FLOAT, Layouts.CAR_BOOST, boost);
		s.set(JAVA_INT, Layouts.CAR_FLAGS, flags);
		for (int i = 0; i < 12; i++) {
			s.set(JAVA_FLOAT, Layouts.CAR_WHEELS + 4L * i, wheelLocal[i]);
		}
		s.set(JAVA_FLOAT, Layouts.CAR_STEER, steerAngle);
		s.set(JAVA_INT, Layouts.CAR_WHEEL_CONTACT, wheelContact);
		s.set(JAVA_INT, Layouts.CAR_BALL_HITS, ballHits);
		s.set(JAVA_FLOAT, Layouts.CAR_BALL_HIT_SPEED, ballHitSpeed);
		s.set(JAVA_FLOAT, Layouts.CAR_STEP_DZ, stepDz);
	}

	public boolean isFinite() {
		return pose.isFinite() && Double.isFinite(vx) && Double.isFinite(vy) && Double.isFinite(vz)
				&& Double.isFinite(wx) && Double.isFinite(wy) && Double.isFinite(wz);
	}

	public boolean boosting() {
		return (flags & FLAG_BOOSTING) != 0;
	}

	public static CarState readFrom(MemorySegment s) {
		float[] w = new float[12];
		for (int i = 0; i < 12; i++) {
			w[i] = s.get(JAVA_FLOAT, Layouts.CAR_WHEELS + 4L * i);
		}
		return new CarState(Layouts.readPose(s),
				s.get(JAVA_DOUBLE, 40), s.get(JAVA_DOUBLE, 48), s.get(JAVA_DOUBLE, 56),
				s.get(JAVA_DOUBLE, 64), s.get(JAVA_DOUBLE, 72), s.get(JAVA_DOUBLE, 80),
				s.get(JAVA_FLOAT, Layouts.CAR_BOOST), s.get(JAVA_INT, Layouts.CAR_FLAGS), w,
				s.get(JAVA_FLOAT, Layouts.CAR_STEER), s.get(JAVA_INT, Layouts.CAR_WHEEL_CONTACT),
				s.get(JAVA_INT, Layouts.CAR_BALL_HITS), s.get(JAVA_FLOAT, Layouts.CAR_BALL_HIT_SPEED),
				s.get(JAVA_FLOAT, Layouts.CAR_STEP_DZ));
	}
}
