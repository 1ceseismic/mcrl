package dev.mcrl.rlassets.bodies;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BodyIdsTest {
	@Test
	void valid() {
		assertTrue(BodyIds.valid("Body_Octane"));
		assertTrue(BodyIds.valid("Body_Dominus"));
		assertTrue(BodyIds.valid("a".repeat(64)));
		assertFalse(BodyIds.valid(""));
		assertFalse(BodyIds.valid("../x"));
		assertFalse(BodyIds.valid("a/b"));
		assertFalse(BodyIds.valid("a".repeat(65)));
		assertFalse(BodyIds.valid("Body.Octane"));
		assertFalse(BodyIds.valid(null));
	}
}
