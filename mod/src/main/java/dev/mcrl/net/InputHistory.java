package dev.mcrl.net;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jspecify.annotations.Nullable;

import dev.mcrl.sim.Controls;

public final class InputHistory {
	public static final int CAPACITY = 256;

	private final long[] tickOf = new long[CAPACITY];
	private final Controls[] controls = new Controls[CAPACITY];
	private boolean any;
	private long newest;
	private long sentThrough;

	public InputHistory() {
		Arrays.fill(tickOf, Long.MIN_VALUE);
	}

	public synchronized void put(long tick, Controls c) {
		int k = slot(tick);
		tickOf[k] = tick;
		controls[k] = c;
		if (!any) {
			any = true;
			sentThrough = tick - 1;
		} else if (tick <= newest) {
			sentThrough = Math.min(sentThrough, tick - 1);
		}
		newest = tick;
	}

	public synchronized @Nullable Controls get(long tick) {
		int k = slot(tick);
		return any && tick <= newest && tickOf[k] == tick ? controls[k] : null;
	}

	public synchronized long newest() {
		return any ? newest : Long.MIN_VALUE;
	}

	public synchronized List<NetPayloads.InputC2S> takeUnsent(int maxPerPayload, int epoch) {
		List<NetPayloads.InputC2S> out = new ArrayList<>();
		if (!any || sentThrough >= newest) {
			return out;
		}
		long from = Math.max(sentThrough + 1, newest - CAPACITY + 1);
		List<Controls> run = new ArrayList<>();
		long runStart = from;
		for (long t = from; t <= newest; t++) {
			Controls c = get(t);
			if (c == null || run.size() == maxPerPayload) {
				flush(out, runStart, epoch, run);
				runStart = t;
			}
			if (c == null) {
				runStart = t + 1;
			} else {
				run.add(c);
			}
		}
		flush(out, runStart, epoch, run);
		sentThrough = newest;
		return out;
	}

	private static void flush(List<NetPayloads.InputC2S> out, long first, int epoch, List<Controls> run) {
		if (!run.isEmpty()) {
			out.add(new NetPayloads.InputC2S(first, epoch, run.toArray(new Controls[0])));
			run.clear();
		}
	}

	private static int slot(long tick) {
		return (int) Math.floorMod(tick, (long) CAPACITY);
	}
}
