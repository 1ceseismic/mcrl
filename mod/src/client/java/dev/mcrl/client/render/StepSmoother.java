package dev.mcrl.client.render;

import static dev.mcrl.math.Coords.UU_PER_BLOCK;

import java.util.ArrayList;
import java.util.List;

import dev.mcrl.sim.TickRing;

public final class StepSmoother {
	private static final double EASE_DISTANCE = 30.0 / UU_PER_BLOCK;
	private static final double MIN_WINDOW_S = 0.016;
	private static final double MAX_WINDOW_S = 0.060;
	private static final double MAX_OFFSET = 50.0 / UU_PER_BLOCK;

	private record Step(double dz, double start, double window) {
	}

	public record Applied(double offset, boolean reset) {
	}

	private final List<Step> steps = new ArrayList<>();
	private long lastTick = Long.MIN_VALUE;

	private static double window(double horizSpeedBlocksPerSec) {
		double v = Math.abs(horizSpeedBlocksPerSec);
		double w = v <= 1e-9 ? MAX_WINDOW_S : EASE_DISTANCE / v;
		return Math.max(MIN_WINDOW_S, Math.min(MAX_WINDOW_S, w));
	}

	public long lastTick() {
		return lastTick;
	}

	public void reset() {
		steps.clear();
		lastTick = Long.MIN_VALUE;
	}

	public Applied applyUpTo(List<TickRing.Entry> since, long maxTick, long oldestTick, double nowSeconds,
			double horizSpeedBlocksPerSec) {
		List<TickRing.Entry> bounded = since;
		if (!since.isEmpty() && since.get(since.size() - 1).tick() > maxTick) {
			bounded = new ArrayList<>(since.size());
			for (TickRing.Entry e : since) {
				if (e.tick() <= maxTick) {
					bounded.add(e);
				}
			}
		}
		if (lastTick == Long.MIN_VALUE || lastTick + 1 < oldestTick) {
			reset();
			if (!bounded.isEmpty()) {
				lastTick = bounded.get(bounded.size() - 1).tick();
			}
			return new Applied(0, true);
		}
		return new Applied(apply(bounded, nowSeconds, horizSpeedBlocksPerSec), false);
	}

	private double apply(List<TickRing.Entry> since, double nowSeconds, double horizSpeedBlocksPerSec) {
		double w = window(horizSpeedBlocksPerSec);
		for (TickRing.Entry e : since) {
			if (e.tick() <= lastTick) {
				continue;
			}
			lastTick = e.tick();
			if (e.stepDz() != 0f) {
				steps.add(new Step(e.stepDz(), nowSeconds, w));
			}
		}
		double sum = 0;
		for (int i = steps.size() - 1; i >= 0; i--) {
			Step s = steps.get(i);
			double t = (nowSeconds - s.start) / s.window;
			if (t >= 1) {
				steps.remove(i);
				continue;
			}
			double k = t <= 0 ? 0 : t * t * (3 - 2 * t);
			sum -= s.dz * (1 - k);
		}
		return Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, sum));
	}
}
