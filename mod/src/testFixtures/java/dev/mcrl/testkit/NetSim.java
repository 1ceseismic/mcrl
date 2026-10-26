package dev.mcrl.testkit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.jspecify.annotations.Nullable;

public final class NetSim {
	public record Params(int latencyMs, int jitterMs, double loss, long seed) {
		public Params {
			if (latencyMs < 0 || jitterMs < 0 || jitterMs > latencyMs || !(loss >= 0 && loss <= 1)) {
				throw new IllegalArgumentException("netsim " + latencyMs + "," + jitterMs + "," + loss);
			}
		}

		@Override
		public String toString() {
			return latencyMs + "," + jitterMs + "," + loss;
		}
	}

	private NetSim() {
	}

	public static @Nullable Params parse(@Nullable String s) {
		if (s == null) {
			return null;
		}
		String[] p = s.trim().split(",");
		if (p.length != 3) {
			return null;
		}
		try {
			return new Params(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Double.parseDouble(p[2].trim()),
					1);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public record Arrival<T>(long atNanos, T value) {
	}

	public static final class Queue<T> {
		private record Item<T>(long at, T value) {
		}

		private final long latency, jitter;
		private final double loss;
		private final Random rng;
		private final ArrayDeque<Item<T>> items = new ArrayDeque<>();
		private long last = Long.MIN_VALUE;

		public Queue(Params p) {
			this.latency = p.latencyMs() * 1_000_000L;
			this.jitter = p.jitterMs() * 1_000_000L;
			this.loss = p.loss();
			this.rng = new Random(p.seed());
		}

		public void offer(T item, long nowNanos) {
			long at = nowNanos + latency + (jitter == 0 ? 0 : Math.round((rng.nextDouble() * 2 - 1) * jitter));
			if (loss > 0 && rng.nextDouble() < loss) {
				at += 2 * latency;
			}
			at = Math.max(at, last);
			last = at;
			items.addLast(new Item<>(at, item));
		}

		public List<T> poll(long nowNanos) {
			List<T> out = new ArrayList<>();
			for (Arrival<T> a : pollArrivals(nowNanos)) {
				out.add(a.value());
			}
			return out;
		}

		public List<Arrival<T>> pollArrivals(long nowNanos) {
			List<Arrival<T>> out = new ArrayList<>();
			while (!items.isEmpty() && items.getFirst().at() <= nowNanos) {
				Item<T> i = items.removeFirst();
				out.add(new Arrival<>(i.at(), i.value()));
			}
			return out;
		}
	}
}
