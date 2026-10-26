package dev.mcrl.sim;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.mcrl.math.Pose;
import dev.mcrl.natives.Layouts;
import dev.mcrl.natives.McrlNative;
import dev.mcrl.natives.NativeBoot;

class HitboxPresetTest {
	private static final double EPS = 1e-6;

	@BeforeAll
	static void boot() {
		NativeBoot.ensureStarted();
	}

	private static void assertAxle(HitboxPreset.Axle want, HitboxPreset.Axle got, String what) {
		assertEquals(want.wheelRadius(), got.wheelRadius(), EPS, what + " wheelRadius");
		assertEquals(want.suspensionRest(), got.suspensionRest(), EPS, what + " suspensionRest");
		assertEquals(want.connForward(), got.connForward(), EPS, what + " connForward");
		assertEquals(want.connUp(), got.connUp(), EPS, what + " connUp");
		assertEquals(want.connRight(), got.connRight(), EPS, what + " connRight");
	}

	@Test
	void presets_match_native_config() {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment w = McrlNative.worldCreate(MemorySegment.NULL, 0, 0, 0);
			try {
				MemorySegment st = a.allocate(Layouts.CAR_STATE);
				MemorySegment cfg = a.allocate(Layouts.CAR_CONFIG);
				for (int i = 0; i < 7; i++) {
					CarState.of(new Pose(8.5 + 6 * i, 70, 8.5, 1, 0, 0, 0), 0, 0, 0, 0, 0, 0).writeTo(st);
					McrlNative.carAdd(w, i + 1, i % 2, i, st);
					McrlNative.carGetConfig(w, i + 1, cfg);
					HitboxPreset want = HitboxPreset.of(i), got = HitboxPreset.readFrom(cfg);
					String p = want.name();
					assertEquals(i, got.index(), p);
					assertEquals(want.name(), got.name(), p);
					assertEquals(want.length(), got.length(), EPS, p + " length");
					assertEquals(want.width(), got.width(), EPS, p + " width");
					assertEquals(want.height(), got.height(), EPS, p + " height");
					assertEquals(want.offsetForward(), got.offsetForward(), EPS, p + " offsetForward");
					assertEquals(want.offsetUp(), got.offsetUp(), EPS, p + " offsetUp");
					assertAxle(want.front(), got.front(), p + " front");
					assertAxle(want.back(), got.back(), p + " back");
					assertEquals(want.threeWheels(), got.threeWheels(), p);
					assertEquals(i % 2, cfg.get(JAVA_INT, Layouts.CAR_CONFIG_TEAM), p + " team");
				}
			} finally {
				McrlNative.worldDestroy(w);
			}
		}
	}
}
