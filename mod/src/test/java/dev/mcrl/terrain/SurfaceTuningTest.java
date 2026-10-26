package dev.mcrl.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import org.junit.jupiter.api.Test;

import dev.mcrl.terrain.SurfaceTuning.Material;

class SurfaceTuningTest {
	@Test
	void write_to_the_native_layout() {
		Material slime = SurfaceTuning.material(0.8f, 0.5f, 1f);
		try (Arena a = Arena.ofConfined()) {
			MemorySegment s = a.allocate(40);
			s.fill((byte) 0x55);
			slime.writeTo(s);
			float[] want = {slime.wheelLat(), slime.wheelLong(), slime.wheelDrag(), slime.chassisFriction(),
					slime.chassisRestitution(), slime.ballFriction(), slime.ballRestitution(), slime.ballDrag(),
					slime.padRestitution()};
			for (int i = 0; i < 9; i++) {
				assertEquals(want[i], s.get(JAVA_FLOAT, 4L * i), "field " + i);
			}
			assertEquals(0, s.get(JAVA_INT, 36), "pad");
		}
		assertTrue(slime.wheelDrag() > 0 && slime.padRestitution() > 0);
	}
}
