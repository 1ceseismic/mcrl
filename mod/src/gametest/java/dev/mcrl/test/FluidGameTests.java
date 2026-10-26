package dev.mcrl.test;

import static dev.mcrl.test.GameTestSupport.RUNWAY;
import static dev.mcrl.test.GameTestSupport.clean;
import static dev.mcrl.test.GameTestSupport.fill;
import static dev.mcrl.test.GameTestSupport.host;
import static dev.mcrl.test.GameTestSupport.terrainKeepsUp;
import static dev.mcrl.test.GameTestSupport.topOf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.entity.BallEntity;
import dev.mcrl.entity.McrlEntities;
import dev.mcrl.sim.SimFrame;
import dev.mcrl.sim.Wet;

public class FluidGameTests {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl-test");
	private static final long FLOAT_SETTLE_TICKS = 400;

	private static double pool(GameTestHelper h) {
		fill(h, 0, 0, 0, 47, 0, 15, Blocks.STONE);
		fill(h, 0, 1, 0, 31, 4, 0, Blocks.GLASS);
		fill(h, 0, 1, 13, 31, 4, 13, Blocks.GLASS);
		fill(h, 0, 1, 1, 0, 4, 12, Blocks.GLASS);
		fill(h, 31, 1, 1, 31, 4, 12, Blocks.GLASS);
		fill(h, 1, 1, 1, 30, 4, 12, Blocks.WATER);
		return topOf(h, 3) + 8.0 / 9;
	}

	private static BallEntity spawnBall(GameTestHelper h, double rx, double ry, double rz) {
		BallEntity ball = McrlEntities.BALL.create(h.getLevel(), EntitySpawnReason.COMMAND);
		if (ball == null) {
			throw h.assertionException("could not create a BallEntity");
		}
		Vec3 a = h.absoluteVec(new Vec3(rx, ry, rz));
		ball.setSimState(BallEntity.restingAt(a.x, a.y, a.z));
		if (!h.getLevel().addFreshEntity(ball)) {
			throw h.assertionException("the ball was not added");
		}
		return ball;
	}

	private static SimFrame.BallSnap ballSnap(GameTestHelper h) {
		SimFrame.BallSnap b = host(h).latest().ball();
		if (b == null) {
			throw h.assertionException("no ball in the sim yet");
		}
		return b;
	}

	private static double speed(SimFrame.BallSnap b) {
		return Math.sqrt(b.vx() * b.vx() + b.vy() * b.vy() + b.vz() * b.vz());
	}

	@GameTest(environment = "mcrl-test:ball_floats_in_pool", structure = RUNWAY, maxTicks = 600)
	public void ballFloatsInPool(GameTestHelper h) {
		clean(h);
		terrainKeepsUp(h);
		double surface = pool(h);
		BallEntity ball = spawnBall(h, 15.5, surface - h.absoluteVec(Vec3.ZERO).y - 2, 6.5);
		long[] released = {-1};
		h.startSequence()
				.thenWaitUntil(() -> {
					ballSnap(h);
					released[0] = h.getTick();
				})
				.thenWaitUntil(() -> {
					SimFrame.BallSnap b = ballSnap(h);
					h.assertTrue(Math.abs(b.cur().y() - surface) < 0.1 && speed(b) < 0.1,
							"ball at y " + b.cur().y() + " (surface " + surface + "), speed " + speed(b));
				})
				.thenExecute(() -> {
					SimFrame.BallSnap b = ballSnap(h);
					long settle = h.getTick() - released[0];
					LOG.info("ball_floats_in_pool: settled {} server ticks after release, y - surface {}, speed {}, wet {}",
							settle, b.cur().y() - surface, speed(b), b.wet());
					h.assertTrue(settle <= FLOAT_SETTLE_TICKS, "the ball took " + settle + " server ticks to settle");
					h.assertTrue(b.wet().fluid() == Wet.WATER, "fluid " + b.wet().fluid());
					h.assertTrue(Math.abs(b.wet().immersion() - 0.5) < 0.1, "immersion " + b.wet().immersion());
					ball.discard();
				})
				.thenSucceed();
	}
}
