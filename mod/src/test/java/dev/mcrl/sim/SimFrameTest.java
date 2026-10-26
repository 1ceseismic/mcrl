package dev.mcrl.sim;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import org.junit.jupiter.api.Test;

import dev.mcrl.natives.Layouts;
import dev.mcrl.natives.McrlNative;

class SimFrameTest {
	@Test
	void read_from_native_layout() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment s = arena.allocate(Layouts.CAR_FX);
			long wheel = Layouts.WHEEL_FX.byteSize();
			for (int i = 0; i < 4; i++) {
				long o = Layouts.CAR_FX_WHEELS + wheel * i;
				s.set(JAVA_DOUBLE, o, 10.0 * i + 1);
				s.set(JAVA_DOUBLE, o + 8, 10.0 * i + 2);
				s.set(JAVA_DOUBLE, o + 16, 10.0 * i + 3);
				s.set(JAVA_FLOAT, o + Layouts.WHEEL_FX_NORMAL, 10f * i + 4);
				s.set(JAVA_FLOAT, o + Layouts.WHEEL_FX_NORMAL + 4, 10f * i + 5);
				s.set(JAVA_FLOAT, o + Layouts.WHEEL_FX_NORMAL + 8, 10f * i + 6);
				s.set(JAVA_INT, o + Layouts.WHEEL_FX_CONTACT, i);
				s.set(JAVA_INT, o + Layouts.WHEEL_FX_LANDS, 10 * i + 7);
				s.set(JAVA_FLOAT, o + Layouts.WHEEL_FX_LAND_SPEED, 10f * i + 8);
			}
			s.set(JAVA_FLOAT, Layouts.CAR_FX_HANDBRAKE, 0.5f);
			s.set(JAVA_INT, Layouts.CAR_FX_JUMPS, 101);
			s.set(JAVA_INT, Layouts.CAR_FX_DOUBLE_JUMPS, 102);
			s.set(JAVA_INT, Layouts.CAR_FX_DODGES, 103);
			s.set(JAVA_INT, Layouts.CAR_FX_FLIP_RESETS, 104);
			s.set(JAVA_INT, Layouts.CAR_FX_WORLD_HITS, 105);
			s.set(JAVA_FLOAT, Layouts.CAR_FX_WORLD_HIT_SPEED, 106f);
			s.set(JAVA_INT, Layouts.CAR_FX_BODY_CONTACT, 1);
			s.set(JAVA_INT, Layouts.CAR_FX_ACTIVE_STATES, CarFx.DOUBLE_JUMPING);
			s.set(JAVA_DOUBLE, Layouts.CAR_FX_BODY_POINT, 107.0);
			s.set(JAVA_DOUBLE, Layouts.CAR_FX_BODY_POINT + 8, 108.0);
			s.set(JAVA_DOUBLE, Layouts.CAR_FX_BODY_POINT + 16, 109.0);
			s.set(JAVA_FLOAT, Layouts.CAR_FX_BODY_NORMAL, 110f);
			s.set(JAVA_FLOAT, Layouts.CAR_FX_BODY_NORMAL + 4, 111f);
			s.set(JAVA_FLOAT, Layouts.CAR_FX_BODY_NORMAL + 8, 112f);
			s.set(JAVA_FLOAT, Layouts.CAR_FX_SLIDE_SPEED, 113f);
			hit(s, Layouts.CAR_FX_WORLD_HIT, 200);
			hit(s, Layouts.CAR_FX_BALL_HIT, 300);
			s.set(JAVA_INT, Layouts.CAR_FX_BUMPS, 114);
			s.set(JAVA_INT, Layouts.CAR_FX_BUMPED_BY, 115);
			s.set(JAVA_FLOAT, Layouts.CAR_FX_BUMP_SPEED, 116f);

			CarFx fx = CarFx.readFrom(s);
			for (int i = 0; i < 4; i++) {
				assertEquals(new WheelContact((i & McrlNative.WHEEL_CONTACT) != 0, 10.0 * i + 1, 10.0 * i + 2,
						10.0 * i + 3, 10f * i + 4, 10f * i + 5, 10f * i + 6, (i & McrlNative.WHEEL_WORLD) != 0, 10 * i + 7,
						10f * i + 8), fx.wheel(i), "wheel " + i);
			}
			assertEquals(new CarFx(fx.wheels(), 0.5f, 101, 102, 103, 104, 105, 106f, true, 110f, 111f, 112f, 113f, 107.0,
					108.0, 109.0, new Hit(201, 202, 203, 204f, 205f, 206f), new Hit(301, 302, 303, 304f, 305f, 306f),
					CarFx.DOUBLE_JUMPING, 114, 115, 116f, Wet.DRY), fx);
			assertTrue(fx.doubleJumping());

			MemorySegment b = arena.allocate(Layouts.BALL_FX);
			b.set(JAVA_INT, Layouts.BALL_FX_WORLD_CONTACT, 1);
			b.set(JAVA_INT, Layouts.BALL_FX_LAST_TOUCH_TEAM, 1);
			hit(b, Layouts.BALL_FX_BOUNCE, 400);
			assertEquals(new BallFx(true, 1, new Hit(401, 402, 403, 404f, 405f, 406f), Wet.DRY), BallFx.readFrom(b));
			b.set(JAVA_INT, Layouts.BALL_FX_WORLD_CONTACT, 0);
			b.set(JAVA_INT, Layouts.BALL_FX_LAST_TOUCH_TEAM, -1);
			assertEquals(-1, BallFx.readFrom(b).lastTouchTeam());
			assertFalse(BallFx.readFrom(b).worldContact());

			MemorySegment first = arena.allocate(Layouts.CAR_FX);
			first.set(JAVA_INT, Layouts.CAR_FX_BUMPED_BY, -1);
			assertEquals(CarFx.EMPTY, CarFx.readFrom(first), "a McrlCarFx before the first tick");
		}
	}

	private static void hit(MemorySegment s, long off, int base) {
		s.set(JAVA_DOUBLE, off, base + 1.0);
		s.set(JAVA_DOUBLE, off + 8, base + 2.0);
		s.set(JAVA_DOUBLE, off + 16, base + 3.0);
		s.set(JAVA_FLOAT, off + Layouts.HIT_FX_NORMAL, base + 4f);
		s.set(JAVA_FLOAT, off + Layouts.HIT_FX_NORMAL + 4, base + 5f);
		s.set(JAVA_FLOAT, off + Layouts.HIT_FX_NORMAL + 8, base + 6f);
	}
}
