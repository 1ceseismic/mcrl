package dev.mcrl.sim;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.MemorySegment;

import dev.mcrl.natives.Layouts;
import dev.mcrl.natives.McrlNative;

public record WheelContact(boolean contact, double px, double py, double pz, float nx, float ny, float nz,
		boolean world, int lands, float landSpeed) {
	public static final WheelContact NONE = new WheelContact(false, 0, 0, 0, 0f, 0f, 0f, false, 0, 0f);

	public static WheelContact readFrom(MemorySegment s, long off) {
		long n = off + Layouts.WHEEL_FX_NORMAL;
		int bits = s.get(JAVA_INT, off + Layouts.WHEEL_FX_CONTACT);
		return new WheelContact((bits & McrlNative.WHEEL_CONTACT) != 0,
				s.get(JAVA_DOUBLE, off), s.get(JAVA_DOUBLE, off + 8), s.get(JAVA_DOUBLE, off + 16),
				s.get(JAVA_FLOAT, n), s.get(JAVA_FLOAT, n + 4), s.get(JAVA_FLOAT, n + 8),
				(bits & McrlNative.WHEEL_WORLD) != 0, s.get(JAVA_INT, off + Layouts.WHEEL_FX_LANDS),
				s.get(JAVA_FLOAT, off + Layouts.WHEEL_FX_LAND_SPEED));
	}
}
