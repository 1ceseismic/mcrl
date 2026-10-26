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
import static dev.mcrl.test.GameTestSupport.terrainKeepsUp;
import static dev.mcrl.test.GameTestSupport.topOf;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.entity.CarEntity;
import dev.mcrl.math.OrientedBox;
import dev.mcrl.math.Pose;
import dev.mcrl.net.McrlPayloads;
import dev.mcrl.sim.HitboxPreset;
import dev.mcrl.sim.InputFrame;
import dev.mcrl.sim.CarState;
import dev.mcrl.sim.Wet;
import dev.mcrl.test.GameTestSupport.Window;

public class PassengerGameTests {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl-test");

	private static ServerPlayer seat(GameTestHelper h, CarEntity car) {
		MinecraftServer server = h.getLevel().getServer();
		GameProfile profile = new GameProfile(UUID.randomUUID(), "test-driver");
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
		ServerPlayer p = new ServerPlayer(server, h.getLevel(), profile, cookie.clientInformation());
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		new EmbeddedChannel(connection);
		server.getPlayerList().placeNewPlayer(connection, p, cookie);
		p.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
		p.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
		p.snapTo(car.getX(), car.getY() + 1, car.getZ(), car.getYRot(), 0f);
		h.assertTrue(p.startRiding(car, true, true), "the mock player could not get in");
		h.assertTrue(car.getControllingPassenger() == p, "the mock player is not driving");
		h.assertTrue(!p.isCreative() && !p.getAbilities().invulnerable, "the mock player is not a survival player");
		return p;
	}

	private static void connectionTick(ServerPlayer p) {
		double x = p.getX(), y = p.getY(), z = p.getZ();
		p.xo = x;
		p.yo = y;
		p.zo = z;
		p.doTick();
		p.absSnapTo(x, y, z, p.getYRot(), p.getXRot());
	}

	private static void everyTick(GameTestHelper h, ServerPlayer p, Runnable afterLevel, Runnable afterConnection) {
		h.onEachTick(() -> {
			if (p.isRemoved()) {
				return;
			}
			afterLevel.run();
			connectionTick(p);
			afterConnection.run();
		});
	}

	private static void assertRides(GameTestHelper h, ServerPlayer p, CarEntity car, String when) {
		h.assertTrue(p.getVehicle() == car && car.getControllingPassenger() == p, "the driver is out of the car " + when);
	}

	private static void assertUnhurt(GameTestHelper h, ServerPlayer p, float health, String when) {
		h.assertTrue(p.getHealth() == health, "the driver's health is " + p.getHealth() + ", was " + health + ", " + when);
	}

	private static void leave(GameTestHelper h, ServerPlayer p) {
		p.stopRiding();
		h.getLevel().getServer().getPlayerList().remove(p);
	}

