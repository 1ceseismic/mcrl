package dev.mcrl.sim;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class SimTestHooks {
	private SimTestHooks() {
	}

	public static CountDownLatch block(SimHost h) {
		CountDownLatch release = new CountDownLatch(1);
		h.runOnSimThread(() -> {
			try {
				release.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		return release;
	}

	public static void feedAll(SimHost h, dev.mcrl.terrain.TerrainSource src) throws Exception {
		SimHostTest.feedAll(h, src);
	}

	public static CountDownLatch blockNow(SimHost h) throws InterruptedException {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		h.runOnSimThread(() -> {
			started.countDown();
			try {
				release.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		if (!started.await(10, TimeUnit.SECONDS)) {
			throw new AssertionError("the sim thread did not reach the blocking job within 10 s");
		}
		return release;
	}
}
