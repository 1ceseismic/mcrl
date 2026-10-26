package dev.mcrl.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BallHitsMathTest {
	@Test
	void chained_hits() {
		double[] v = {30, 0, 0}, dvRun = {0, 0, 0}, n = {-1, 0, 0}, rest = {0, 0, 0};
		double m = EntityMass.of(0.9, 1.3);
		int hits = 0;
		for (int i = 0; i < 10; i++) {
			if (BallHitTuning.exchangeAfter(v, dvRun, rest, n, m, BallHitTuning.E_MOB) != null) {
				hits++;
			}
		}
		assertEquals(1, hits);
		assertEquals(30 - 1.5 * 30 * (1 / EntityMass.BALL) / (1 / EntityMass.BALL + 1 / m), v[0] + dvRun[0], 1e-9);
		assertEquals(-0.12, v[0] + dvRun[0], 0.01);

		double a = Math.toRadians(20);
		double[] run = {0, 0, 0};
		double[] d1 = BallHitTuning.exchangeAfter(v, run, rest, new double[] {-Math.cos(a), 0, Math.sin(a)}, 70.2,
				BallHitTuning.E_MOB);
		double[] d2 = BallHitTuning.exchangeAfter(v, run, rest, new double[] {-Math.cos(a), 0, -Math.sin(a)}, 70.2,
				BallHitTuning.E_MOB);
		assertNotNull(d1);
		double vx = v[0] + run[0];
		assertTrue(vx > -5 && vx < 10, "the ball after both: " + vx + " (" + (d2 == null ? "one hit" : "two hits") + ")");
	}
}
