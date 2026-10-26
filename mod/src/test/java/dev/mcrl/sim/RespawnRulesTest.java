package dev.mcrl.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.mcrl.math.Pose;

class RespawnRulesTest {
	private static final int MIN_Y = -64;

	private static CarState car(Pose p, int flags, int wheels) {
		return new CarState(p, 0, 0, 0, 0, 0, 0, 100f, flags, null, 0f, wheels, 0, 0f, 0f);
	}

	@Test
	void ball_rules() {
		Pose car = new Pose(0, 70, 0, 1, 0, 0, 0);
		assertTrue(RespawnRules.inVoid(new Pose(0, MIN_Y - 16.01, 0, 1, 0, 0, 0), MIN_Y));
		assertFalse(RespawnRules.inVoid(new Pose(0, MIN_Y - 16, 0, 1, 0, 0, 0), MIN_Y));
		Pose near = new Pose(500, 70, 0, 1, 0, 0, 0), far = new Pose(0, 70, 513, 1, 0, 0, 0);
		assertFalse(RespawnRules.ballMustRespawn(near, 3600, car, MIN_Y));
		assertTrue(RespawnRules.ballMustRespawn(near, 3601, car, MIN_Y), "parked too long");
		assertTrue(RespawnRules.ballMustRespawn(far, 0, car, MIN_Y), "beyond the leash");
		assertFalse(RespawnRules.ballMustRespawn(far, 0, null, MIN_Y), "no car, no leash");
		assertFalse(RespawnRules.ballMustRespawn(far, 0, new Pose(0, MIN_Y - 17, -500, 1, 0, 0, 0), MIN_Y),
				"no leash to a car in the void (1000 blocks away)");
		assertTrue(RespawnRules.ballMustRespawn(new Pose(0, MIN_Y - 17, 0, 1, 0, 0, 0), 0, null, MIN_Y), "void");
		assertTrue(RespawnRules.ballSafe(near, false, MIN_Y));
		assertFalse(RespawnRules.ballSafe(near, true, MIN_Y), "parked");
		assertFalse(RespawnRules.ballSafe(new Pose(0, MIN_Y - 1, 0, 1, 0, 0, 0), false, MIN_Y), "below minY");
	}

	@Test
	void car_demo_void_and_respawn_pose() {
		assertTrue(RespawnRules.carDemoVoid(new Pose(0, -64 - 4.01, 0, 1, 0, 0, 0), -64));
		assertFalse(RespawnRules.carDemoVoid(new Pose(0, -64 - 3.99, 0, 1, 0, 0, 0), -64));
		assertEquals(4, RespawnRules.DEMO_VOID_MARGIN);
		assertEquals(360, RespawnRules.RESPAWN_TICKS);
		Pose safe = Pose.upright(1, 70, 2, 45f);
		Pose at = new Pose(5, -80, 6, 0.8f, 0.6f, 0, 0);
		assertEquals(safe, RespawnRules.respawnPose(safe, at));
		Pose upright = RespawnRules.respawnPose(null, at);
		assertEquals(5, upright.x());
		assertEquals(-80, upright.y());
		assertEquals(6, upright.z());
		assertEquals(at.yawDegrees(), upright.yawDegrees(), 1e-3);
		assertEquals(1, upright.up().y, 1e-6, "upright");
	}
}
