package dev.mcrl.test;

import static dev.mcrl.test.GameTestSupport.EAST;
import static dev.mcrl.test.GameTestSupport.LONG_RUNWAY;
import static dev.mcrl.test.GameTestSupport.RUNWAY;
import static dev.mcrl.test.GameTestSupport.assertRunning;
import static dev.mcrl.test.GameTestSupport.clean;
import static dev.mcrl.test.GameTestSupport.drive;
import static dev.mcrl.test.GameTestSupport.fill;
import static dev.mcrl.test.GameTestSupport.host;
import static dev.mcrl.test.GameTestSupport.simTick;
import static dev.mcrl.test.GameTestSupport.spawnCar;
import static dev.mcrl.test.GameTestSupport.speed;
import static dev.mcrl.test.GameTestSupport.terrainKeepsUp;
import static dev.mcrl.test.GameTestSupport.topOf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.entity.CarEntity;
import dev.mcrl.math.OrientedBox;
import dev.mcrl.sim.CarState;
import dev.mcrl.sim.HitboxPreset;
import dev.mcrl.sim.SimHost;
import dev.mcrl.sim.SimHosts;
import dev.mcrl.test.GameTestSupport.Window;

public class TerrainGameTests {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl-test");

	@GameTest(environment = "mcrl-test:placed_wall_stops_car", structure = RUNWAY, maxTicks = 300)
	public void placedWallStopsCar(GameTestHelper h) {
		clean(h);
		terrainKeepsUp(h);
		fill(h, 0, 0, 0, 47, 0, 15, Blocks.STONE);
		CarEntity car = spawnCar(h, 3.0, 1.34, 8.0, EAST);
		long t0 = simTick(h);
		double face = h.absoluteVec(new Vec3(18, 0, 0)).x;
		double wallTop = topOf(h, 1);
		double[] front = {Double.NEGATIVE_INFINITY, 0};
		int[] seq = {0};
		Window w = new Window();
		h.startSequence()
				.thenWaitUntil(() -> assertRunning(h, car, t0))
				.thenExecute(() -> {
					CarState s = car.simState();
					host(h).setCar(car.getId(), CarState.of(s.pose(), 20, 0, 0, 0, 0, 0));
					fill(h, 18, 1, 0, 18, 1, 15, Blocks.STONE);
				})
				.thenExecuteFor(60, () -> {
					w.sample(h);
					host(h).submitInput(car.getId(), drive(++seq[0], 1f, false));
					double f = OrientedBox.car(car.simState().pose(), HitboxPreset.OCTANE).aabb()[3];
					front[0] = Math.max(front[0], f);
					if (f < face - 0.5) {
						front[1] = speed(car.simState());
					}
					h.assertTrue(car.getY() < wallTop, "car above the wall top: y " + car.getY() + " >= " + wallTop);
					h.assertTrue(car.getX() < face, "car past the wall face: x " + car.getX() + " >= " + face);
				})
				.thenExecute(() -> {
					w.assertNeverFroze(h);
					LOG.info("placed_wall_stops_car: max hitbox front - wall face = {}, approach speed {} blocks/s",
							front[0] - face, front[1]);
					h.assertTrue(front[1] > 15, "the car approached the wall at only " + front[1] + " blocks/s");
					h.assertTrue(front[0] > face - 0.25, "the car never reached the wall (hitbox front " + front[0] + ")");
					h.assertTrue(front[0] < face + 0.15, "the hitbox went " + (front[0] - face) + " into the wall");
					car.discard();
				})
				.thenSucceed();
	}

	@GameTest(environment = "mcrl-test:drive_across_long_runway", structure = LONG_RUNWAY, maxTicks = 400)
	public void driveAcrossLongRunwayNeverFreezes(GameTestHelper h) {
		clean(h);
		SimHosts.closeAll();
		terrainKeepsUp(h);
		fill(h, 0, 0, 1, 299, 0, 5, Blocks.STONE);
		double top = topOf(h, 0);
		CarEntity car = spawnCar(h, 3.0, 1.34, 3.5, EAST);
		long t0 = simTick(h);
		int[] o0 = {0, 0};
		int[] shifts = {0};
		double[] x0 = {0}, lowest = {Double.POSITIVE_INFINITY};
		int[] seq = {0};
		Window w = new Window();
		h.startSequence()
				.thenWaitUntil(() -> assertRunning(h, car, t0))
				.thenExecute(() -> {
					CarState s = car.simState();
					x0[0] = s.pose().x();
					o0[0] = o0[1] = host(h).origin()[0];
					host(h).setCar(car.getId(), CarState.of(s.pose(), 46, 0, 0, 0, 0, 0));
				})
				.thenExecuteFor(120, () -> {
					w.sample(h);
					SimHost host = host(h);
					host.submitInput(car.getId(), drive(++seq[0], 1f, true));
					int ox = host.origin()[0];
					if (ox != o0[1]) {
						shifts[0]++;
						LOG.info("drive_across_long_runway: O.x {} -> {} with the car {} blocks from its start", o0[1], ox,
								car.getX() - x0[0]);
						o0[1] = ox;
					}
					lowest[0] = Math.min(lowest[0], car.getY());
				})
				.thenExecute(() -> {
					w.assertNeverFroze(h);
					double dist = car.getX() - x0[0];
					LOG.info("drive_across_long_runway: {} blocks in 6 s, end speed {} blocks/s, O.x {} -> {} ({} shifts), "
							+ "lowest y - runway top {}", dist, speed(car.simState()), o0[0], o0[1], shifts[0],
							lowest[0] - top);
					h.assertTrue(shifts[0] >= 1, "the origin never moved: O.x " + o0[0] + ", the car went " + dist
							+ " blocks east of x " + x0[0]);
					h.assertTrue(lowest[0] > top + 0.2, "the car left the runway: lowest y " + (lowest[0] - top)
							+ " above its top");
					car.discard();
				})
				.thenSucceed();
	}
}
