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
import static dev.mcrl.test.GameTestSupport.topOf;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.entity.CarEntity;
import dev.mcrl.entity.McrlEntities;
import dev.mcrl.math.OrientedBox;
import dev.mcrl.math.Pose;
import dev.mcrl.net.McrlPayloads;
import dev.mcrl.sim.CarState;
import dev.mcrl.sim.HitboxPreset;
import dev.mcrl.sim.SimFrame;
import dev.mcrl.test.GameTestSupport.Window;

@SuppressWarnings("removal")
public class CarGameTests {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl-test");

	@GameTest(environment = "mcrl-test:car_rests_on_platform", maxTicks = 200)
	public void carRestsOnPlatform(GameTestHelper h) {
		clean(h);
		terrainKeepsUp(h);
		fill(h, 1, 0, 1, 5, 0, 5, Blocks.STONE);
		double top = topOf(h, 0);
		CarEntity car = spawnCar(h, 3.5, 1.0 + 1.5, 3.5, EAST);
		long t0 = simTick(h);
		Window w = new Window();
		h.startSequence()
				.thenWaitUntil(() -> assertRunning(h, car, t0))
				.thenExecuteFor(40, () -> w.sample(h))
				.thenExecute(() -> {
					w.assertNeverFroze(h);
					double dy = car.getY() - top;
					LOG.info("car_rests_on_platform: car y - platform top = {}", dy);
					h.assertTrue(Math.abs(dy - 0.34) <= 0.03, "car rests " + dy + " above the platform (want 0.34 +- 0.03)");
					car.discard();
				})
				.thenSucceed();
	}

	@GameTest(environment = "mcrl-test:spawn_clear_of_fence", maxTicks = 100)
	public void spawnClearOfFence(GameTestHelper h) {
		clean(h);
		fill(h, 0, 0, 0, 7, 0, 7, Blocks.STONE);
		fill(h, 4, 1, 0, 4, 1, 7, Blocks.OAK_FENCE);
		ServerPlayer p = h.makeMockServerPlayerInLevel();
		place(h, p, 1.0, 1.0, 4.0, EAST);
		List<Component> out = runCommand(h, p, "mcrl car");
		List<CarEntity> near = carsNear(h, p);
		LOG.info("spawn_clear_of_fence: {}", near.isEmpty() ? "no room"
				: "car at " + (near.get(0).getX() - p.getX()) + " ahead, y +" + (near.get(0).getY() - p.getY()));
		if (near.isEmpty()) {
			h.assertTrue(hasKey(out, "mcrl.command.no_room"), "no car and no 'No room' message: " + out);
		}
		for (CarEntity c : near) {
			assertClearOfBlocks(h, c);
			c.discard();
		}
		h.succeed();
	}

	@GameTest(environment = "mcrl-test:exit_neutralises_controls", structure = RUNWAY, maxTicks = 400)
	public void exitNeutralisesControls(GameTestHelper h) {
		clean(h);
		terrainKeepsUp(h);
		fill(h, 0, 0, 0, 47, 0, 15, Blocks.STONE);
		CarEntity car = spawnCar(h, 3.0, 1.34, 8.0, EAST);
		long t0 = simTick(h);
		ServerPlayer p = h.makeMockServerPlayerInLevel();
		p.snapTo(car.getX(), car.getY() + 1, car.getZ() - 2, EAST, 0f);
		h.assertTrue(p.startRiding(car), "the mock player could not get in");
		h.assertTrue(car.getControllingPassenger() == p, "the rider is not the car's controlling passenger");
		double[] v = new double[2];
		int[] seq = {0};
		Window w = new Window();
		h.startSequence()
				.thenWaitUntil(() -> assertRunning(h, car, t0))
				.thenExecuteFor(20, () -> {
					w.sample(h);
					host(h).submitInput(car.getId(), drive(++seq[0], 1f, false));
				})
				.thenExecute(() -> {
					v[0] = speed(car.simState());
					v[1] = v[0];
					McrlPayloads.handleExit(p);
					h.assertFalse(p.isPassenger(), "still riding after ExitCarC2S");
				})
				.thenExecuteFor(40, () -> {
					w.sample(h);
					v[1] = Math.max(v[1], speed(car.simState()));
				})
				.thenExecute(() -> {
					w.assertNeverFroze(h);
					double end = speed(car.simState());
					LOG.info("exit_neutralises_controls: speed at exit {} b/s, max after {}, after 40 ticks {}", v[0], v[1],
							end);
					h.assertTrue(v[0] > 5, "the car was too slow at exit to tell: " + v[0] + " b/s");
					h.assertTrue(v[1] <= v[0] + 0.05, "the car sped up after exit: " + v[1] + " > " + v[0]);
					h.assertTrue(end < 0.5 * v[0], "the car did not slow down: " + end + " vs " + v[0] + " at exit");
					car.discard();
				})
				.thenSucceed();
	}

