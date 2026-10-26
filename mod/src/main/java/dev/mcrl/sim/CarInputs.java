package dev.mcrl.sim;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import dev.mcrl.net.NetProtocol;
import dev.mcrl.sim.SimHost.InputStats;
import dev.mcrl.sim.SimWorld.CarRec;

final class CarInputs {
	private static final double SLACK_ALPHA = 0.1;

	private sealed interface Input permits Frame, Ring {
	}

	private record Frame(InputFrame frame, int pendingEdges) implements Input {
		Frame consume(int edges) {
			return new Frame(frame, pendingEdges & ~edges);
		}
	}

	static final class Ring implements Input {
		private final long[] tickOf = new long[NetProtocol.INPUT_RING];
		private final Controls[] controls = new Controls[NetProtocol.INPUT_RING];
		private long ranTick;
		private long takenTick;
		private boolean takenHit;
		private Controls last = Controls.NEUTRAL;
		private int misses;
		private long ackTick, late, early;
		private double slack;
		private int epoch = -1;

		private Ring(long tick) {
			ranTick = takenTick = tick;
			Arrays.fill(tickOf, Long.MIN_VALUE);
		}

		private synchronized void submit(long firstTick, int inputEpoch, Controls[] ticks) {
			double d = firstTick - (ranTick + 1);
			slack = inputEpoch == epoch ? slack + SLACK_ALPHA * (d - slack) : d;
			epoch = inputEpoch;
			long closed = Math.max(ranTick, takenTick);
			for (int i = 0; i < ticks.length; i++) {
				long t = firstTick + i;
				if (t <= closed) {
					late++;
				} else if (t > ranTick + NetProtocol.INPUT_RING) {
					early++;
				} else {
					int k = (int) Math.floorMod(t, (long) NetProtocol.INPUT_RING);
					tickOf[k] = t;
					controls[k] = Objects.requireNonNull(ticks[i]);
				}
			}
		}

		private synchronized Controls take(long t) {
			takenTick = t;
			int k = (int) Math.floorMod(t, (long) NetProtocol.INPUT_RING);
			takenHit = tickOf[k] == t;
			if (takenHit) {
				return controls[k];
			}
			return misses + 1 > NetProtocol.HOLD_TICKS ? Controls.NEUTRAL : last;
		}

		private synchronized void ran(long t, Controls applied) {
			ranTick = t;
			if (takenHit) {
				ackTick = t;
				misses = 0;
			} else {
				misses++;
			}
			last = applied;
		}

		synchronized void notRun() {
			takenTick = ranTick;
		}

		private synchronized InputStats stats() {
			int sl = (int) Math.max(-127, Math.min(127, Math.round(slack)));
			return new InputStats(ackTick, sl, epoch, late, early);
		}
	}

	private final ConcurrentHashMap<Integer, Input> byCar = new ConcurrentHashMap<>();

	void submit(int carId, InputFrame f) {
		Objects.requireNonNull(f);
		byCar.compute(carId, (k, old) -> new Frame(f, (old instanceof Frame s ? s.pendingEdges() : 0) | f.edges()));
	}

	void submit(int carId, long firstTick, int epoch, Controls[] ticks, long hostTick) {
		byCar.compute(carId, (k, old) -> {
			Ring r = old instanceof Ring ring ? ring : new Ring(hostTick);
			r.submit(firstTick, epoch, ticks);
			return r;
		});
	}

	InputStats stats(int carId) {
		return byCar.get(carId) instanceof Ring r ? r.stats() : InputStats.NONE;
	}

	void remove(int carId) {
		byCar.remove(carId);
	}

	void hold(int carId, Controls c) {
		int buttons = (c.jump() ? InputFrame.JUMP : 0) | (c.boost() ? InputFrame.BOOST : 0)
				| (c.handbrake() ? InputFrame.HANDBRAKE : 0);
		byCar.put(carId, new Frame(InputFrame.of(0, c.throttle(), c.steer(), c.pitch(), c.yaw(), c.roll(), buttons, 0),
				0));
	}

	void apply(CarRec c, long t) {
		Input in = byCar.get(c.id);
		if (in instanceof Ring r) {
			c.inputRing = r;
			c.appliedEdges = 0;
			c.applied = r.take(t);
		} else {
			Frame s = (Frame) in;
			InputFrame f = s == null ? InputFrame.NEUTRAL : s.frame();
			c.inputRing = null;
			c.appliedEdges = s == null ? 0 : s.pendingEdges();
			c.applied = f.withEdges(c.appliedEdges).toControls(true);
		}
	}

	void ran(CarRec c, long t) {
		if (c.inputRing != null) {
			c.inputRing.ran(t, c.applied);
			return;
		}
		int e = c.appliedEdges;
		if (e != 0) {
			byCar.computeIfPresent(c.id, (k, s) -> s instanceof Frame slot ? slot.consume(e) : s);
			c.appliedEdges = 0;
		}
	}
}
