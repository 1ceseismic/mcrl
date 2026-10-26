package dev.mcrl.client.link;

import static dev.mcrl.sim.SimClock.DT_NANOS;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.ThreadLocalRandom;

import dev.mcrl.net.Snapshot;
import dev.mcrl.sim.SimClock;

public final class LeadControl {
	static final int REANCHOR_FALL = 120;
	private static final int TARGET_SLACK = 2;
	private static final int STEADY_TICKS = 2;
	private static final long SETTLE_NANOS = 100_000_000L;
	private static final int NO_TREND_TICKS = 1;
	private static final long REANCHOR_PERSIST_NANOS = 300_000_000L;
	private static final int LEAD_MIN = 2, LEAD_MAX = 36, LEAD_MARGIN = 2;
	private static final float RATE_MAX = 0.03f, RATE_GAIN = 0.01f;
	private static final int REANCHOR_TICKS = 4;
	private static final long ESTIMATE_WINDOW_NANOS = 1_000_000_000L;
	private static final int OVERRULE_ARRIVALS = 4;
	private static final double OVERRULE_TICKS = 3;
	private static final double JITTER_ALPHA = 0.1;
	private static final double JITTER_FLOOR_TICKS = 0.05;
	private static final double JITTER_CAP_NANOS = 3 * DT_NANOS;
	private static final double SPAN_ALPHA = 0.05;

	private boolean has;
	private long newestS;
	private final ArrayDeque<long[]> recent = new ArrayDeque<>();
	private float serverRate = 1f;
	private boolean paused;
	private int rttMs;
	private long jitterS, jitterRecv;
	private boolean jitterHas;
	private double dev;
	private double span = 2, spanDev;
	private boolean hasSpan;
	private int epoch = ThreadLocalRandom.current().nextInt(256);
	private boolean reported;
	private int slack;
	private long reportSince;
	private final ArrayDeque<long[]> reports = new ArrayDeque<>();
	private boolean anchored, anchorRequested;
	private long highestSinceAnchor;
	private double impliedLead = Double.NaN;
	private long offSince = Long.MIN_VALUE;
	private boolean offAhead;
	private boolean epochSettled;

	public void onSnapshot(Snapshot s, long recvNanos, int rttMs) {
		boolean moved = !has || s.tick() != newestS;
		if (s.paused() || s.rate() != serverRate) {
			jitterHas = false;
		} else if (moved) {
			if (jitterHas && s.tick() > jitterS) {
				double expected = (s.tick() - jitterS) * DT_NANOS / s.rate();
				double actual = recvNanos - jitterRecv;
				if (actual >= expected / 2) {
					dev += JITTER_ALPHA * (Math.min(JITTER_CAP_NANOS, Math.abs(actual - expected)) - dev);
				}
			}
			jitterHas = true;
			jitterS = s.tick();
			jitterRecv = recvNanos;
		}
		if (s.paused() != paused || s.rate() != serverRate || has && s.tick() < newestS - REANCHOR_FALL) {
			recent.clear();
		}
		if (moved || recent.isEmpty()) {
			recent.addLast(new long[] {s.tick(), recvNanos});
		}
		while (recent.size() > 1 && recent.getFirst()[1] < recvNanos - ESTIMATE_WINDOW_NANOS) {
			recent.removeFirst();
		}
		highestSinceAnchor = has ? Math.max(highestSinceAnchor, s.tick()) : s.tick();
		newestS = s.tick();
		serverRate = s.rate() > 0f ? s.rate() : 1f;
		paused = s.paused();
		this.rttMs = Math.max(0, rttMs);
		has = true;
		if (!paused) {
			dropOverruledMaximum(recvNanos);
		}
		if (anchored && s.inputEpoch() == epoch) {
			if (!reported) {
				reported = true;
				reportSince = recvNanos;
			}
			slack = s.slack();
			reports.addLast(new long[] {recvNanos, slack, targetSlack()});
			while (reports.getFirst()[0] < recvNanos - SETTLE_NANOS) {
				reports.removeFirst();
			}
		} else {
			reported = false;
			reports.clear();
		}
	}

	private void dropOverruledMaximum(long nowNanos) {
		if (recent.size() <= OVERRULE_ARRIVALS) {
			return;
		}
		Iterator<long[]> it = recent.descendingIterator();
		long[] after = it.next();
		double newest = after[0] + (nowNanos - after[1]) * (double) serverRate / DT_NANOS;
		for (int i = 1; i < OVERRULE_ARRIVALS; i++) {
			long[] r = it.next();
			double expected = (after[0] - r[0]) * DT_NANOS / serverRate;
			if (Math.abs(after[1] - r[1] - expected) > DT_NANOS) {
				return;
			}
			newest = Math.max(newest, r[0] + (nowNanos - r[1]) * (double) serverRate / DT_NANOS);
			after = r;
		}
		if (serverTickEstimate(nowNanos) - newest > OVERRULE_TICKS) {
			while (recent.size() > OVERRULE_ARRIVALS) {
				recent.removeFirst();
			}
		}
	}

