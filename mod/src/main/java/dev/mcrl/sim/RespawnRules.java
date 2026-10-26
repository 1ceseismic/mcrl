package dev.mcrl.sim;

import org.jspecify.annotations.Nullable;

import dev.mcrl.math.Pose;
import dev.mcrl.natives.McrlNative;

public final class RespawnRules {
	public static final int VOID_MARGIN = 16;
	public static final int DEMO_VOID_MARGIN = 4;
	// RocketSim's demo respawn time, 3 s.
	public static final int RESPAWN_TICKS = 360;
	public static final int SAFE_POSE_EVERY_TICKS = 30;
	public static final int BALL_PARK_RESPAWN_TICKS = 3600;
	public static final int BALL_LEASH_BLOCKS = 512;
	public static final int BALL_RESPAWN_AHEAD = 6;
	public static final int BALL_RESPAWN_UP = 4;
	// -1 UU/s, so RocketSim does not sleep it.
	public static final double BALL_RESPAWN_VY = -0.02;
	public static final double SAFE_UP_DOT = 0.7;
	public static final int ALL_WHEELS = 0b1111;

	private RespawnRules() {
	}

	public static boolean inVoid(Pose p, int minY) {
		return p.y() < minY - VOID_MARGIN;
	}

	public static boolean carDemoVoid(Pose p, int minY) {
		return p.y() < minY - DEMO_VOID_MARGIN;
	}

	public static Pose respawnPose(@Nullable Pose safe, Pose at) {
		return safe != null ? safe : Pose.upright(at.x(), at.y(), at.z(), at.yawDegrees());
	}

	public static boolean carSafe(CarState s) {
		return (s.flags() & McrlNative.CAR_ON_GROUND) != 0 && s.wheelContact() == ALL_WHEELS
				&& s.pose().up().y >= SAFE_UP_DOT;
	}

	public static boolean ballSafe(Pose p, boolean parked, int minY) {
		return !parked && p.y() > minY;
	}

	public static boolean ballMustRespawn(Pose ball, int parkedTicks, @Nullable Pose anchor, int minY) {
		if (inVoid(ball, minY) || parkedTicks > BALL_PARK_RESPAWN_TICKS) {
			return true;
		}
		if (anchor == null || inVoid(anchor, minY)) {
			return false;
		}
		double dx = ball.x() - anchor.x(), dy = ball.y() - anchor.y(), dz = ball.z() - anchor.z();
		return dx * dx + dy * dy + dz * dz > (double) BALL_LEASH_BLOCKS * BALL_LEASH_BLOCKS;
	}

	public static @Nullable BallState ballRespawn(@Nullable Pose anchor, @Nullable Pose ballSafe, int minY) {
		Pose p = ballSafe;
		if (anchor != null && !inVoid(anchor, minY)) {
			double yaw = Math.toRadians(anchor.yawDegrees());
			double fx = -Math.sin(yaw), fz = Math.cos(yaw);
			p = new Pose(anchor.x() + BALL_RESPAWN_AHEAD * fx, anchor.y() + BALL_RESPAWN_UP,
					anchor.z() + BALL_RESPAWN_AHEAD * fz, 1f, 0f, 0f, 0f);
		}
		return p == null ? null : new BallState(p, 0, BALL_RESPAWN_VY, 0, 0, 0, 0);
	}

	public static @Nullable Pose initialSafe(Pose p, int minY) {
		return inVoid(p, minY) ? null : p;
	}

	public static @Nullable Pose initialCarSafe(Pose p, int minY) {
		return carDemoVoid(p, minY) ? null : p;
	}

	public static Pose outOfDemoVoid(Pose p, int minY) {
		if (!carDemoVoid(p, minY)) {
			return p;
		}
		return new Pose(p.x(), minY - DEMO_VOID_MARGIN, p.z(), p.qw(), p.qx(), p.qy(), p.qz());
	}
}
