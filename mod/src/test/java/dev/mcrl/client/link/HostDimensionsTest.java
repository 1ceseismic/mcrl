package dev.mcrl.client.link;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HostDimensionsTest {
	private static final String OVERWORLD = "minecraft:overworld", NETHER = "minecraft:the_nether";
	private static final int LATE = 120;

	@Test
	void theOldLevelsHostIsDroppedInTheNewOneHoweverLate() {
		HostDimensions h = new HostDimensions();
		assertTrue(h.accepts(10, OVERWORLD));
		assertTrue(h.accepts(10, OVERWORLD));
		for (int i = 0; i < LATE; i++) {
			assertFalse(h.accepts(10, NETHER), "the overworld host's snapshot " + i + " after the change");
		}
	}
}
