package dev.mcrl.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.mcrl.math.Pose;
import dev.mcrl.sim.TickRing;

class MobHitsMathTest {
	@Test
	void wreck_entries_are_skipped() {
		TickRing.Entry live1 = entry(1, false), wreck2 = entry(2, true), wreck3 = entry(3, true), live4 = entry(4, false);
		List<TickRing.Entry> all = List.of(live1, wreck2, wreck3, live4);
		assertEquals(List.of(live1, live4), MobHits.liveEntries(all), "the demolition and respawn ticks of one batch");
		assertEquals(List.of(), MobHits.liveEntries(List.of(wreck2, wreck3)));
		List<TickRing.Entry> none = List.of(live1, live4);
		assertTrue(MobHits.liveEntries(none) == none, "no wreck: the same list");
	}

	private static TickRing.Entry entry(long tick, boolean wreck) {
		return new TickRing.Entry(tick, new Pose(0, 64, 0, 1, 0, 0, 0), 0, 0, 0, 0f, wreck, 0, 0, 0);
	}
}
