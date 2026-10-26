package dev.mcrl.test;

import static dev.mcrl.test.GameTestSupport.EAST;
import static dev.mcrl.test.GameTestSupport.RUNWAY;
import static dev.mcrl.test.GameTestSupport.clean;
import static dev.mcrl.test.GameTestSupport.fill;
import static dev.mcrl.test.GameTestSupport.host;
import static dev.mcrl.test.GameTestSupport.terrainKeepsUp;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.entity.CarEntity;
import dev.mcrl.entity.McrlEntities;
import dev.mcrl.math.Pose;
import dev.mcrl.sim.CarState;
import dev.mcrl.sim.HitboxPreset;
import dev.mcrl.sim.SimFrame;
import dev.mcrl.sim.SimHost;

public class CarPresetGameTests {
	static CarEntity spawnCar(GameTestHelper h, double rx, double ry, double rz, float yaw, int team, int preset) {
		Vec3 a = h.absoluteVec(new Vec3(rx, ry, rz));
		CarEntity car = McrlEntities.CAR.create(h.getLevel(), EntitySpawnReason.COMMAND);
		if (car == null) {
			throw h.assertionException("could not create a CarEntity");
		}
		car.setTeam(team);
		car.setPreset(preset);
		car.setSimState(CarState.of(Pose.upright(a.x, a.y, a.z, yaw), 0, 0, 0, 0, 0, 0));
		if (!h.getLevel().addFreshEntity(car)) {
			throw h.assertionException("the car was not added to the level");
		}
		return car;
	}

	private static double dist(Pose a, Pose b) {
		double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private static void near(GameTestHelper h, double want, double got, double tol, String what) {
		h.assertTrue(Math.abs(want - got) <= tol, what + ": " + got + ", want " + want);
	}

	private static SimFrame.CarSnap snap(GameTestHelper h, CarEntity car) {
		SimHost host = host(h);
		host.flush();
		SimFrame.CarSnap s = host.latest().car(car.carId());
		if (s == null) {
			throw h.assertionException("the car is not in the sim");
		}
		return s;
	}

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

	@GameTest(environment = "mcrl-test:team_preset_save_load", maxTicks = 20)
	public void teamPresetSurviveSaveLoad(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		CarEntity car = McrlEntities.CAR.create(level, EntitySpawnReason.COMMAND);
		h.assertTrue(car != null, "could not create a CarEntity");
		car.setTeam(1);
		car.setPreset(4);
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		car.saveWithoutId(out);
		CompoundTag tag = out.buildResult();
		h.assertTrue(tag.getIntOr("team", -1) == 1 && tag.getIntOr("preset", -1) == 4, "save data: " + tag);
		CarEntity copy = McrlEntities.CAR.create(level, EntitySpawnReason.LOAD);
		h.assertTrue(copy != null, "could not create a CarEntity");
		copy.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
		h.assertTrue(copy.team() == 1, "team " + copy.team());
		h.assertTrue(copy.preset() == 4, "preset " + copy.preset());
		h.assertTrue(copy.hitboxPreset() == HitboxPreset.HYBRID, "hitboxPreset " + copy.hitboxPreset().name());
		near(h, HitboxPreset.HYBRID.height(), copy.getBoundingBox().getYsize(), 1e-5, "loaded HYBRID y extent");
		h.succeed();
	}

	@GameTest(environment = "mcrl-test:legacy_car_octane", maxTicks = 20)
	public void legacyCarLoadsAsOctane(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		CarEntity car = McrlEntities.CAR.create(level, EntitySpawnReason.COMMAND);
		h.assertTrue(car != null, "could not create a CarEntity");
		car.setTeam(1);
		car.setPreset(4);
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		car.saveWithoutId(out);
		CompoundTag tag = out.buildResult();
		tag.remove("team");
		tag.remove("preset");
		h.assertTrue(!tag.contains("owner"), "an unowned car saved an owner: " + tag);
		CarEntity copy = McrlEntities.CAR.create(level, EntitySpawnReason.LOAD);
		h.assertTrue(copy != null, "could not create a CarEntity");
		copy.setTeam(1);
		copy.setPreset(4);
		copy.setOwner(UUID.randomUUID());
		copy.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
		h.assertTrue(copy.team() == 0, "team " + copy.team());
		h.assertTrue(copy.preset() == 0, "preset " + copy.preset());
		h.assertTrue(copy.hitboxPreset() == HitboxPreset.OCTANE, "hitboxPreset " + copy.hitboxPreset().name());
		h.assertTrue(copy.owner() == null, "owner " + copy.owner());
		h.succeed();
	}

	@GameTest(environment = "mcrl-test:restart_keeps_team_preset", structure = RUNWAY, maxTicks = 200)
	public void restartKeepsTeamAndPreset(GameTestHelper h) {
		clean(h);
		terrainKeepsUp(h);
		fill(h, 0, 0, 0, 47, 0, 15, Blocks.STONE);
		CarEntity car = spawnCar(h, 8.0, 1.34, 8.0, EAST, 1, 2);
		SimHost[] old = {null};
		Pose[] planned = {null};
		h.startSequence()
				.thenIdle(2)
				.thenExecute(() -> {
					old[0] = host(h);
					old[0].demolish(car.carId(), -1);
					SimFrame.CarSnap s = snap(h, car);
					h.assertTrue(s.demoed(), "not demoed");
					planned[0] = s.respawnAt();
					h.assertTrue(planned[0] != null, "a wreck without a planned respawn");
				})
				.thenIdle(1)
				.thenExecute(() -> {
					h.assertTrue(car.isDemoed(), "the entity does not know its car is demoed");
					List<String> out = run(h, "mcrl physics restart");
					h.assertTrue(host(h) != old[0], "the host was not replaced: " + out);
					h.assertTrue(!car.isDemoed(), "the entity still says demoed after the restart");
					SimFrame.CarSnap s = snap(h, car);
					h.assertTrue(!s.demoed(), "still demoed after the restart");
					h.assertTrue(dist(s.cur(), planned[0]) <= 0.05, "re-entered at " + s.cur() + ", planned "
							+ planned[0]);
					h.assertTrue(s.team() == 1, "team " + s.team());
					h.assertTrue(s.preset() == 2, "preset " + s.preset());
					car.discard();
				})
				.thenSucceed();
	}
}
