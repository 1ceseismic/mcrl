package dev.mcrl.client.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class LinkGuardTest {
	private static final Runnable NO_IDLE_BODY = () -> {
	};
	private final List<RuntimeException> reported = new ArrayList<>();
	private int cleanups;
	private final LinkGuard guard = new LinkGuard(reported::add, () -> cleanups++);

	@Test
	void a_failure_is_reported_once_and_the_link_idles_for_that_world() {
		Object world = new Object();
		int[] frames = {0};
		guard.run(world, () -> frames[0]++, NO_IDLE_BODY);
		guard.run(world, () -> {
			frames[0]++;
			throw new IllegalStateException("boom");
		}, NO_IDLE_BODY);
		assertEquals(1, reported.size());
		assertEquals("boom", reported.get(0).getMessage());
		assertEquals(1, cleanups, "the failure cleanup ran");
		assertTrue(guard.idle());
		for (int i = 0; i < 10; i++) {
			guard.run(world, () -> frames[0]++, NO_IDLE_BODY);
		}
		assertEquals(2, frames[0], "no frame runs in the failed world");
		assertEquals(1, reported.size(), "reported once");
	}
}
