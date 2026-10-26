package dev.mcrl.test;

import static dev.mcrl.test.GameTestSupport.EAST;
import static dev.mcrl.test.GameTestSupport.RUNWAY;
import static dev.mcrl.test.GameTestSupport.assertRunning;
import static dev.mcrl.test.GameTestSupport.clean;
import static dev.mcrl.test.GameTestSupport.fill;
import static dev.mcrl.test.GameTestSupport.host;
import static dev.mcrl.test.GameTestSupport.leave;
import static dev.mcrl.test.GameTestSupport.simTick;
import static dev.mcrl.test.GameTestSupport.spawnCar;
import static dev.mcrl.test.GameTestSupport.terrainKeepsUp;

import java.util.List;
import java.util.UUID;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.entity.CarChoice;
import dev.mcrl.entity.CarChoices;
import dev.mcrl.entity.CarEntity;
import dev.mcrl.entity.McrlEntities;
import dev.mcrl.sim.SimFrame;
import dev.mcrl.sim.SimHost;

public class CarChoiceGameTests {
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

	private static CarEntity ownedCar(GameTestHelper h, ServerPlayer owner, double rx, double rz) {
		CarEntity car = spawnCar(h, rx, 1.34, rz, EAST);
		car.setOwner(owner.getUUID());
		return car;
	}

	private static void runAs(GameTestHelper h, ServerPlayer p, String command) {
		CommandSource quiet = new CommandSource() {
			@Override
			public void sendSystemMessage(Component c) {
			}

			@Override
			public boolean acceptsSuccess() {
				return false;
			}

			@Override
			public boolean acceptsFailure() {
				return false;
			}

			@Override
			public boolean shouldInformAdmins() {
				return false;
			}
		};
		h.getLevel().getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack().withSource(quiet),
				command);
	}

	private static List<CarEntity> ownedBy(GameTestHelper h, UUID owner) {
		return List.copyOf(h.getLevel().getEntities(McrlEntities.CAR, c -> !c.isRemoved() && owner.equals(c.owner())));
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

	private static void check(GameTestHelper h, CarEntity car, CarChoice want, String when) {
		h.assertTrue(car.choice().equals(want), when + ": the car has " + car.choice() + ", want " + want);
	}

	@GameTest(environment = "mcrl-test:choice_other_player", structure = RUNWAY, maxTicks = 60)
	public void otherPlayersCarUntouched(GameTestHelper h) {
		floor(h);
		ServerPlayer p = player(h, 4.0, 2.0), q = player(h, 4.0, 14.0);
		CarEntity mine = ownedCar(h, p, 8.0, 4.0), theirs = ownedCar(h, q, 8.0, 12.0);
		CarChoice qs = new CarChoice("Body_Q", 4, 1);
		theirs.applyChoice(qs);
		long t0 = simTick(h);
		h.startSequence()
				.thenWaitUntil(() -> assertRunning(h, mine, t0))
				.thenExecute(() -> CarChoices.receive(p, new CarChoice("Body_X", 3, 0)))
				.thenIdle(2)
				.thenExecute(() -> {
					check(h, mine, new CarChoice("Body_X", 3, 0), "P's car");
					check(h, theirs, qs, "Q's car");
					h.assertTrue(snap(h, theirs).preset() == 4 && snap(h, theirs).team() == 1, "Q's snap changed");
					mine.discard();
					theirs.discard();
					leave(p);
					leave(q);
				})
				.thenSucceed();
	}

	@GameTest(environment = "mcrl-test:choice_bad_ignored", structure = RUNWAY, maxTicks = 60)
	public void badChoiceIgnored(GameTestHelper h) {
		floor(h);
		ServerPlayer p = player(h, 4.0, 2.0);
		CarEntity car = ownedCar(h, p, 8.0, 8.0);
		long t0 = simTick(h);
		h.startSequence()
				.thenWaitUntil(() -> assertRunning(h, car, t0))
				.thenExecute(() -> {
					CarChoices.receive(p, new CarChoice("../x", 0, 0));
					CarChoices.receive(p, new CarChoice("Body_A", 7, 0));
					CarChoices.receive(p, new CarChoice("Body_A", 0, 2));
					CarChoices.receive(p, new CarChoice("a".repeat(65), 0, 0));
				})
				.thenIdle(2)
				.thenExecute(() -> {
					check(h, car, CarChoice.DEFAULT, "after bad choices");
					h.assertTrue(CarChoices.get(p).equals(CarChoice.DEFAULT), "kept " + CarChoices.get(p));
					SimFrame.CarSnap s = snap(h, car);
					h.assertTrue(s.preset() == 0 && s.team() == 0, "the snap changed");
					car.discard();
					leave(p);
				})
				.thenSucceed();
	}

	@GameTest(environment = "mcrl-test:choice_flood", structure = RUNWAY, maxTicks = 80)
	public void choiceFloodScansOnce(GameTestHelper h) {
		floor(h);
		ServerPlayer p = player(h, 4.0, 2.0);
		CarEntity car = ownedCar(h, p, 8.0, 8.0);
		long t0 = simTick(h);
		int[] before = new int[1];
		h.startSequence()
				.thenWaitUntil(() -> assertRunning(h, car, t0))
				.thenExecute(() -> {
					int scans = Taps.carScans;
					CarChoice c = new CarChoice("Body_F", 2, 0);
					for (int i = 0; i < 1000; i++) {
						CarChoices.receive(p, c);
					}
					h.assertTrue(Taps.carScans - scans == 1, (Taps.carScans - scans) + " walks for 1000 copies");
					check(h, car, c, "after 1000 copies");
					runAs(h, p, "mcrl car");
					before[0] = Taps.carScans;
					for (int i = 0; i < 1000; i++) {
						CarChoices.receive(p, new CarChoice("Body_F", i % 2 == 0 ? 3 : 4, 0));
					}
					h.assertTrue(Taps.carScans == before[0], (Taps.carScans - before[0])
							+ " walks with a current car");
				})
				.thenIdle(CarChoices.INTERVAL_TICKS + 2)
				.thenExecute(() -> {
					h.assertTrue(Taps.carScans == before[0], (Taps.carScans - before[0])
							+ " walks with a current car, ticks included");
					List<CarEntity> mine = ownedBy(h, p.getUUID());
					h.assertTrue(mine.size() == 1, mine.size() + " car(s) owned");
					check(h, mine.get(0), new CarChoice("Body_F", 4, 0), "the latest of 1000");
					mine.get(0).discard();
					leave(p);
				})
				.thenSucceed();
	}
}
