package dev.mcrl.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.world.level.Explosion;

class DemoRulesTest {
	@Test
	void demolishes() {
		assertEquals(2.0, DemoRules.MIN_RADIUS);
		assertTrue(DemoRules.demolishes(4, 3.9, .5f));
		assertFalse(DemoRules.demolishes(4, 4.1, 1f), "outside the radius");
		assertTrue(DemoRules.demolishes(2, 2, 1f), "radius and distance at the limits");
		assertFalse(DemoRules.demolishes(1.9, .5, 1f), "too small (a wind charge)");
		assertFalse(DemoRules.demolishes(4, 1, 0f), "fully behind cover");
	}

	@Test
	void wind_bursts_only_push() {
		assertTrue(DemoRules.pushOnly(Explosion.BlockInteraction.TRIGGER_BLOCK));
		assertFalse(DemoRules.demolishes(Explosion.BlockInteraction.TRIGGER_BLOCK, 3, 1, 1f), "a breeze's charge");
		assertFalse(DemoRules.demolishes(Explosion.BlockInteraction.TRIGGER_BLOCK, 3.5, 1, 1f), "Wind Burst");
		for (Explosion.BlockInteraction i : new Explosion.BlockInteraction[] {Explosion.BlockInteraction.KEEP,
				Explosion.BlockInteraction.DESTROY, Explosion.BlockInteraction.DESTROY_WITH_DECAY}) {
			assertFalse(DemoRules.pushOnly(i), i.name());
			assertTrue(DemoRules.demolishes(i, 3, 1, 1f), i + ": a creeper's blast");
		}
	}
}
