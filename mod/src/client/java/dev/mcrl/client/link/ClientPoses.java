package dev.mcrl.client.link;

import java.util.List;

import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import dev.mcrl.client.render.StepSmoother;
import dev.mcrl.math.Pose;
import dev.mcrl.sim.SimClock;
import dev.mcrl.sim.SimFrame;
import dev.mcrl.sim.SimHost;
import dev.mcrl.sim.TickRing;

public final class ClientPoses {
	private static final int MAX_EXTRAPOLATION_TICKS = 3;

	private static SimFrame frame = SimFrame.EMPTY;
	private static double simSeconds;
	private static final Int2ObjectOpenHashMap<Pose> CARS = new Int2ObjectOpenHashMap<>();
	private static final Int2ObjectOpenHashMap<Pose> RAW = new Int2ObjectOpenHashMap<>();
	private static final Int2ObjectOpenHashMap<SimFrame.CarSnap> SNAPS = new Int2ObjectOpenHashMap<>();
	private static @Nullable Pose ball;
	private static final Int2ObjectOpenHashMap<StepSmoother> SMOOTHERS = new Int2ObjectOpenHashMap<>();
	private static long sourceEpoch;

	private ClientPoses() {
	}

	public static @Nullable Pose car(int entityId) {
		return CARS.get(entityId);
	}

	public static @Nullable Pose carRaw(int entityId) {
		return RAW.get(entityId);
	}

	public static @Nullable Pose ball() {
		return ball;
	}

	public static SimFrame.@Nullable CarSnap carSnap(int id) {
		return SNAPS.get(id);
	}

	public static SimFrame frame() {
		return frame;
	}

	public static double simSeconds() {
		return simSeconds;
	}

	private static double alphaEff(SimFrame f, long frameNanos) {
		boolean stopped = (f.flags() & SimFrame.PAUSED) != 0
				|| (f.flags() & SimFrame.FROZEN) != 0 && !f.anyCarHeld();
		if (f.paceNanos() == 0 || stopped) {
			return f.alpha();
		}
		long late = Math.min(frameNanos - f.paceNanos(), SimClock.MAX_GAP_NANOS);
		double a = f.alpha() + late * (double) f.rate() / SimClock.DT_NANOS;
		return Math.max(0, Math.min(a, 1 + MAX_EXTRAPOLATION_TICKS));
	}

	static void update(SimFrame f, @Nullable SimHost ringHost, long frameNanos, @Nullable CorrectionOffsets corr) {
		double nowSeconds = frameNanos * 1e-9;
		double a = alphaEff(f, frameNanos);
		frame = f;
		simSeconds = (f.tick() + a) / SimClock.TICK_RATE;
		CARS.clear();
		RAW.clear();
		SNAPS.clear();
		for (SimFrame.CarSnap c : f.cars()) {
			Pose prev = c.prev();
			double offset = 0;
			if (ringHost != null) {
				StepSmoother sm = SMOOTHERS.computeIfAbsent(c.carId(), id -> new StepSmoother());
				List<TickRing.Entry> since = ringHost.ringSince(c.carId(), Math.min(sm.lastTick(), f.tick() - 1));
				double vh = Math.hypot(c.vx(), c.vz());
				StepSmoother.Applied applied = sm.applyUpTo(since, f.tick(), ringHost.ringOldestTick(c.carId()),
						nowSeconds, vh);
				offset = applied.offset();
				for (TickRing.Entry e : applied.reset() ? List.<TickRing.Entry>of() : since) {
					if (e.tick() == f.tick() && e.stepDz() != 0f) {
						prev = new Pose(prev.x(), prev.y() + e.stepDz(), prev.z(), prev.qw(), prev.qx(), prev.qy(),
								prev.qz());
					}
				}
			}
			Pose p, raw;
			if (c.held() || c.demoed()) {
				p = raw = c.cur();
			} else if (a <= 1) {
				p = prev.lerp(c.cur(), (float) a);
				raw = c.prev().lerp(c.cur(), (float) a);
			} else {
				p = raw = c.cur().advanced(c.vx(), c.vy(), c.vz(), c.wx(), c.wy(), c.wz(),
						(a - 1) / SimClock.TICK_RATE);
			}
			RAW.put(c.carId(), raw);
			if (offset != 0) {
				p = new Pose(p.x(), p.y() + offset, p.z(), p.qw(), p.qx(), p.qy(), p.qz());
			}
			CorrectionOffsets.Offset o = corr == null ? null : corr.get(c.carId(), frameNanos);
			if (o != null) {
				p = o.apply(p);
			}
			CARS.put(c.carId(), p);
			SNAPS.put(c.carId(), c);
		}
		SMOOTHERS.keySet().removeIf(id -> f.car(id) == null);
		SimFrame.BallSnap b = f.ball();
		ball = b == null ? null
				: a <= 1 ? b.prev().lerp(b.cur(), (float) a)
						: b.cur().advanced(b.vx(), b.vy(), b.vz(), b.wx(), b.wy(), b.wz(),
								(a - 1) / SimClock.TICK_RATE);
		CorrectionOffsets.Offset o = ball == null || corr == null ? null : corr.get(-1, frameNanos);
		if (o != null) {
			ball = o.apply(ball);
		}
	}

	static void newSource() {
		sourceEpoch++;
		SMOOTHERS.clear();
	}

	public static long sourceEpoch() {
		return sourceEpoch;
	}

	static void clear() {
		SMOOTHERS.clear();
		frame = SimFrame.EMPTY;
		simSeconds = 0;
		CARS.clear();
		RAW.clear();
		SNAPS.clear();
		ball = null;
	}
}
