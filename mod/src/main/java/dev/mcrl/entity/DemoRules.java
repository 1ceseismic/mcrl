package dev.mcrl.entity;

import net.minecraft.world.level.Explosion;

public final class DemoRules {
	public static final double MIN_RADIUS = 2.0;

	private DemoRules() {
	}

	public static boolean demolishes(double radius, double distance, float seen) {
		return radius >= MIN_RADIUS && distance <= radius && seen > 0;
	}

	public static boolean demolishes(Explosion.BlockInteraction interaction, double radius, double distance, float seen) {
		return !pushOnly(interaction) && demolishes(radius, distance, seen);
	}

	// Only wind charges and Wind Burst trigger without breaking.
	public static boolean pushOnly(Explosion.BlockInteraction interaction) {
		return interaction == Explosion.BlockInteraction.TRIGGER_BLOCK;
	}
}
