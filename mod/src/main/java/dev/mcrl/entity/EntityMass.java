package dev.mcrl.entity;

import net.minecraft.world.entity.Entity;

public final class EntityMass {
	// RocketSim's 30 : 180 kg ratio, in block³ units.
	public static final double BALL = 52;
	public static final double CAR = 310;
	public static final double MIN = 5, MAX = 2000;
	private static final double DENSITY = 100;

	private EntityMass() {
	}

	public static double of(double width, double height) {
		return Math.clamp(DENSITY * width * width * height, MIN, MAX);
	}

	public static double of(Entity e) {
		return of(e.getBbWidth(), e.getBbHeight());
	}
}