	@GameTest(environment = "mcrl-test:driver_not_in_wall_under_block", structure = RUNWAY, maxTicks = 300)
	public void driverNotInWallUnderBlock(GameTestHelper h) {
		clean(h);
		terrainKeepsUp(h);
		fill(h, 0, 0, 0, 15, 0, 15, Blocks.SMOOTH_STONE_SLAB);
		CarEntity car = spawnCar(h, 8.0, 0.84, 8.5, EAST);
		long t0 = simTick(h);
		ServerPlayer p = seat(h, car);
		float hp = p.getHealth();
		boolean[] on = {false};
		int[] ticks = {0};
		everyTick(h, p, () -> {
		}, () -> {
			if (on[0]) {
				ticks[0]++;
				assertUnhurt(h, p, hp, "under the block, tick " + ticks[0]);
				h.assertFalse(p.isInWall(), "the driver is in a wall on tick " + ticks[0]);
				assertRides(h, p, car, "under the block");
			}
		});
		BlockPos[] eye = {null};
		h.startSequence()
				.thenWaitUntil(() -> assertRunning(h, car, t0))
				.thenExecuteAfter(5, () -> {
					eye[0] = BlockPos.containing(p.getEyePosition());
					AABB box = car.getBoundingBox();
					h.assertTrue(eye[0].getY() >= box.maxY, "the eye block " + eye[0] + " is not above the car's box " + box);
					h.getLevel().setBlock(eye[0], Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
					on[0] = true;
				})
				.thenExecuteAfter(60, () -> {
					boolean eyeInStone = h.getLevel().getBlockState(BlockPos.containing(p.getEyePosition())).is(Blocks.STONE);
					LOG.info("driver_not_in_wall_under_block: {} ticks with stone at {} (eye in it: {}), health {} of {}, "
							+ "isInWall {}", ticks[0], eye[0], eyeInStone, p.getHealth(), hp, p.isInWall());
					h.assertTrue(ticks[0] >= 60, "only " + ticks[0] + " ticks under the block");
					h.assertTrue(eyeInStone, "the driver's eye left the stone block");
					assertUnhurt(h, p, hp, "after 60 ticks");
					h.assertFalse(p.isInWall(), "the driver is in a wall after 60 ticks");
					leave(h, p);
					car.discard();
				})
				.thenSucceed();
	}

	@GameTest(environment = "mcrl-test:driver_breathes_under_water", structure = RUNWAY, maxTicks = 800)
	public void driverBreathesUnderWater(GameTestHelper h) {
		clean(h);
		terrainKeepsUp(h);
		fill(h, 0, 0, 0, 15, 0, 8, Blocks.STONE);
		fill(h, 0, 1, 0, 15, 4, 0, Blocks.GLASS);
		fill(h, 0, 1, 8, 15, 4, 8, Blocks.GLASS);
		fill(h, 0, 1, 1, 0, 4, 7, Blocks.GLASS);
		fill(h, 15, 1, 1, 15, 4, 7, Blocks.GLASS);
		fill(h, 1, 1, 1, 14, 4, 7, Blocks.WATER);
		CarEntity car = spawnCar(h, 7.0, 1.34, 4.5, EAST);
		long t0 = simTick(h);
		ServerPlayer p = seat(h, car);
		float hp = p.getHealth();
		boolean[] on = {false};
		int[] ticks = {0, 0};
		everyTick(h, p, () -> {
		}, () -> {
			if (on[0]) {
				ticks[0]++;
				if (p.isEyeInFluid(FluidTags.WATER)) {
					ticks[1]++;
				}
				h.assertTrue(p.getAirSupply() == p.getMaxAirSupply(), "air " + p.getAirSupply() + " of "
						+ p.getMaxAirSupply() + " on tick " + ticks[0]);
				assertUnhurt(h, p, hp, "under water, tick " + ticks[0]);
				assertRides(h, p, car, "under water");
			}
		});
		h.startSequence()
				.thenWaitUntil(() -> assertRunning(h, car, t0))
				.thenExecute(() -> on[0] = true)
				.thenExecuteAfter(400, () -> {
					LOG.info("driver_breathes_under_water: {} ticks, eye under water on {}, air {} of {}, health {} of {}",
							ticks[0], ticks[1], p.getAirSupply(), p.getMaxAirSupply(), p.getHealth(), hp);
					h.assertTrue(ticks[0] >= 400, "only " + ticks[0] + " ticks under water");
					h.assertTrue(ticks[1] == ticks[0], "the driver's eye was under water on only " + ticks[1] + " of "
							+ ticks[0] + " ticks");
					h.assertTrue(p.getAirSupply() == p.getMaxAirSupply(), "air " + p.getAirSupply());
					assertUnhurt(h, p, hp, "after 400 ticks");
					assertRides(h, p, car, "after 400 ticks");
					leave(h, p);
					car.discard();
				})
				.thenSucceed();
	}

	@GameTest(environment = "mcrl-test:fall_distance_reset", structure = RUNWAY, maxTicks = 300)
	public void fallDistanceReset(GameTestHelper h) {
		clean(h);
		terrainKeepsUp(h);
		fill(h, 0, 0, 0, 15, 0, 15, Blocks.STONE);
		CarEntity car = spawnCar(h, 8.0, 1.34, 8.5, EAST);
		long t0 = simTick(h);
		ServerPlayer p = seat(h, car);
		float hp = p.getHealth();
		double[] afterLevel = {Double.NaN};
		boolean[] out = {false};
		everyTick(h, p, () -> afterLevel[0] = p.fallDistance, () -> {
			if (out[0]) {
				assertUnhurt(h, p, hp, "after the exit");
			}
		});
		h.startSequence()
				.thenWaitUntil(() -> assertRunning(h, car, t0))
				.thenExecute(() -> p.fallDistance = 40)
				.thenExecuteAfter(1, () -> {
					LOG.info("fall_distance_reset: fall distance one tick after 40: {}", afterLevel[0]);
					h.assertTrue(afterLevel[0] == 0.0, "fall distance " + afterLevel[0] + " one tick after it was set to 40");
					assertRides(h, p, car, "before the exit");
					McrlPayloads.handleExit(p);
					h.assertFalse(p.isPassenger(), "still riding after ExitCarC2S");
					p.doCheckFallDamage(0, 0, 0, true);
					assertUnhurt(h, p, hp, "on landing after the exit");
					out[0] = true;
				})
				.thenExecuteAfter(20, () -> {
					LOG.info("fall_distance_reset: 20 ticks after the exit: health {} of {}, fall distance {}", p.getHealth(),
							hp, p.fallDistance);
					assertUnhurt(h, p, hp, "20 ticks after the exit");
					out[0] = false;
					p.fallDistance = 5;
					p.doCheckFallDamage(0, 0, 0, true);
					LOG.info("fall_distance_reset: control landing from 5 blocks: health {} of {}", p.getHealth(), hp);
					h.assertTrue(p.getHealth() == hp - 2, "the control landing from 5 blocks left health " + p.getHealth());
					leave(h, p);
					car.discard();
				})
				.thenSucceed();
	}

	@GameTest(environment = "mcrl-test:car_survives_below_world", structure = RUNWAY, maxTicks = 200)
	public void carSurvivesBelowWorld(GameTestHelper h) {
		clean(h);
		terrainKeepsUp(h);
		fill(h, 0, 0, 0, 15, 0, 15, Blocks.STONE);
		CarEntity car = spawnCar(h, 8.0, 1.34, 8.5, EAST);
		long t0 = simTick(h);
		int minY = h.getLevel().getMinY();
		h.startSequence()
				.thenWaitUntil(() -> assertRunning(h, car, t0))
				.thenExecute(() -> {
					car.setPos(car.getX(), minY - 70, car.getZ());
					h.assertTrue(car.getY() < minY - 64, "the car is at y " + car.getY() + ", not below the world");
				})
				.thenExecuteAfter(1, () -> {
					LOG.info("car_survives_below_world: one tick after y = minY - 70 ({}): removed {}, y {}", minY - 70,
							car.isRemoved(), car.getY());
					h.assertTrue(!car.isRemoved(), "the car was removed below the world: " + car.getRemovalReason());
					car.discard();
				})
				.thenSucceed();
	}

	private static boolean touches(GameTestHelper h, AABB box, Block b) {
		return BlockPos.betweenClosedStream(box.deflate(1e-7)).anyMatch(pos -> h.getLevel().getBlockState(pos).is(b));
	}

	@GameTest(environment = "mcrl-test:dismount_when_left_blocked", structure = RUNWAY, maxTicks = 200)
	public void dismountWhenLeftBlocked(GameTestHelper h) {
		clean(h);
		terrainKeepsUp(h);
		fill(h, 0, 0, 0, 15, 0, 15, Blocks.STONE);
		fill(h, 0, 1, 2, 15, 3, 2, Blocks.STONE);
		fill(h, 0, 1, 5, 15, 3, 5, Blocks.STONE);
		CarEntity car = spawnCar(h, 8.0, 1.34, 4.0, EAST);
		long t0 = simTick(h);
		ServerPlayer p = seat(h, car);
		double floor = topOf(h, 0);
		h.startSequence()
				.thenWaitUntil(() -> assertRunning(h, car, t0))
				.thenExecute(() -> {
					Pose pose = car.simState().pose();
					OrientedBox box = OrientedBox.car(pose, HitboxPreset.OCTANE);
					Vector3d f = pose.forward();
					McrlPayloads.handleExit(p);
					h.assertFalse(p.isPassenger(), "still riding after ExitCarC2S");
					double dx = p.getX() - box.cx(), dz = p.getZ() - box.cz();
					double ahead = dx * f.x + dz * f.z;
					boolean clear = h.getLevel().noCollision(p);
					LOG.info("dismount_when_left_blocked: at ({}, {}, {}); ahead of the centre {}, feet - floor {}, clear {}",
							p.getX(), p.getY(), p.getZ(), ahead, p.getY() - floor, clear);
					h.assertTrue(clear, "the driver exited into a block at " + p.position());
					h.assertTrue(ahead < 0, "the driver is not behind the car: " + ahead);
					h.assertTrue(Math.abs(p.getY() - floor) <= 0.1, "the driver's feet are " + (p.getY() - floor)
							+ " from the floor top (not on the floor behind the car)");
					leave(h, p);
					car.discard();
				})
				.thenSucceed();
	}

	@GameTest(environment = "mcrl-test:lava_then_water_puts_driver_out", structure = RUNWAY, maxTicks = 600)
	public void lavaThenWaterPutsDriverOut(GameTestHelper h) {
		clean(h);
		terrainKeepsUp(h);
		fill(h, 0, 0, 0, 47, 0, 15, Blocks.STONE);
		fill(h, 0, 1, 0, 10, 1, 0, Blocks.GLASS);
		fill(h, 0, 1, 8, 10, 1, 8, Blocks.GLASS);
		fill(h, 0, 1, 1, 0, 1, 7, Blocks.GLASS);
		fill(h, 10, 1, 1, 10, 1, 7, Blocks.GLASS);
		fill(h, 1, 1, 1, 9, 1, 7, Blocks.LAVA);
		fill(h, 20, 1, 0, 35, 3, 0, Blocks.GLASS);
		fill(h, 20, 1, 8, 35, 3, 8, Blocks.GLASS);
		fill(h, 20, 1, 1, 20, 3, 7, Blocks.GLASS);
		fill(h, 35, 1, 1, 35, 3, 7, Blocks.GLASS);
		fill(h, 21, 1, 1, 34, 3, 7, Blocks.WATER);
		CarEntity car = spawnCar(h, 5.0, 1.34, 4.5, EAST);
		long t0 = simTick(h);
		ServerPlayer p = seat(h, car);
		int[] ticks = {-1, -1};
		everyTick(h, p, () -> {
		}, () -> {
			if (ticks[0] < 0 && car.wet().fluid() == Wet.WATER) {
				ticks[0] = 0;
			} else if (ticks[0] >= 0) {
				ticks[0]++;
			}
			if (ticks[0] >= 0 && ticks[1] < 0 && !p.isOnFire()) {
				ticks[1] = ticks[0];
			}
		});
		h.startSequence()
				.thenWaitUntil(() -> assertRunning(h, car, t0))
				.thenWaitUntil(() -> h.assertTrue(p.isOnFire(), "the driver has not caught fire in the lava"))
				.thenExecute(() -> {
					Vec3 a = h.absoluteVec(new Vec3(27.0, 1.34, 4.5));
					Pose cur = car.simState().pose();
					host(h).setCar(car.getId(), CarState.of(new Pose(a.x, a.y, a.z, cur.qw(), cur.qx(), cur.qy(), cur.qz()),
							0, 0, 0, 0, 0, 0));
				})
				.thenWaitUntil(() -> h.assertTrue(ticks[0] >= 0, "the car's feed does not say WATER yet"))
				.thenWaitUntil(() -> h.assertTrue(ticks[1] >= 0 || ticks[0] > 5, "waiting for the fire to go out"))
				.thenExecute(() -> h.assertTrue(ticks[1] >= 0 && ticks[1] <= 5,
						"the driver still burns " + ticks[0] + " ticks after the car entered the water"))
				.thenIdle(100)
				.thenExecute(() -> {
					LOG.info("lava_then_water_puts_driver_out: fire out {} ticks after WATER; health {}, air {} of {}, wet {}",
							ticks[1], p.getHealth(), p.getAirSupply(), p.getMaxAirSupply(), car.wet());
					h.assertTrue(car.wet().fluid() == Wet.WATER, "the car is not in water: " + car.wet());
					h.assertFalse(p.isOnFire(), "the driver is on fire in water");
					h.assertTrue(p.getAirSupply() == p.getMaxAirSupply(), "air " + p.getAirSupply());
					assertRides(h, p, car, "after 100 ticks in water");
					leave(h, p);
					car.discard();
				})
				.thenSucceed();
	}

	private enum Hazard {
		BERRY_BUSH(Blocks.SWEET_BERRY_BUSH), WITHER_ROSE(Blocks.WITHER_ROSE), FIRE(Blocks.FIRE),
		POWDER_SNOW(Blocks.POWDER_SNOW), CAMPFIRE(Blocks.CAMPFIRE);

		final Block block;

		Hazard(Block block) {
			this.block = block;
		}
	}

	@GameTest(environment = "mcrl-test:block_effects_ignored", structure = LONG_RUNWAY, maxTicks = 600)
	public void blockEffectsIgnored(GameTestHelper h) {
		clean(h);
		terrainKeepsUp(h);
		fill(h, 0, 0, 0, 90, 0, 7, Blocks.STONE);
		fill(h, 9, 0, 3, 10, 0, 5, Blocks.DIRT);
		fill(h, 13, 0, 3, 14, 0, 5, Blocks.DIRT);
		fill(h, 17, 0, 3, 18, 0, 5, Blocks.NETHERRACK);
		BlockState bush = Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3);
		for (int x = 9; x <= 10; x++) {
			for (int z = 3; z <= 5; z++) {
				h.setBlock(x, 1, z, bush);
			}
		}
		fill(h, 13, 1, 3, 14, 1, 5, Blocks.WITHER_ROSE);
		fill(h, 17, 1, 3, 18, 1, 5, Blocks.FIRE);
		fill(h, 21, 1, 3, 22, 1, 5, Blocks.POWDER_SNOW);
		fill(h, 25, 1, 3, 26, 1, 5, Blocks.CAMPFIRE);
		CarEntity car = spawnCar(h, 3.0, 1.34, 4.5, EAST);
		long t0 = simTick(h);
		ServerPlayer p = seat(h, car);
		float hp = p.getHealth();
		double past = h.absoluteVec(new Vec3(32, 0, 0)).x;
		boolean[] on = {false, false};
		int[] ticks = {0, 0};
		Set<Hazard> touched = EnumSet.noneOf(Hazard.class);
		Window w = new Window();
		everyTick(h, p, () -> {
		}, () -> {
			if (on[0]) {
				ticks[0]++;
				w.sample(h);
				for (Hazard z : Hazard.values()) {
					if (touches(h, p.getBoundingBox(), z.block)) {
						touched.add(z);
					}
				}
				assertUnhurt(h, p, hp, "on tick " + ticks[0] + " of the strip run");
				h.assertTrue(p.getTicksFrozen() == 0, "the driver is freezing on tick " + ticks[0] + ": " + p.getTicksFrozen());
				h.assertFalse(p.isOnFire(), "the driver is on fire on tick " + ticks[0]);
				assertRides(h, p, car, "on tick " + ticks[0] + " of the strip run");
			}
			if (on[1]) {
				host(h).submitInput(car.getId(), drive(++ticks[1], 1f, false));
			}
		});
		h.startSequence()
				.thenWaitUntil(() -> assertRunning(h, car, t0))
				.thenExecute(() -> {
					on[0] = true;
					on[1] = true;
				})
				.thenWaitUntil(() -> h.assertTrue(car.getX() > past, "the car has not passed the strip yet"))
				.thenExecute(() -> {
					on[1] = false;
					host(h).submitInput(car.getId(), InputFrame.NEUTRAL);
				})
				.thenExecuteAfter(60, () -> {
					LOG.info("block_effects_ignored: {} ticks watched, {} FROZEN, hazards touched {}; health {} of {}, frozen "
							+ "ticks {}, on fire {}", ticks[0], w.frozen, touched, p.getHealth(), hp, p.getTicksFrozen(),
							p.isOnFire());
					Set<Hazard> missed = EnumSet.allOf(Hazard.class);
					missed.removeAll(touched);
					h.assertTrue(missed.isEmpty(), "the driver's box missed " + missed);
					assertUnhurt(h, p, hp, "60 ticks after the strip");
					h.assertTrue(p.getTicksFrozen() == 0, "frozen ticks " + p.getTicksFrozen());
					h.assertFalse(p.isOnFire(), "the driver is on fire after the strip");
					assertRides(h, p, car, "after the strip");
					leave(h, p);
					car.discard();
				})
				.thenSucceed();
	}
}
