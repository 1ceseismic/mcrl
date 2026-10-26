package dev.mcrl.sim;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.MemorySegment;
import java.util.List;

import dev.mcrl.math.Coords;
import dev.mcrl.natives.Layouts;

public record HitboxPreset(int index, String name, double length, double width, double height,
		double offsetForward, double offsetUp, Axle front, Axle back, boolean threeWheels) {
	public record Axle(double wheelRadius, double suspensionRest, double connForward, double connUp,
			double connRight) {
	}

	private static final double UU = Coords.UU_PER_BLOCK;

	private static HitboxPreset uu(int index, String name, double sizeX, double sizeY, double sizeZ, double offsetX,
			double offsetZ, double frontRadius, double frontRest, double frontX, double frontY, double frontZ,
			double backRadius, double backRest, double backX, double backY, double backZ, boolean threeWheels) {
		return new HitboxPreset(index, name, sizeX / UU, sizeY / UU, sizeZ / UU, offsetX / UU, offsetZ / UU,
				new Axle(frontRadius / UU, frontRest / UU, frontX / UU, frontZ / UU, frontY / UU),
				new Axle(backRadius / UU, backRest / UU, backX / UU, backZ / UU, backY / UU), threeWheels);
	}

	// RocketSim's numbers, in UU.
	public static final HitboxPreset OCTANE = uu(0, "OCTANE", 120.507, 86.6994, 38.6591, 13.8757, 20.755,
			12.5, 38.755, 51.25, 25.90, 20.755, 15, 37.055, -33.75, 29.50, 20.755, false);
	public static final HitboxPreset DOMINUS = uu(1, "DOMINUS", 130.427, 85.7799, 33.8, 9, 15.75,
			12, 33.95, 50.30, 31.10, 15.75, 13.5, 33.85, -34.75, 33.00, 15.75, false);
	public static final HitboxPreset PLANK = uu(2, "PLANK", 131.32, 87.1704, 31.8944, 9.00857, 12.0942,
			12.5, 31.9242, 49.97, 27.80, 12.0942, 17, 27.9242, -35.43, 20.28, 12.0942, false);
	public static final HitboxPreset BREAKOUT = uu(3, "BREAKOUT", 133.992, 83.021, 32.8, 12.5, 11.75,
			13.5, 29.7, 51.50, 26.67, 11.75, 15, 29.666, -35.75, 35.00, 11.75, false);
	public static final HitboxPreset HYBRID = uu(4, "HYBRID", 129.519, 84.6879, 36.6591, 13.8757, 20.755,
			12.5, 38.755, 51.25, 25.90, 20.755, 15, 37.055, -34.00, 29.50, 20.755, false);
	public static final HitboxPreset MERC = uu(5, "MERC", 123.22, 79.2103, 44.1591, 11.3757, 21.505,
			15, 39.505, 51.25, 25.90, 21.505, 15, 39.105, -33.75, 29.50, 21.505, false);
	public static final HitboxPreset PSYCLOPS = uu(6, "PSYCLOPS", 120.641, 86.8334, 38.7931, 13.8757, 15.0,
			12.5, 33.0, 51.25, 5.0, 15.0, 15, 31.3, -33.75, 29.50, 15.0, true);

	public static final double MAX_SUSPENSION_TRAVEL = 12 / UU;

	public static final List<HitboxPreset> ALL = List.of(OCTANE, DOMINUS, PLANK, BREAKOUT, HYBRID, MERC, PSYCLOPS);

	public static HitboxPreset of(int index) {
		return index >= 0 && index < ALL.size() ? ALL.get(index) : OCTANE;
	}

	public double halfForward() {
		return length / 2;
	}

	public double halfUp() {
		return height / 2;
	}

	public double halfRight() {
		return width / 2;
	}

	public static HitboxPreset readFrom(MemorySegment s) {
		int index = s.get(JAVA_INT, Layouts.CAR_CONFIG_PRESET);
		long size = Layouts.CAR_CONFIG_HITBOX_SIZE, off = Layouts.CAR_CONFIG_HITBOX_OFFSET;
		return new HitboxPreset(index, of(index).name(), f(s, size), f(s, size + 8), f(s, size + 4), f(s, off),
				f(s, off + 4), axle(s, 0), axle(s, 1), s.get(JAVA_INT, Layouts.CAR_CONFIG_THREE_WHEELS) != 0);
	}

	private static double f(MemorySegment s, long offset) {
		return s.get(JAVA_FLOAT, offset);
	}

	private static Axle axle(MemorySegment s, int i) {
		long conn = Layouts.CAR_CONFIG_CONNECTION + 12L * i;
		return new Axle(f(s, Layouts.CAR_CONFIG_WHEEL_RADIUS + 4L * i), f(s, Layouts.CAR_CONFIG_SUSPENSION_REST + 4L * i),
				f(s, conn), f(s, conn + 4), f(s, conn + 8));
	}
}
