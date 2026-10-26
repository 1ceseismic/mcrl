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
import static dev.mcrl.test.GameTestSupport.speed;
import static dev.mcrl.test.GameTestSupport.terrainKeepsUp;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.commands.CommandSource;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.SafeBoot;
import dev.mcrl.command.McrlCommands;
import dev.mcrl.entity.BallEntity;
import dev.mcrl.entity.CarEntity;
import dev.mcrl.entity.McrlEntities;
import dev.mcrl.math.Pose;
import dev.mcrl.natives.Breadcrumb;
import dev.mcrl.sim.BallState;
import dev.mcrl.sim.CarState;
import dev.mcrl.sim.SimFrame;
import dev.mcrl.sim.SimHost;
import dev.mcrl.sim.SimHosts;

public class PhysicsRestartGameTests {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl-test");
	private static final long STEP = 6;

	private static List<String> run(GameTestHelper h, String command) {
		List<String> out = new ArrayList<>();
		CommandSource capture = new CommandSource() {
			@Override
			public void sendSystemMessage(Component c) {
				out.add(c.getString());
			}

			@Override
			public boolean acceptsSuccess() {
				return true;
			}

			@Override
			public boolean acceptsFailure() {
				return true;
			}

			@Override
			public boolean shouldInformAdmins() {
				return false;
			}
		};
		MinecraftServer server = h.getLevel().getServer();
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSource(capture)
				.withLevel(h.getLevel()).withPermission(LevelBasedPermissionSet.GAMEMASTER), command);
		return out;
	}

	private static List<String> status(GameTestHelper h) {
		List<String> out = run(h, "mcrl physics status");
		Breadcrumb.Previous crash = SafeBoot.lastCrash();
		if (crash == null) {
			return out;
		}
		String line = McrlCommands.crashLine(crash).getString();
		h.assertTrue(!out.isEmpty() && out.get(0).equals(line), "status does not start with the crash line '" + line
				+ "': " + out);
		return out.subList(1, out.size());
	}

	private static String dim(GameTestHelper h) {
		return h.getLevel().dimension().identifier().toString();
	}

	private static double inSection(double v) {
		return v - 16 * Math.floor(v / 16);
	}

	private static double alignTo(double abs0, double from, double want) {
		return from + inSection(want - inSection(abs0 + from));
	}

	private static void assertSame(GameTestHelper h, Pose p, double[] v, Pose q, double[] w, double tol, String what) {
		double[] da = {p.x(), p.y(), p.z(), p.qw(), p.qx(), p.qy(), p.qz(), v[0], v[1], v[2], v[3], v[4], v[5]};
		double[] db = {q.x(), q.y(), q.z(), q.qw(), q.qx(), q.qy(), q.qz(), w[0], w[1], w[2], w[3], w[4], w[5]};
		double worst = 0;
		for (int i = 0; i < da.length; i++) {
			double d = Math.abs(da[i] - db[i]);
			worst = Math.max(worst, d);
			h.assertTrue(d <= tol, what + ": component " + i + " differs by " + d + ": " + da[i] + " vs " + db[i]);
		}
		LOG.info("{}: largest difference {}", what, worst);
	}

	@GameTest(environment = "mcrl-test:restart_rebuilds_from_entities", structure = RUNWAY, maxTicks = 400)
	public void restartRebuildsFromEntities(GameTestHelper h) {
		clean(h);
		terrainKeepsUp(h);
		ServerLevel level = h.getLevel();
		SimHosts.Restarted none = SimHosts.restart(level);
		h.assertTrue(none.cars() == 0 && !none.ball(), "bodies left before the test: " + none);
		h.assertTrue(SimHosts.get(level.dimension()) == null, "a host survived a restart with no body");
		fill(h, 0, 0, 0, 47, 0, 15, Blocks.STONE);
		Vec3 a0 = h.absoluteVec(Vec3.ZERO);
		double rx = alignTo(a0.x, 3.0, 1.5);
		double rz = 6.0;
		while (rz < 15.0 && !(inSection(a0.z + rz) >= 1.0 && inSection(a0.z + rz) <= 9.0)) {
			rz += 0.5;
		}
		double ballRz = rz - 4;
		LOG.info("restart_rebuilds_from_entities: car {} into its section in x, {} in z", inSection(a0.x + rx),
				inSection(a0.z + rz));
		CarEntity car = spawnCar(h, rx, 1.34, rz, EAST);
		BallEntity ball = McrlEntities.BALL.create(level, EntitySpawnReason.COMMAND);
		h.assertTrue(ball != null, "could not create a BallEntity");
		Vec3 b = h.absoluteVec(new Vec3(rx + 2, 1.0 + BallEntity.RADIUS + 0.05, ballRz));
		ball.setSimState(new BallState(new Pose(b.x, b.y, b.z, 1, 0, 0, 0), 2.0, 0, 0, 0, 0, 0));
		h.assertTrue(level.addFreshEntity(ball), "the ball was not added");
		long t0 = simTick(h);
		SimHost[] old = {null};
		CarState[] carBefore = {null};
		BallState[] ballBefore = {null};
		long[] t1 = {0};
		double[] x1 = {0};
		int[] seq = {0};
		h.startSequence()
				.thenWaitUntil(() -> assertRunning(h, car, t0))
				.thenExecuteFor(5, () -> host(h).submitInput(car.getId(), drive(++seq[0], 1f, false)))
				.thenExecute(() -> {
					old[0] = host(h);
					carBefore[0] = car.simState();
					ballBefore[0] = ball.simState();
					h.assertTrue(speed(carBefore[0]) > 2, "the car is not moving: " + speed(carBefore[0]) + " b/s");
					BallState bs = ballBefore[0];
					double ballSpeed = Math.sqrt(bs.vx() * bs.vx() + bs.vy() * bs.vy() + bs.vz() * bs.vz());
					h.assertTrue(ballSpeed > 0.5, "the ball is not moving: " + ballSpeed + " b/s");
					List<String> out = run(h, "mcrl physics restart");
					LOG.info("restart_rebuilds_from_entities: {}", out);
					String want = "Physics restarted in " + dim(h) + ": 1 car(s), ball yes";
					h.assertTrue(out.contains(want), "restart said " + out + ", want '" + want + "'");
					SimHost nu = host(h);
					h.assertTrue(nu != old[0], "the level's host is the same instance after restart");
					h.assertTrue(old[0].isClosed(), "the old host is not closed");
					nu.flush();
					SimFrame f = nu.latest();
					SimFrame.CarSnap c = f.car(car.getId());
					h.assertTrue(c != null, "the car is not in the new host");
					h.assertTrue(f.ball() != null, "the ball is not in the new host");
					CarState cb = carBefore[0];
					BallState bb = ballBefore[0];
					assertSame(h, cb.pose(), new double[] {cb.vx(), cb.vy(), cb.vz(), cb.wx(), cb.wy(), cb.wz()},
							c.cur(), new double[] {c.vx(), c.vy(), c.vz(), c.wx(), c.wy(), c.wz()}, 1e-6,
							"car: entity before vs new host");
					SimFrame.BallSnap s = f.ball();
					assertSame(h, bb.pose(), new double[] {bb.vx(), bb.vy(), bb.vz(), bb.wx(), bb.wy(), bb.wz()},
							s.cur(), new double[] {s.vx(), s.vy(), s.vz(), s.wx(), s.wy(), s.wz()}, 1e-6,
							"ball: entity before vs new host");
					h.assertTrue((f.flags() & SimFrame.FAULT) == 0 && nu.fault().count() == 0,
							"the new host has a FAULT: " + nu.fault());
					t1[0] = f.tick();
				})
				.thenWaitUntil(() -> assertRunning(h, car, t1[0]))
				.thenExecute(() -> x1[0] = car.getX())
				.thenExecuteFor(10, () -> host(h).submitInput(car.getId(), drive(++seq[0], 1f, false)))
				.thenExecute(() -> {
					SimFrame f = host(h).latest();
					h.assertTrue((f.flags() & SimFrame.FAULT) == 0, "FAULT after the restart: flags " + f.flags());
					double moved = car.getX() - x1[0];
					LOG.info("restart_rebuilds_from_entities: driven {} blocks in 10 ticks on the new host", moved);
					h.assertTrue(moved > 1.0, "the car did not keep driving on the new host: moved " + moved);
					car.discard();
					ball.discard();
				})
				.thenSucceed();
	}

	@GameTest(environment = "mcrl-test:restart_replaces_stalled_host", structure = RUNWAY, maxTicks = 400)
	public void restartReplacesStalledHost(GameTestHelper h) {
		clean(h);
		terrainKeepsUp(h);
		fill(h, 0, 0, 0, 47, 0, 15, Blocks.STONE);
		CarEntity car = spawnCar(h, 3.0, 1.34, 8.0, EAST);
		long t0 = simTick(h);
		CountDownLatch latch = new CountDownLatch(1);
		SimHost[] old = {null};
		long[] blockedAt = {0}, oldTick = {0}, t1 = {0};
		int[] waited = {0}, seq = {0};
		double[] x1 = {0};
		h.startSequence()
				.thenWaitUntil(() -> assertRunning(h, car, t0))
				.thenExecute(() -> {
					old[0] = host(h);
					oldTick[0] = old[0].latest().tick();
					blockedAt[0] = h.getTick();
					old[0].runOnSimThread(() -> {
						try {
							latch.await(30, TimeUnit.SECONDS);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						}
					});
				})
				.thenWaitUntil(() -> {
					if (++waited[0] <= 20) {
						h.assertTrue((old[0].latest().flags() & SimFrame.STALLED) != 0, "not STALLED yet");
					}
				})
				.thenExecute(() -> {
					long ticks = h.getTick() - blockedAt[0];
					SimFrame f = old[0].latest();
					h.assertTrue((f.flags() & SimFrame.STALLED) != 0, "not STALLED " + ticks + " server ticks after the "
							+ "sim thread got stuck: flags " + f.flags());
					LOG.info("restart_replaces_stalled_host: STALLED after {} server ticks, stalled for {} ms", ticks,
							old[0].stalledNanos(System.nanoTime()) / 1_000_000);
					List<String> status = status(h);
					LOG.info("restart_replaces_stalled_host: status {}", status);
					h.assertTrue(status.size() == 1 && status.get(0).startsWith(dim(h) + ": stalled "),
							"status while stuck: " + status);
					long n0 = System.nanoTime();
					List<String> out = run(h, "mcrl physics restart");
					long took = System.nanoTime() - n0;
					LOG.info("restart_replaces_stalled_host: restart took {} ms: {}", took / 1_000_000, out);
					h.assertTrue(took < 200_000_000L, "restart waited for the stuck thread: " + took / 1_000_000 + " ms");
					h.assertTrue(out.contains("Physics restarted in " + dim(h) + ": 1 car(s), ball no"), "restart said " + out);
					SimHost nu = host(h);
					h.assertTrue(nu != old[0], "the stuck host is still the level's host");
					h.assertTrue(old[0].isClosed() && !old[0].isTerminated(), "the stuck host was not abandoned");
					nu.flush();
					t1[0] = nu.latest().tick();
				})
				.thenWaitUntil(() -> assertRunning(h, car, t1[0]))
				.thenExecute(() -> x1[0] = car.getX())
				.thenExecuteFor(10, () -> host(h).submitInput(car.getId(), drive(++seq[0], 1f, false)))
				.thenExecute(() -> {
					double moved = car.getX() - x1[0];
					LOG.info("restart_replaces_stalled_host: the new host drove the car {} blocks in 10 ticks", moved);
					h.assertTrue(moved > 1.0, "the new host does not drive the car: moved " + moved);
					SimHost nu = host(h);
					nu.flush();
					SimFrame nuBefore = nu.latest();
					int nuSections = nu.sectionCount();
					latch.countDown();
					long n0 = System.nanoTime();
					while (!old[0].isTerminated() && System.nanoTime() - n0 < 2_000_000_000L) {
						try {
							Thread.sleep(5);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
							break;
						}
					}
					long took = System.nanoTime() - n0;
					LOG.info("restart_replaces_stalled_host: the released old thread ended after {} ms", took / 1_000_000);
					h.assertTrue(old[0].isTerminated(), "the released old thread did not end within 2 s");
					h.assertTrue(old[0].latest().tick() == oldTick[0] + STEP, "the old thread's late step job did not "
							+ "publish to its own frame: tick " + old[0].latest().tick() + ", was " + oldTick[0]);
					h.assertTrue(old[0].sectionCount() == 0, "the old world was not destroyed");
					h.assertTrue(host(h) == nu, "the level's host changed when the old thread ended");
					h.assertTrue(nu.latest() == nuBefore, "the new host's frame changed while the old thread finished");
					h.assertTrue(nu.sectionCount() == nuSections, "the new host's sections changed: " + nuSections + " -> "
							+ nu.sectionCount());
					t1[0] = nuBefore.tick();
				})
				.thenExecuteFor(3, () -> host(h).submitInput(car.getId(), drive(++seq[0], 1f, false)))
				.thenExecute(() -> {
					SimFrame f = host(h).latest();
					h.assertTrue(f.tick() == t1[0] + 3 * STEP, "the new host did not step on: tick " + f.tick() + ", was "
							+ t1[0]);
					h.assertTrue((f.flags() & (SimFrame.FAULT | SimFrame.FROZEN | SimFrame.STALLED)) == 0,
							"flags after the old thread ended: " + f.flags());
					h.assertTrue(f.car(car.getId()) != null, "the car left the new host");
					car.discard();
				})
				.thenSucceed();
	}
}
