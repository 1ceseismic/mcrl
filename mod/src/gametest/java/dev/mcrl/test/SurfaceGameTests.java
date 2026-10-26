package dev.mcrl.test;

import static dev.mcrl.test.GameTestSupport.EAST;
import static dev.mcrl.test.GameTestSupport.RUNWAY;
import static dev.mcrl.test.GameTestSupport.assertRunning;
import static dev.mcrl.test.GameTestSupport.clean;
import static dev.mcrl.test.GameTestSupport.drive;
import static dev.mcrl.test.GameTestSupport.fill;
import static dev.mcrl.test.GameTestSupport.host;
import static dev.mcrl.test.GameTestSupport.simTick;
import static dev.mcrl.test.GameTestSupport.spawnCar;
import static dev.mcrl.test.GameTestSupport.terrainKeepsUp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import dev.mcrl.entity.CarEntity;
import dev.mcrl.sim.CarState;
import dev.mcrl.test.GameTestSupport.Window;

public class SurfaceGameTests {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl-test");

	private static void lanes(GameTestHelper h, Block other) {
		fill(h, 0, 0, 0, 39, 0, 6, Blocks.STONE);
		fill(h, 0, 0, 9, 39, 0, 15, other);
	}

	private static final class Brake {
		final CarEntity car;
		double x0, maxX;
		boolean fast, stopped;
		int seq;

		Brake(CarEntity car) {
			this.car = car;
		}

		void start(GameTestHelper h) {
			CarState s = car.simState();
			x0 = maxX = s.pose().x();
			fast = stopped = false;
			host(h).setCar(car.getId(), CarState.of(s.pose(), 20, 0, 0, 0, 0, 0));
		}

		void tick(GameTestHelper h) {
			CarState s = car.simState();
			maxX = Math.max(maxX, s.pose().x());
			fast = fast || s.vx() > 10;
			stopped = stopped || (fast && s.vx() < 0.1);
			host(h).submitInput(car.getId(), drive(++seq, stopped ? 0f : -1f, false));
		}

		double distance() {
			return maxX - x0;
		}
	}

	@GameTest(environment = "mcrl-test:ice_brakes_longer", structure = RUNWAY, maxTicks = 300)
	public void iceBrakesLonger(GameTestHelper h) {
		clean(h);
		terrainKeepsUp(h);
		lanes(h, Blocks.ICE);
		Brake stone = new Brake(spawnCar(h, 4.0, 1.34, 3.5, EAST));
		Brake ice = new Brake(spawnCar(h, 4.0, 1.34, 12.5, EAST));
		long t0 = simTick(h);
		Window w = new Window();
		h.startSequence()
				.thenWaitUntil(() -> {
					assertRunning(h, stone.car, t0);
					assertRunning(h, ice.car, t0);
				})
				.thenExecute(() -> {
					stone.start(h);
					ice.start(h);
				})
				.thenExecuteFor(60, () -> {
					w.sample(h);
					stone.tick(h);
					ice.tick(h);
				})
				.thenExecute(() -> {
					w.assertNeverFroze(h);
					double ratio = ice.distance() / stone.distance();
					LOG.info("ice_brakes_longer: stone {} blocks, ice {} blocks, ratio {}", stone.distance(),
							ice.distance(), ratio);
					h.assertTrue(stone.stopped && ice.stopped, "a car did not stop within 60 ticks");
					h.assertTrue(stone.distance() > 1, "stone stopped in " + stone.distance() + " blocks");
					h.assertTrue(ratio >= 2.0 && ratio <= 3.0, "ice / stone braking distance " + ratio);
					stone.car.discard();
					ice.car.discard();
				})
				.thenSucceed();
	}
}
