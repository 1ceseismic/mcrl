package dev.mcrl.test;

import static dev.mcrl.test.GameTestSupport.EAST;
import static dev.mcrl.test.GameTestSupport.RUNWAY;
import static dev.mcrl.test.GameTestSupport.clean;
import static dev.mcrl.test.GameTestSupport.fill;
import static dev.mcrl.test.GameTestSupport.host;
import static dev.mcrl.test.GameTestSupport.spawnCar;
import static dev.mcrl.test.GameTestSupport.terrainKeepsUp;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.entity.CarEntity;
import dev.mcrl.entity.McrlEntities;
import dev.mcrl.math.OrientedBox;
import dev.mcrl.math.Pose;
import dev.mcrl.net.McrlPayloads;
import dev.mcrl.net.McrlPayloads.FxEventS2C;
import dev.mcrl.sim.CarState;
import dev.mcrl.sim.SimFrame;

public class DemolitionGameTests {
	private static final int RESPAWN_SERVER_TICKS = 61;

	private static List<FxEventS2C> listen() {
		List<FxEventS2C> out = new CopyOnWriteArrayList<>();
		Taps.fxEvents = out::add;
		return out;
	}

	private static List<FxEventS2C> of(List<FxEventS2C> events, int entityId, FxEventS2C.Kind kind) {
		return events.stream().filter(e -> e.entityId() == entityId && e.kind() == kind).toList();
	}

	private static CarEntity restingCar(GameTestHelper h) {
		clean(h);
		terrainKeepsUp(h);
		fill(h, 0, 0, 0, 47, 0, 15, Blocks.STONE);
		return spawnCar(h, 8.0, 1.34, 8.0, EAST);
	}

