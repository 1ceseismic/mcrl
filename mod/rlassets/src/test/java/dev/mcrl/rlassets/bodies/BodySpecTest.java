package dev.mcrl.rlassets.bodies;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BodySpecTest {
	@Test
	void legacyOffsetTakesTheProductsWheelRadius() {
		BodySpec vanquish = new BodySpec("Body_Vanquish", "Body_Vanquish_SF.upk", "Body_Merc_PremiumSkin_SK", 5,
				"Body_Vanquish_MIC", "VanquishChassis_MIC", new double[] {51.25, 25.9, -6.0},
				new double[] {-33.75, 29.5, -5.6}, 15, 15, new BodySpec.Axle(15, 14.5, -1, 12.5),
				new BodySpec.Axle(16, 13, -1, 15), true, 0, 8, 0, 0, null);
		assertEquals(-0.94538, vanquish.presetSuspensionOffsetZ(-6.55919, -4.65462), 1e-5);
		BodySpec sameRadius = new BodySpec("Body_Vanquish", "Body_Vanquish_SF.upk", "Body_Merc_PremiumSkin_SK", 5,
				"Body_Vanquish_MIC", "VanquishChassis_MIC", new double[] {51.25, 25.9, -6.0},
				new double[] {-33.75, 29.5, -5.6}, 15, 15, new BodySpec.Axle(15, 14.5, -1, 15),
				new BodySpec.Axle(16, 13, -1, 15), true, 0, 8, 0, 0, null);
		assertEquals(0.55919, sameRadius.presetSuspensionOffsetZ(-6.55919, -4.65462), 1e-5,
				"with the preset's radius the front term (−6 + 6.55919) would win");
	}
}