	public boolean hasSnapshot() {
		return has;
	}

	public boolean paused() {
		return paused;
	}

	public float serverRate() {
		return serverRate;
	}

	public double serverTickEstimate(long nowNanos) {
		if (paused || recent.isEmpty()) {
			return newestS;
		}
		double best = Double.NEGATIVE_INFINITY;
		for (long[] r : recent) {
			best = Math.max(best, r[0] + (nowNanos - r[1]) * (double) serverRate / DT_NANOS);
		}
		return best;
	}

	public float rate() {
		if (!steady()) {
			return 1f;
		}
		float r = RATE_GAIN * (targetSlack() - slack);
		return 1f + Math.max(-RATE_MAX, Math.min(RATE_MAX, r));
	}

	public float clockRate(long nowNanos, double position) {
		if (paused) {
			return 0f;
		}
		float r = rate();
		double lead = position - serverTickEstimate(nowNanos);
		if (r > 1f && lead >= LEAD_MAX || r < 1f && lead <= LEAD_MIN) {
			r = 1f;
		}
		return serverRate * r;
	}

	public int targetSlack() {
		return TARGET_SLACK + (int) Math.max(0, Math.ceil(2 * dev / DT_NANOS + 2 * spanDev - JITTER_FLOOR_TICKS));
	}

	public void onPayload(int ticks) {
		if (!hasSpan) {
			hasSpan = true;
			span = ticks;
		} else {
			spanDev += SPAN_ALPHA * (Math.abs(ticks - span) - spanDev);
			span += SPAN_ALPHA * (ticks - span);
		}
	}

	public int slack() {
		return reported ? slack : 0;
	}

	public int epoch() {
		return epoch;
	}

	public void requestAnchor() {
		anchorRequested = true;
	}

	public boolean needsAnchor(long nowNanos, double position) {
		if (!has) {
			return false;
		}
		if (!anchored || anchorRequested || newestS < highestSinceAnchor - REANCHOR_FALL) {
			return true;
		}
		double lead = position - serverTickEstimate(nowNanos);
		if (lead < LEAD_MIN - LEAD_MARGIN || lead > LEAD_MAX + LEAD_MARGIN) {
			return true;
		}
		if (!steady()) {
			offSince = Long.MIN_VALUE;
			return false;
		}
		impliedLead = lead + targetSlack() - slack;
		double off = clampLead(impliedLead) - lead;
		if (Math.abs(off) <= REANCHOR_TICKS) {
			offSince = Long.MIN_VALUE;
			epochSettled = true;
			return false;
		}
		if (!epochSettled) {
			return true;
		}
		if (offSince == Long.MIN_VALUE || (off > 0) != offAhead) {
			offSince = nowNanos;
			offAhead = off > 0;
		}
		return nowNanos - offSince >= REANCHOR_PERSIST_NANOS;
	}

	private boolean steady() {
		if (!reported || reports.getLast()[0] - reportSince < SETTLE_NANOS) {
			return false;
		}
		long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE, tlo = Long.MAX_VALUE, thi = Long.MIN_VALUE;
		for (long[] r : reports) {
			lo = Math.min(lo, r[1]);
			hi = Math.max(hi, r[1]);
			tlo = Math.min(tlo, r[2]);
			thi = Math.max(thi, r[2]);
		}
		return hi - lo <= STEADY_TICKS && Math.abs(reports.getLast()[1] - reports.getFirst()[1]) <= NO_TREND_TICKS
				&& thi - tlo <= NO_TREND_TICKS;
	}

	public double anchorPosition(long nowNanos) {
		double lead = Double.isNaN(impliedLead)
				? Math.ceil(rttMs * (double) serverRate * SimClock.TICK_RATE / 1000.0) + targetSlack()
						+ Math.ceil(span - 1e-9)
				: impliedLead;
		lead = clampLead(lead);
		anchored = true;
		anchorRequested = false;
		offSince = Long.MIN_VALUE;
		epochSettled = false;
		highestSinceAnchor = newestS;
		epoch = (epoch + 1) & 0xFF;
		reported = false;
		reports.clear();
		return serverTickEstimate(nowNanos) + lead;
	}

	public long anchorTick(long nowNanos) {
		return (long) Math.floor(anchorPosition(nowNanos));
	}

	private static double clampLead(double lead) {
		return Math.max(LEAD_MIN, Math.min(LEAD_MAX, lead));
	}
}