	private static double dist(Pose a, Pose b) {
		double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private static Vec3 centre(CarEntity car) {
		OrientedBox b = OrientedBox.car(car.pose(), car.hitboxPreset());
		return new Vec3(b.cx(), b.cy(), b.cz());
	}

	private static void explodeAt(GameTestHelper h, Vec3 at, float r) {
		explodeAt(h, at, r, Level.ExplosionInteraction.NONE);
	}

	private static void explodeAt(GameTestHelper h, Vec3 at, float r, Level.ExplosionInteraction interaction) {
		h.getLevel().explode(null, at.x, at.y, at.z, r, interaction);
	}

	@GameTest(environment = "mcrl-test:demo_explosion_within", structure = RUNWAY, maxTicks = 120)
	public void explosionWithinRadiusDemolishes(GameTestHelper h) {
		CarEntity car = restingCar(h);
		List<FxEventS2C> events = listen();
		Pose[] at = {null};
		h.startSequence()
				.thenIdle(5)
				.thenExecute(() -> {
					at[0] = car.pose();
					explodeAt(h, centre(car).add(0, 0, 2), 4f);
				})
				.thenIdle(2)
				.thenExecute(() -> {
					List<FxEventS2C> d = of(events, car.getId(), FxEventS2C.Kind.CAR_DEMOLISHED);
					h.assertTrue(d.size() == 1, "CAR_DEMOLISHED events: " + events);
					h.assertTrue(d.get(0).attackerId() == -1, "attacker " + d.get(0).attackerId());
					h.assertTrue(dist(d.get(0).pose(), at[0]) <= 0.01, "demolished at " + d.get(0).pose());
					h.assertTrue(!car.isRemoved(), "the car was removed");
					h.assertTrue(car.isDemoed(), "the car is not demoed");
					h.assertTrue(!car.isPickable(), "a wreck is pickable");
					ServerPlayer p = h.makeMockServerPlayerInLevel();
					h.assertTrue(car.interact(p, InteractionHand.MAIN_HAND, p.position()) == InteractionResult.PASS,
							"a wreck took a right-click");
					h.assertTrue(p.getVehicle() == null, "the player got into a wreck");
				})
				.thenIdle(RESPAWN_SERVER_TICKS)
				.thenExecute(() -> {
					h.assertTrue(of(events, car.getId(), FxEventS2C.Kind.CAR_SPAWNED).size() == 1, "respawn: " + events);
					h.assertTrue(!car.isDemoed(), "still demoed");
					Taps.fxEvents = null;
					car.discard();
				})
				.thenSucceed();
	}

	private static GameTestSequence voidWreck(GameTestHelper h, CarEntity car, ServerPlayer rider, Pose[] safe) {
		rider.snapTo(car.getX(), car.getY() + 1, car.getZ(), car.getYRot(), 0f);
		h.assertTrue(rider.startRiding(car, true, true), "the mock player could not get in");
		int minY = h.getLevel().getMinY();
		return h.startSequence()
				.thenIdle(25)
				.thenExecute(() -> {
					safe[0] = car.pose();
					host(h).setCar(car.carId(), CarState.of(new Pose(car.getX(), minY - 5, car.getZ(), 1, 0, 0, 0), 0, 0,
							0, 0, 0, 0));
				})
				.thenIdle(2)
				.thenExecute(() -> {
					h.assertTrue(car.isDemoed(), "not demolished in the void");
					h.assertTrue(car.pose().y() < minY - 4, "the wreck is at " + car.pose());
				});
	}

	@GameTest(environment = "mcrl-test:demo_void_saved_at_safe_pose", structure = RUNWAY, maxTicks = 80)
	public void voidWreckSavesAtItsRespawn(GameTestHelper h) {
		CarEntity car = restingCar(h);
		ServerPlayer p = h.makeMockServerPlayerInLevel();
		Pose[] safe = {null};
		voidWreck(h, car, p, safe)
				.thenExecute(() -> {
					TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING,
							h.getLevel().registryAccess());
					car.saveWithoutId(out);
					CarEntity copy = McrlEntities.CAR.create(h.getLevel(), EntitySpawnReason.LOAD);
					h.assertTrue(copy != null, "could not create a CarEntity");
					copy.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(),
							out.buildResult()));
					CarState s = copy.pendingState();
					h.assertTrue(s != null, "no state loaded");
					h.assertTrue(dist(s.pose(), safe[0]) <= 0.05, "saved at " + s.pose() + ", safe pose " + safe[0]);
					h.assertTrue(s.vx() == 0 && s.vy() == 0 && s.vz() == 0, "saved moving: " + s);
					p.stopRiding();
					car.discard();
				})
				.thenSucceed();
	}

	@GameTest(environment = "mcrl-test:demo_void_restart", structure = RUNWAY, maxTicks = 100)
	public void restartDuringVoidDemoReentersAtTheSafePose(GameTestHelper h) {
		CarEntity car = restingCar(h);
		ServerPlayer p = h.makeMockServerPlayerInLevel();
		int minY = h.getLevel().getMinY();
		Pose[] safe = {null};
		voidWreck(h, car, p, safe)
				.thenExecute(() -> {
					MinecraftServer server = h.getLevel().getServer();
					server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(h.getLevel())
							.withPermission(LevelBasedPermissionSet.GAMEMASTER), "mcrl physics restart");
					h.assertTrue(!car.isDemoed(), "still demoed after the restart");
					h.assertTrue(dist(car.pose(), safe[0]) <= 0.05, "re-entered at " + car.pose() + ", safe pose "
							+ safe[0]);
				})
				.thenExecuteFor(20, () -> h.assertTrue(car.getY() >= minY, "below minY: " + car.pose()))
				.thenExecute(() -> {
					SimFrame.CarSnap c = host(h).latest().car(car.carId());
					h.assertTrue(c != null && !c.demoed(), "the sim car: " + c);
					h.assertTrue(dist(c.cur(), safe[0]) <= 0.1, "the sim car is at " + c.cur() + ", safe pose " + safe[0]);
					h.assertTrue(p.getVehicle() == car, "the driver no longer rides the car");
					p.stopRiding();
					car.discard();
				})
				.thenSucceed();
	}

	@GameTest(environment = "mcrl-test:demo_reset_ignored", structure = RUNWAY, maxTicks = 60)
	public void resetIgnoredWhileDemoed(GameTestHelper h) {
		CarEntity car = restingCar(h);
		ServerPlayer p = h.makeMockServerPlayerInLevel();
		p.snapTo(car.getX(), car.getY() + 1, car.getZ(), car.getYRot(), 0f);
		h.assertTrue(p.startRiding(car, true, true), "the mock player could not get in");
		List<FxEventS2C> events = listen();
		Pose[] wreck = {null};
		h.startSequence()
				.thenIdle(5)
				.thenExecute(() -> host(h).demolish(car.carId(), -1))
				.thenIdle(2)
				.thenExecute(() -> {
					h.assertTrue(car.isDemoed(), "the car is not demoed");
					wreck[0] = car.pose();
					events.clear();
					McrlPayloads.handleReset(p);
				})
				.thenIdle(2)
				.thenExecute(() -> {
					h.assertTrue(of(events, car.getId(), FxEventS2C.Kind.CAR_SPAWNED).isEmpty(), "reset spawned: " + events);
					h.assertTrue(dist(car.pose(), wreck[0]) == 0, "the wreck moved: " + car.pose());
					h.assertTrue(car.isDemoed(), "the reset undid the demo");
					Taps.fxEvents = null;
					p.stopRiding();
					car.discard();
				})
				.thenSucceed();
	}
}
