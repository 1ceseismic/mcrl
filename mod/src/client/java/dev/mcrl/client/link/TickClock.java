package dev.mcrl.client.link;

import static dev.mcrl.sim.SimClock.DT_NANOS;

import dev.mcrl.sim.SimClock;

final class TickClock {
	public static final int MAX_BURST = SimClock.MAX_TICKS_PER_PACE;

	private boolean hasLast;
	private long last;
	private double acc;
	private long tick;

	public int advance(long nowNanos, float rate) {
		if (!hasLast) {
			hasLast = true;
			last = nowNanos;
			return 0;
		}
		long gap = nowNanos - last;
		last = nowNanos;
		if (gap > 0 && rate > 0f) {
			acc += gap * (double) rate;
		}
		long due = (long) Math.floor(acc / DT_NANOS);
		acc -= due * DT_NANOS;
		if (acc < 0) {
			acc = 0;
		}
		tick += due;
		return (int) Math.min(due, MAX_BURST);
	}

	public long tick() {
		return tick;
	}

	public double position() {
		return tick + acc / DT_NANOS;
	}

	public void anchor(double position) {
		tick = (long) Math.floor(position);
		acc = (position - tick) * DT_NANOS;
	}
}
