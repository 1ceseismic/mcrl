package dev.mcrl.test;

import static dev.mcrl.test.GameTestSupport.EAST;
import static dev.mcrl.test.GameTestSupport.RUNWAY;
import static dev.mcrl.test.GameTestSupport.clean;
import static dev.mcrl.test.GameTestSupport.fill;
import static dev.mcrl.test.GameTestSupport.spawnCar;
import static dev.mcrl.test.GameTestSupport.terrainKeepsUp;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.entity.CarEntity;
import dev.mcrl.entity.McrlEntities;
import dev.mcrl.net.McrlPayloads.FxEventS2C;

public class OwnershipGameTests {
	private static ServerPlayer player(GameTestHelper h, double rx, double rz) {
		ServerPlayer p = h.makeMockServerPlayerInLevel();
		Vec3 a = h.absoluteVec(new Vec3(rx, 1.0, rz));
		p.snapTo(a.x, a.y, a.z, EAST, 0f);
		return p;
	}

	private static void floor(GameTestHelper h) {
		clean(h);
		terrainKeepsUp(h);
		fill(h, 0, 0, 0, 47, 0, 15, Blocks.STONE);
	}

	private static List<String> run(GameTestHelper h, CommandSourceStack src, String command) {
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
		h.getLevel().getServer().getCommands().performPrefixedCommand(src.withSource(capture), command);
		return out;
	}

	private static List<String> runAs(GameTestHelper h, ServerPlayer p, String command) {
		return run(h, p.createCommandSourceStack(), command);
	}

	private static List<CarEntity> ownedBy(GameTestHelper h, UUID owner) {
		return List.copyOf(h.getLevel().getEntities(McrlEntities.CAR, c -> !c.isRemoved() && owner.equals(c.owner())));
	}

	@GameTest(environment = "mcrl-test:owner_replaces", structure = RUNWAY, maxTicks = 60)
	public void carCommandReplacesOwnCar(GameTestHelper h) {
		floor(h);
		ServerPlayer p = player(h, 4.0, 8.0);
		List<FxEventS2C> events = new CopyOnWriteArrayList<>();
		Taps.fxEvents = events::add;
		runAs(h, p, "mcrl car");
		List<CarEntity> first = ownedBy(h, p.getUUID());
		h.assertTrue(first.size() == 1, "after the first /mcrl car: " + first.size() + " car(s) owned");
		CarEntity one = first.get(0);
		p.snapTo(p.getX() + 10, p.getY(), p.getZ(), EAST, 0f);
		runAs(h, p, "mcrl car");
		List<CarEntity> now = ownedBy(h, p.getUUID());
		h.assertTrue(now.size() == 1 && now.get(0) != one, "after the second: " + now);
		h.assertTrue(one.isRemoved(), "the first car is still there");
		h.assertTrue(events.stream().anyMatch(e -> e.entityId() == one.getId() && e.kind() == FxEventS2C.Kind.CAR_LEFT),
				"no CAR_LEFT for the first car: " + events);
		Taps.fxEvents = null;
		now.get(0).discard();
		h.succeed();
	}

	@GameTest(environment = "mcrl-test:owner_others_stay", structure = RUNWAY, maxTicks = 60)
	public void otherPlayersCarsStay(GameTestHelper h) {
		floor(h);
		ServerPlayer p = player(h, 4.0, 4.0), q = player(h, 4.0, 12.0);
		runAs(h, p, "mcrl car");
		runAs(h, q, "mcrl car");
		List<CarEntity> qs = ownedBy(h, q.getUUID());
		h.assertTrue(qs.size() == 1, "Q owns " + qs.size() + " car(s)");
		p.snapTo(p.getX() + 10, p.getY(), p.getZ(), EAST, 0f);
		runAs(h, p, "mcrl car");
		h.assertTrue(!qs.get(0).isRemoved(), "P's /mcrl car removed Q's car");
		h.assertTrue(ownedBy(h, q.getUUID()).size() == 1 && ownedBy(h, p.getUUID()).size() == 1, "one car each");
		ownedBy(h, p.getUUID()).forEach(CarEntity::discard);
		qs.get(0).discard();
		h.succeed();
	}

	@GameTest(environment = "mcrl-test:owner_unowned_stays", structure = RUNWAY, maxTicks = 1400)
	public void unownedCarNeverDespawns(GameTestHelper h) {
		floor(h);
		CarEntity car = spawnCar(h, 8.0, 1.34, 8.0, EAST);
		h.assertTrue(car.owner() == null, "a spawned car has an owner");
		h.startSequence()
				.thenIdle(1300)
				.thenExecute(() -> {
					h.assertTrue(!car.isRemoved(), "an unowned car despawned");
					car.discard();
				})
				.thenSucceed();
	}

	@GameTest(environment = "mcrl-test:owner_restart_permission", structure = RUNWAY, maxTicks = 40)
	public void restartNeedsGamemaster(GameTestHelper h) {
		floor(h);
		CarEntity car = spawnCar(h, 8.0, 1.34, 8.0, EAST);
		MinecraftServer server = h.getLevel().getServer();
		CommandSourceStack base = server.createCommandSourceStack().withLevel(h.getLevel());
		h.startSequence()
				.thenIdle(2)
				.thenExecute(() -> {
					List<String> denied = run(h, base.withPermission(PermissionSet.NO_PERMISSIONS), "mcrl physics restart");
					h.assertTrue(denied.stream().noneMatch(l -> l.startsWith("Physics restarted")), "level 0 restarted: "
							+ denied);
					List<String> ok = run(h, base.withPermission(LevelBasedPermissionSet.GAMEMASTER), "mcrl physics restart");
					h.assertTrue(ok.stream().anyMatch(l -> l.startsWith("Physics restarted")), "level 2 did not: " + ok);
					car.discard();
				})
				.thenSucceed();
	}
}
