package dev.mcrl.client.fx.particle;

import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

public final class FxRandom implements RandomGenerator {
	private final SplittableRandom r;

	public FxRandom(long seed) {
		r = new SplittableRandom(seed);
	}

	public static long emitterSeed(long systemSeed, int index) {
		return systemSeed + index;
	}

	@Override
	public long nextLong() {
		return r.nextLong();
	}

	@Override
	public int nextInt() {
		return r.nextInt();
	}

	@Override
	public int nextInt(int bound) {
		return r.nextInt(bound);
	}
}
