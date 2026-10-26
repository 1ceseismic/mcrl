package dev.mcrl.sim;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

import java.lang.foreign.MemorySegment;

import dev.mcrl.natives.Layouts;

public record Hit(double px, double py, double pz, float nx, float ny, float nz) {
	public static final Hit NONE = new Hit(0, 0, 0, 0f, 0f, 0f);

	public static Hit readFrom(MemorySegment s, long off) {
		long n = off + Layouts.HIT_FX_NORMAL;
		return new Hit(s.get(JAVA_DOUBLE, off), s.get(JAVA_DOUBLE, off + 8), s.get(JAVA_DOUBLE, off + 16),
				s.get(JAVA_FLOAT, n), s.get(JAVA_FLOAT, n + 4), s.get(JAVA_FLOAT, n + 8));
	}
}
