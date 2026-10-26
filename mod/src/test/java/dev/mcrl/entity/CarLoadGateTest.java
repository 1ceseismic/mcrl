package dev.mcrl.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class CarLoadGateTest {
	private static long key(int cx, int cz) {
		return ((long) cx << 32) ^ (cz & 0xffffffffL);
	}

	private static Set<Long> square(int cx, int cz, int r) {
		Set<Long> s = new HashSet<>();
		for (int dz = -r; dz <= r; dz++) {
			for (int dx = -r; dx <= r; dx++) {
				s.add(key(cx + dx, cz + dz));
			}
		}
		return s;
	}

	@Test
	void waits_when_any_one_column_is_missing() {
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				Set<Long> loaded = square(0, 0, 1);
				loaded.remove(key(dx, dz));
				assertFalse(CarLoadGate.columnsReady(8, 8, (cx, cz) -> loaded.contains(key(cx, cz))),
						"missing column " + dx + "," + dz);
			}
		}
	}

	@Test
	void a_non_finite_position_never_enters() {
		assertFalse(CarLoadGate.columnsReady(Double.NaN, 0, (cx, cz) -> true));
		assertFalse(CarLoadGate.columnsReady(0, Double.POSITIVE_INFINITY, (cx, cz) -> true));
	}
}
