package dev.mcrl.net;

import java.util.List;

import org.jspecify.annotations.Nullable;

import dev.mcrl.math.Pose;
import dev.mcrl.sim.CarFx;
import dev.mcrl.sim.Controls;
import dev.mcrl.sim.Hit;
import dev.mcrl.sim.SimFrame;

public record Snapshot(long tick, int ox, int oy, int oz, int frameFlags, int hostId, float rate, int inputEpoch,
		long ackTick, int slack, List<Car> cars, @Nullable Ball ball, byte @Nullable [] arenaBlob, ArenaBlock arena) {
	public Snapshot {
		cars = List.copyOf(cars);
	}

	public boolean paused() {
		return (frameFlags & SimFrame.PAUSED) != 0;
	}

	public int[] origin() {
		return new int[] {ox, oy, oz};
	}

	public @Nullable Car car(int id) {
		for (Car c : cars) {
			if (c.id() == id) {
				return c;
			}
		}
		return null;
	}

	public record Car(int id, int team, int preset, Controls applied, CarHead head, byte @Nullable [] blob) {
		public SimFrame.CarSnap toCarSnap(Pose prev, Pose cur) {
			CarHead h = head;
			return new SimFrame.CarSnap(id, prev, cur, h.vx(), h.vy(), h.vz(), h.wx(), h.wy(), h.wz(), h.boost(),
					h.flags(), h.wheelLocal(), h.steer(), h.wheelContact(), 0, 0f, 0f, CarFx.EMPTY, team, preset,
					applied);
		}
	}

	public record CarHead(float px, float py, float pz, float qw, float qx, float qy, float qz, float vx, float vy,
			float vz, float wx, float wy, float wz, int boost, int flags, int wheelContact, float steer,
			float[] wheelLocal) {
		public CarHead {
			if (wheelLocal.length != 12) {
				throw new IllegalArgumentException("wheelLocal must have 12 floats");
			}
			wheelLocal = wheelLocal.clone();
		}

		@Override
		public float[] wheelLocal() {
			return wheelLocal.clone();
		}

		public Pose pose(int[] origin) {
			return new Pose(origin[0] + (double) px, origin[1] + (double) py, origin[2] + (double) pz, qw, qx, qy, qz);
		}
	}

	public record Ball(BallHead head, byte @Nullable [] blob) {
		public SimFrame.BallSnap toBallSnap(Pose prev, Pose cur) {
			BallHead h = head;
			return new SimFrame.BallSnap(prev, cur, h.vx(), h.vy(), h.vz(), h.wx(), h.wy(), h.wz(), h.bounces(),
					h.bounceSpeed(), h.lastTouchTeam(), h.worldContact(), Hit.NONE);
		}
	}

	public record BallHead(float px, float py, float pz, float qw, float qx, float qy, float qz, float vx, float vy,
			float vz, float wx, float wy, float wz, int bounces, float bounceSpeed, int lastTouchTeam,
			boolean worldContact) {
		public Pose pose(int[] origin) {
			return new Pose(origin[0] + (double) px, origin[1] + (double) py, origin[2] + (double) pz, qw, qx, qy, qz);
		}
	}
}
