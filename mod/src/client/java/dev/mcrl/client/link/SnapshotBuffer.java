package dev.mcrl.client.link;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import dev.mcrl.math.Pose;
import dev.mcrl.net.NetTuning;
import dev.mcrl.net.Snapshot;
import dev.mcrl.sim.SimClock;
import dev.mcrl.sim.SimFrame;

final class SnapshotBuffer {
	private static final int EXTRAPOLATE_TICKS = NetTuning.INTERP_DELAY_TICKS;
	private static final int STALE_TICKS = NetTuning.CAR_STALE_TICKS;
	private static final int SAMPLES = 32;

	private interface Sample {
		long tick();
	}

	private interface BodySample extends Sample {
		Pose pose();

		Pose carriedTo(double renderTick);
	}

	private record CarSample(long tick, Pose pose, Snapshot.Car car) implements BodySample {
		@Override
		public Pose carriedTo(double renderTick) {
			Snapshot.CarHead h = car.head();
			return carried(pose, tick, renderTick, h.vx(), h.vy(), h.vz(), h.wx(), h.wy(), h.wz());
		}
	}

	private record BallSample(long tick, Pose pose, Snapshot.Ball ball) implements BodySample {
		@Override
		public Pose carriedTo(double renderTick) {
			Snapshot.BallHead h = ball.head();
			return carried(pose, tick, renderTick, h.vx(), h.vy(), h.vz(), h.wx(), h.wy(), h.wz());
		}
	}

	private record FlagSample(long tick, int flags) implements Sample {
	}

	private final Map<Integer, ArrayDeque<CarSample>> cars = new LinkedHashMap<>();
	private final ArrayDeque<BallSample> ball = new ArrayDeque<>();
	private final ArrayDeque<FlagSample> flags = new ArrayDeque<>();
	private long newest = Long.MIN_VALUE;

	long newestTick() {
		return newest;
	}

	private void clear() {
		cars.clear();
		ball.clear();
		flags.clear();
		newest = Long.MIN_VALUE;
	}

	void add(Snapshot s) {
		if (newest != Long.MIN_VALUE && s.tick() < newest) {
			clear();
		}
		newest = s.tick();
		int[] o = s.origin();
		for (Snapshot.Car c : s.cars()) {
			push(cars.computeIfAbsent(c.id(), k -> new ArrayDeque<>()), new CarSample(s.tick(), c.head().pose(o), c));
		}
		Snapshot.Ball b = s.ball();
		if (b != null) {
			push(ball, new BallSample(s.tick(), b.head().pose(o), b));
		}
		push(flags, new FlagSample(s.tick(), s.frameFlags()));
		for (Iterator<ArrayDeque<CarSample>> it = cars.values().iterator(); it.hasNext();) {
			if (it.next().getLast().tick() < newest - STALE_TICKS) {
				it.remove();
			}
		}
		if (!ball.isEmpty() && ball.getLast().tick() < newest - STALE_TICKS) {
			ball.clear();
		}
	}

	private static <T extends Sample> void push(ArrayDeque<T> q, T sample) {
		if (!q.isEmpty() && q.getLast().tick() == sample.tick()) {
			q.removeLast();
		}
		q.addLast(sample);
		while (q.size() > SAMPLES) {
			q.removeFirst();
		}
	}

	SimFrame frameAt(double renderTick) {
		if (newest == Long.MIN_VALUE) {
			return SimFrame.EMPTY;
		}
		List<SimFrame.CarSnap> out = new ArrayList<>(cars.size());
		for (ArrayDeque<CarSample> track : cars.values()) {
			out.add(at(track, renderTick, (c, p) -> c.car().toCarSnap(p, p)));
		}
		SimFrame.BallSnap b = ball.isEmpty() ? null : at(ball, renderTick, (c, p) -> c.ball().toBallSnap(p, p));
		int f = flags.getFirst().flags();
		for (FlagSample s : flags) {
			if (s.tick() <= renderTick) {
				f = s.flags();
			}
		}
		long t = (long) Math.floor(renderTick);
		return new SimFrame(t, (float) (renderTick - t), f, out, b, 0);
	}

	private static <S extends BodySample, T> T at(ArrayDeque<S> track, double rt, BiFunction<S, Pose, T> snap) {
		S a = null, n = null;
		for (S s : track) {
			if (s.tick() <= rt) {
				a = s;
			} else {
				n = s;
				break;
			}
		}
		if (a == null || n == null) {
			S only = a == null ? track.getFirst() : a;
			return snap.apply(only, only.carriedTo(rt));
		}
		double u = (rt - a.tick()) / (double) (n.tick() - a.tick());
		Pose p = a.pose().lerp(n.pose(), (float) u);
		return snap.apply(u < 0.5 ? a : n, p);
	}

	private static Pose carried(Pose p, long tick, double rt, double vx, double vy, double vz, double wx, double wy,
			double wz) {
		double ticks = Math.max(-EXTRAPOLATE_TICKS, Math.min(rt - tick, EXTRAPOLATE_TICKS));
		return ticks == 0 ? p : p.advanced(vx, vy, vz, wx, wy, wz, ticks / SimClock.TICK_RATE);
	}
}
