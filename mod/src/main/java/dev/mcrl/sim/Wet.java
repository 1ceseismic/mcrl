package dev.mcrl.sim;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.MemorySegment;

import dev.mcrl.natives.Layouts;

public record Wet(float immersion, int fluid, int splashes, float splashSpeed, double sx, double sy, double sz) {
	public static final int NONE = 0, WATER = 1, LAVA = 2, MEDIUM = 3;

	public static final Wet DRY = new Wet(0f, NONE, 0, 0f, 0, 0, 0);

	public static Wet readFrom(MemorySegment s, long offset) {
		long p = offset + Layouts.WET_FX_SPLASH_POINT;
		return new Wet(s.get(JAVA_FLOAT, offset + Layouts.WET_FX_IMMERSION), s.get(JAVA_INT, offset + Layouts.WET_FX_FLUID),
				s.get(JAVA_INT, offset + Layouts.WET_FX_SPLASHES), s.get(JAVA_FLOAT, offset + Layouts.WET_FX_SPLASH_SPEED),
				s.get(JAVA_DOUBLE, p), s.get(JAVA_DOUBLE, p + 8), s.get(JAVA_DOUBLE, p + 16));
	}
}
