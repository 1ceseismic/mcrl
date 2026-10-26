package dev.mcrl.sim;

import java.util.List;

import org.jspecify.annotations.Nullable;

import dev.mcrl.math.Pose;

public record SimFrame(long tick, float alpha, int flags, List<CarSnap> cars, @Nullable BallSnap ball, long paceSeq,
		long paceNanos, float rate, @Nullable SoccarState soccar) {
	public static final int FROZEN = 1;
	public static final int PAUSED = 2;
	public static final int STALLED = 4;
	public static final int FAULT = 8;
	public static final int STEPUP = 32;
	public static final int BALL_HELD = 64;

	public static final SimFrame EMPTY = new SimFrame(0, 0f, 0, List.of(), null, 0);

	public SimFrame {
		cars = List.copyOf(cars);
	}

	public SimFrame(long tick, float alpha, int flags, List<CarSnap> cars, @Nullable BallSnap ball, long paceSeq) {
		this(tick, alpha, flags, cars, ball, paceSeq, 0, 1f);
	}

	public SimFrame(long tick, float alpha, int flags, List<CarSnap> cars, @Nullable BallSnap ball, long paceSeq,
			long paceNanos, float rate) {
		this(tick, alpha, flags, cars, ball, paceSeq, paceNanos, rate, null);
	}

	public SimFrame withFlags(int newFlags) {
		return new SimFrame(tick, alpha, newFlags, cars, ball, paceSeq, paceNanos, rate, soccar);
	}

	public boolean anyCarHeld() {
		for (CarSnap c : cars) {
			if (c.held()) {
				return true;
			}
		}
		return false;
	}

	public @Nullable CarSnap car(int carId) {
		for (CarSnap c : cars) {
			if (c.carId() == carId) {
				return c;
			}
		}
		return null;
	}

	public record CarSnap(int carId, Pose prev, Pose cur, double vx, double vy, double vz, double wx, double wy,
			double wz, float boost, int carFlags, float[] wheelLocal, float steerAngle, int wheelContact, int ballHits,
			float ballHitSpeed, float stepDz, CarFx fx, int team, int preset, Controls applied, @Nullable Pose respawnAt) {
		public CarSnap {
			if (wheelLocal.length != 12) {
				throw new IllegalArgumentException("wheelLocal must have 12 floats");
			}
			wheelLocal = wheelLocal.clone();
		}

		public CarSnap(int carId, Pose prev, Pose cur, double vx, double vy, double vz, double wx, double wy, double wz,
				float boost, int carFlags, float[] wheelLocal, float steerAngle, int wheelContact, int ballHits,
				float ballHitSpeed, float stepDz, CarFx fx, int team, int preset, Controls applied) {
			this(carId, prev, cur, vx, vy, vz, wx, wy, wz, boost, carFlags, wheelLocal, steerAngle, wheelContact, ballHits,
					ballHitSpeed, stepDz, fx, team, preset, applied, null);
		}

		public boolean demoed() {
			return (carFlags & CarState.FLAG_DEMOED) != 0;
		}

		public boolean held() {
			return (carFlags & CarState.FLAG_HELD) != 0;
		}

		@Override
		public float[] wheelLocal() {
			return wheelLocal.clone();
		}
	}

	public record BallSnap(Pose prev, Pose cur, double vx, double vy, double vz, double wx, double wy, double wz,
			int bounces, float bounceSpeed, int lastTouchTeam, boolean worldContact, Hit bounce, Wet wet) {
		public BallSnap(Pose prev, Pose cur, double vx, double vy, double vz, double wx, double wy, double wz,
				int bounces, float bounceSpeed, int lastTouchTeam, boolean worldContact, Hit bounce) {
			this(prev, cur, vx, vy, vz, wx, wy, wz, bounces, bounceSpeed, lastTouchTeam, worldContact, bounce, Wet.DRY);
		}
	}
}
