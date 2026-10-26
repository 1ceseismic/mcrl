package dev.mcrl.sim;

import java.util.ArrayList;
import java.util.List;

import dev.mcrl.math.Pose;

public final class TickRing {
	public static final int CAPACITY = 32;

	public record Entry(long tick, Pose pose, double vx, double vy, double vz, float stepDz, boolean wreck, double wx,
			double wy, double wz) {
	}

	private final int capacity;
	private final Entry[] buf;
	private long count;
	private long floor;

	public TickRing(int capacity) {
		if (capacity < 1) {
			throw new IllegalArgumentException("capacity " + capacity);
		}
		this.capacity = capacity;
		this.buf = new Entry[capacity];
	}

	public synchronized void add(Entry e) {
		buf[(int) (count % capacity)] = e;
		count++;
	}

	private long first() {
		return Math.max(floor, Math.max(0, count - capacity));
	}

	public synchronized long oldestTick() {
		return count <= first() ? Long.MAX_VALUE : buf[(int) (first() % capacity)].tick();
	}

	public synchronized List<Entry> since(long afterTick) {
		List<Entry> out = new ArrayList<>();
		for (long i = first(); i < count; i++) {
			Entry e = buf[(int) (i % capacity)];
			if (e.tick() > afterTick) {
				out.add(e);
			}
		}
		return out;
	}

	public synchronized Entry at(long tick) {
		for (long i = count - 1; i >= first(); i--) {
			Entry e = buf[(int) (i % capacity)];
			if (e.tick() == tick) {
				return e;
			}
			if (e.tick() < tick) {
				return null;
			}
		}
		return null;
	}

	public synchronized void truncateAfter(long tick) {
		floor = first();
		while (count > floor && buf[(int) ((count - 1) % capacity)].tick() > tick) {
			count--;
		}
	}
}