	@GameTest(environment = "mcrl-test:car_save_roundtrip", structure = RUNWAY, maxTicks = 300)
	public void carSaveRoundtrip(GameTestHelper h) {
		clean(h);
		terrainKeepsUp(h);
		fill(h, 0, 0, 0, 47, 0, 15, Blocks.STONE);
		CarEntity car = spawnCar(h, 3.0, 1.34, 8.0, EAST);
		long t0 = simTick(h);
		CarEntity[] loaded = {null};
		int[] seq = {0};
		Window w = new Window();
		h.startSequence()
				.thenWaitUntil(() -> assertRunning(h, car, t0))
				.thenExecuteFor(15, () -> {
					w.sample(h);
					host(h).submitInput(car.getId(), drive(++seq[0], 1f, false));
				})
				.thenExecute(() -> {
					w.assertNeverFroze(h);
					ServerLevel level = h.getLevel();
					CarState s = car.simState();
					h.assertTrue(speed(s) > 2, "the car is not moving: " + speed(s) + " b/s");

					TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
					car.saveWithoutId(out);
					CompoundTag tag = out.buildResult();
					h.assertTrue(tag.contains("mcrl_pose") && tag.contains("mcrl_vel") && tag.contains("mcrl_angvel"),
							"save data lacks mcrl_pose/mcrl_vel/mcrl_angvel: " + tag.keySet());

					CarEntity copy = McrlEntities.CAR.create(level, EntitySpawnReason.LOAD);
					h.assertTrue(copy != null, "could not create a CarEntity");
					copy.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
					CarState p = copy.pendingState();
					h.assertTrue(p != null, "no pending state after load");
					assertSameState(h, s, p, 1e-6, 1e-6, "saved vs loaded");

					car.discard();
					h.assertTrue(level.addFreshEntity(copy), "the loaded car was not added");
					host(h).flush();
					SimFrame.CarSnap snap = host(h).latest().car(copy.getId());
					h.assertTrue(snap != null, "the loaded car is not in the sim");
					assertSameState(h, p, CarState.of(snap.cur(), snap.vx(), snap.vy(), snap.vz(), snap.wx(), snap.wy(),
							snap.wz()), 1e-4, 1e-3, "loaded vs sim");
					loaded[0] = copy;
				})
				.thenExecute(() -> {
					if (loaded[0] != null) {
						loaded[0].discard();
					}
				})
				.thenSucceed();
	}

	private static void assertSameState(GameTestHelper h, CarState a, CarState b, double posTol, double velTol,
			String what) {
		Pose p = a.pose(), q = b.pose();
		double[] da = {p.x(), p.y(), p.z(), p.qw(), p.qx(), p.qy(), p.qz(), a.vx(), a.vy(), a.vz(), a.wx(), a.wy(), a.wz()};
		double[] db = {q.x(), q.y(), q.z(), q.qw(), q.qx(), q.qy(), q.qz(), b.vx(), b.vy(), b.vz(), b.wx(), b.wy(), b.wz()};
		for (int i = 0; i < da.length; i++) {
			double tol = i < 7 ? posTol : velTol;
			h.assertTrue(Math.abs(da[i] - db[i]) <= tol, what + ": component " + i + " differs: " + da[i] + " vs " + db[i]);
		}
	}

	private static void place(GameTestHelper h, ServerPlayer p, double rx, double ry, double rz, float yaw) {
		Vec3 a = h.absoluteVec(new Vec3(rx, ry, rz));
		p.snapTo(a.x, a.y, a.z, yaw, 0f);
	}

	private static List<Component> runCommand(GameTestHelper h, ServerPlayer p, String command) {
		List<Component> out = new ArrayList<>();
		CommandSource capture = new CommandSource() {
			@Override
			public void sendSystemMessage(Component c) {
				out.add(c);
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
		h.getLevel().getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack().withSource(capture),
				command);
		return out;
	}

	private static boolean hasKey(List<Component> msgs, String key) {
		for (Component c : msgs) {
			if (hasKey(c, key)) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasKey(Component c, String key) {
		if (c.getContents() instanceof TranslatableContents t && t.getKey().equals(key)) {
			return true;
		}
		for (Component s : c.getSiblings()) {
			if (hasKey(s, key)) {
				return true;
			}
		}
		return false;
	}

	private static List<CarEntity> carsNear(GameTestHelper h, ServerPlayer p) {
		return h.getLevel().getEntitiesOfClass(CarEntity.class, p.getBoundingBox().inflate(8), c -> !c.isRemoved());
	}

	private static void assertClearOfBlocks(GameTestHelper h, CarEntity car) {
		ServerLevel level = h.getLevel();
		OrientedBox box = OrientedBox.car(car.simState().pose(), HitboxPreset.OCTANE);
		double[] bb = box.aabb();
		for (BlockPos pos : BlockPos.betweenClosed(Mth.floor(bb[0]) - 1, Mth.floor(bb[1]) - 1, Mth.floor(bb[2]) - 1,
				Mth.floor(bb[3]) + 1, Mth.floor(bb[4]) + 1, Mth.floor(bb[5]) + 1)) {
			for (AABB a : level.getBlockState(pos).getCollisionShape(level, pos).toAabbs()) {
				AABB w = a.move(pos.getX(), pos.getY(), pos.getZ()).deflate(1e-6);
				if (box.intersects(w.minX, w.minY, w.minZ, w.maxX, w.maxY, w.maxZ)) {
					throw h.assertionException("the car's hitbox overlaps the block at " + pos.immutable() + " ("
							+ level.getBlockState(pos) + ")");
				}
			}
		}
	}
}
