package dev.mcrl.sim;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.MemorySegment;

import dev.mcrl.math.Pose;
import dev.mcrl.natives.Layouts;

public record BallState(Pose pose, double vx, double vy, double vz, double wx, double wy, double wz, int bounces,
		float bounceSpeed) {
	public BallState(Pose pose, double vx, double vy, double vz, double wx, double wy, double wz) {
		this(pose, vx, vy, vz, wx, wy, wz, 0, 0f);
	}

	public boolean isFinite() {
		return pose.isFinite() && Double.isFinite(vx) && Double.isFinite(vy) && Double.isFinite(vz)
				&& Double.isFinite(wx) && Double.isFinite(wy) && Double.isFinite(wz);
	}

	public void writeTo(MemorySegment s) {
		Layouts.writePose(s, pose);
		Layouts.writeVec(s, Layouts.BODY_VEL, vx, vy, vz);
		Layouts.writeVec(s, Layouts.BODY_ANGVEL, wx, wy, wz);
		s.set(JAVA_INT, Layouts.BALL_BOUNCES, bounces);
		s.set(JAVA_FLOAT, Layouts.BALL_BOUNCE_SPEED, bounceSpeed);
	}

	public static BallState readFrom(MemorySegment s) {
		return new BallState(Layouts.readPose(s),
				s.get(JAVA_DOUBLE, 40), s.get(JAVA_DOUBLE, 48), s.get(JAVA_DOUBLE, 56),
				s.get(JAVA_DOUBLE, 64), s.get(JAVA_DOUBLE, 72), s.get(JAVA_DOUBLE, 80),
				s.get(JAVA_INT, Layouts.BALL_BOUNCES), s.get(JAVA_FLOAT, Layouts.BALL_BOUNCE_SPEED));
	}
}
