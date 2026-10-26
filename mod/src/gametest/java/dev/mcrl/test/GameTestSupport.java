package dev.mcrl.test;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.McrlGameRules;
import dev.mcrl.entity.BallEntity;
import dev.mcrl.entity.CarEntity;
import dev.mcrl.entity.McrlEntities;
import dev.mcrl.math.Pose;
import dev.mcrl.sim.CarState;
import dev.mcrl.sim.InputFrame;
import dev.mcrl.sim.SimFrame;
import dev.mcrl.sim.SimHost;
import dev.mcrl.sim.SimHosts;
import dev.mcrl.terrain.MeshWorkers;
import dev.mcrl.terrain.ServerLevelTerrainSource;
import dev.mcrl.terrain.TerrainFeeder;

final class GameTestSupport {
	static void leave(ServerPlayer p) {
		if (p.level().getServer().getPlayerList().getPlayer(p.getUUID()) == p) {
			p.connection.onDisconnect(new DisconnectionDetails(Component.literal("test over")));
		}
	}

	private static final Logger LOG = LoggerFactory.getLogger("mcrl-test");
	static final float EAST = -90f;
	static final String RUNWAY = "mcrl-test:runway";
	static final String LONG_RUNWAY = "mcrl-test:long_runway";

	private GameTestSupport() {
	}

	static void clean(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		resetRules(h);
		int n = 0;
		for (CarEntity c : List.copyOf(level.getEntities(McrlEntities.CAR, e -> !e.isRemoved()))) {
			c.discard();
			n++;
		}
		for (BallEntity b : List.copyOf(level.getEntities(McrlEntities.BALL, e -> !e.isRemoved()))) {
			b.discard();
			n++;
		}
		SimHost host = SimHosts.get(level.dimension());
		if (n > 0 && host != null) {
			LOG.warn("cleaned up {} cars/balls an earlier test left behind", n);
			host.stepLockstep(1);
			TerrainFeeder.feed(host, new ServerLevelTerrainSource(level, MeshWorkers.registry()), 0);
		}
	}

	static void resetRules(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		server.getGameRules().set(McrlGameRules.STEP_UP, McrlGameRules.STEP_UP.defaultValue(), server);
		server.getGameRules().set(McrlGameRules.CAR_DAMAGE, McrlGameRules.CAR_DAMAGE.defaultValue(), server);
		server.getGameRules().set(McrlGameRules.CAR_DEMOS, McrlGameRules.CAR_DEMOS.defaultValue(), server);
		server.getGameRules().set(McrlGameRules.EXPLOSION_DEMOS, McrlGameRules.EXPLOSION_DEMOS.defaultValue(), server);
		server.getGameRules().set(McrlGameRules.SURFACE_PHYSICS, McrlGameRules.SURFACE_PHYSICS.defaultValue(), server);
		server.getGameRules().set(McrlGameRules.FLUID_PHYSICS, McrlGameRules.FLUID_PHYSICS.defaultValue(), server);
		server.getGameRules().set(McrlGameRules.BALL_HITS_ENTITIES, McrlGameRules.BALL_HITS_ENTITIES.defaultValue(), server);
	}

	static void terrainKeepsUp(GameTestHelper h) {
		h.onEachTick(() -> {
			if (!MeshWorkers.awaitIdle(5_000_000_000L)) {
				throw h.assertionException("the mesh workers did not finish within 5 s");
			}
		});
	}

	static final class Window {
		int ticks, frozen;

		void sample(GameTestHelper h) {
			ticks++;
			if ((host(h).latest().flags() & SimFrame.FROZEN) != 0) {
				frozen++;
			}
		}

		void assertNeverFroze(GameTestHelper h) {
			h.assertTrue(frozen == 0, "the sim was FROZEN on " + frozen + " of " + ticks + " ticks of the window");
		}
	}

	static SimHost host(GameTestHelper h) {
		SimHost host = SimHosts.get(h.getLevel().dimension());
		if (host == null) {
			throw h.assertionException("no SimHost for the test level");
		}
		return host;
	}

	static void fill(GameTestHelper h, int x0, int y0, int z0, int x1, int y1, int z1, Block b) {
		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				for (int z = z0; z <= z1; z++) {
					h.setBlock(x, y, z, b);
				}
			}
		}
	}

	static double topOf(GameTestHelper h, int relY) {
		return h.absolutePos(new BlockPos(0, relY, 0)).getY() + 1.0;
	}

	static CarEntity spawnCar(GameTestHelper h, double rx, double ry, double rz, float yaw) {
		Vec3 a = h.absoluteVec(new Vec3(rx, ry, rz));
		CarEntity car = McrlEntities.CAR.create(h.getLevel(), EntitySpawnReason.COMMAND);
		if (car == null) {
			throw h.assertionException("could not create a CarEntity");
		}
		car.setSimState(CarState.of(Pose.upright(a.x, a.y, a.z, yaw), 0, 0, 0, 0, 0, 0));
		if (!h.getLevel().addFreshEntity(car)) {
			throw h.assertionException("the car was not added to the level");
		}
		return car;
	}

	static void assertRunning(GameTestHelper h, CarEntity car, long t0) {
		SimFrame f = host(h).latest();
		h.assertTrue(f.car(car.getId()) != null, "the car is not in the sim yet");
		h.assertFalse((f.flags() & SimFrame.FROZEN) != 0, "FROZEN: the car's terrain is not committed yet");
		h.assertTrue(f.tick() > t0, "the sim has not stepped since the car was added");
	}

	static long simTick(GameTestHelper h) {
		host(h).flush();
		return host(h).latest().tick();
	}

	static InputFrame drive(int seq, float throttle, boolean boost) {
		int b = boost ? InputFrame.BOOST : 0;
		return InputFrame.of(seq, throttle, 0, 0, 0, 0, b, seq == 1 ? b : 0);
	}

	static double speed(CarState s) {
		return Math.sqrt(s.vx() * s.vx() + s.vy() * s.vy() + s.vz() * s.vz());
	}
}
