package dev.mcrl.sim;

import net.minecraft.server.MinecraftServer;

public interface SimClock {
	enum Mode { CLIENT_PACED, LOCKSTEP, REALTIME }

	int TICK_RATE = 120;
	double DT_NANOS = 1e9 / TICK_RATE;
	long MAX_GAP_NANOS = 50_000_000L;
	int MAX_TICKS_PER_PACE = 6;

	Mode mode();

	int onPace(long nowNanos, float rateFactor);

	float alpha();

	void reset();

	static Mode selectedMode(boolean dedicated, boolean published) {
		if ("lockstep".equalsIgnoreCase(System.getProperty("mcrl.clock"))) {
			return Mode.LOCKSTEP;
		}
		return dedicated || published ? Mode.REALTIME : Mode.CLIENT_PACED;
	}

	static Mode selectedMode(MinecraftServer server) {
		return selectedMode(server.isDedicatedServer(), server.isPublished());
	}

	static SimClock create(Mode mode) {
		return switch (mode) {
			case CLIENT_PACED -> new ClientPaced();
			case LOCKSTEP -> new Lockstep();
			case REALTIME -> new Realtime();
		};
	}

	final class ClientPaced implements SimClock {
		private final long maxGapNanos;
		private final int maxTicks;
		private boolean hasLast;
		private long last;
		private double acc;

		public ClientPaced() {
			this(MAX_GAP_NANOS, MAX_TICKS_PER_PACE);
		}

		public ClientPaced(long maxGapNanos, int maxTicks) {
			this.maxGapNanos = maxGapNanos;
			this.maxTicks = maxTicks;
		}

		@Override
		public Mode mode() {
			return Mode.CLIENT_PACED;
		}

		@Override
		public int onPace(long nowNanos, float rateFactor) {
			if (!hasLast) {
				hasLast = true;
				last = nowNanos;
				return 0;
			}
			long gap = nowNanos - last;
			last = nowNanos;
			if (gap <= 0) {
				return 0;
			}
			acc += Math.min(gap, maxGapNanos) * (double) rateFactor;
			int n = (int) Math.min(maxTicks, Math.floor(acc / DT_NANOS));
			acc -= n * DT_NANOS;
			if (acc >= DT_NANOS) {
				acc %= DT_NANOS;
			}
			if (acc < 0) {
				acc = 0;
			}
			return n;
		}

		@Override
		public float alpha() {
			float a = (float) (acc / DT_NANOS);
			return a >= 1f ? Math.nextDown(1f) : Math.max(0f, a);
		}

		@Override
		public void reset() {
			hasLast = false;
		}
	}

	final class Lockstep implements SimClock {
		@Override
		public Mode mode() {
			return Mode.LOCKSTEP;
		}

		@Override
		public int onPace(long nowNanos, float rateFactor) {
			return 0;
		}

		@Override
		public float alpha() {
			return 0f;
		}

		@Override
		public void reset() {
		}
	}

	final class Realtime implements SimClock {
		public static final long PERIOD_NANOS = (long) DT_NANOS;

		private boolean armed;
		private long last;
		private double acc;
		private float rate = 1f;
		private long lastDeadline;

		@Override
		public Mode mode() {
			return Mode.REALTIME;
		}

		public int due(long nowNanos, float rate) {
			this.rate = rate;
			if (!armed) {
				armed = true;
				last = nowNanos;
				lastDeadline = nowNanos;
				acc = 0;
				return 0;
			}
			long gap = nowNanos - last;
			if (gap <= 0) {
				return 0;
			}
			last = nowNanos;
			acc += gap * (double) rate;
			long n = (long) Math.floor(acc / PERIOD_NANOS);
			if (n > MAX_TICKS_PER_PACE) {
				acc = 0;
				lastDeadline = nowNanos;
				return MAX_TICKS_PER_PACE;
			}
			if (n > 0) {
				acc -= n * PERIOD_NANOS;
				lastDeadline = nowNanos - (long) Math.ceil(acc / rate);
			}
			return (int) n;
		}

		public long waitNanos(long nowNanos) {
			if (!armed) {
				return 0;
			}
			double left = (PERIOD_NANOS - acc) / rate - (nowNanos - last);
			return left <= 0 ? 0 : (long) Math.ceil(left);
		}

		public long lastDeadline() {
			return lastDeadline;
		}

		@Override
		public int onPace(long nowNanos, float rateFactor) {
			return 0;
		}

		@Override
		public float alpha() {
			return 0f;
		}

		@Override
		public void reset() {
			armed = false;
		}
	}
}
