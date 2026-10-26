package dev.mcrl.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.entity.CarEntity;
import dev.mcrl.match.ActiveMatch;
import dev.mcrl.match.ArenaMeshes;
import dev.mcrl.match.Match;
import dev.mcrl.match.MatchConfig;
import dev.mcrl.match.MatchManager;
import dev.mcrl.match.MatchSize;
import dev.mcrl.match.Slot;
import dev.mcrl.match.StadiumConst;
import dev.mcrl.math.Pose;
import dev.mcrl.net.MatchPayloads;
import dev.mcrl.net.MatchPayloads.MatchSetupS2C;
import dev.mcrl.sim.SimFrame;
import dev.mcrl.sim.SimHost;
import dev.mcrl.sim.SimHosts;
import dev.mcrl.testkit.PolicyFiles;
import dev.mcrl.testkit.TestArena;

public class BotGameTests {
	private static final List<ServerPlayer> MOCKS = new ArrayList<>();

	private static void begin(GameTestHelper h) {
		leaveAll();
		GameTestSupport.clean(h);
		MatchManager.reset(h.getLevel().getServer());
		Taps.matchPayloads = null;
		System.setProperty(ArenaMeshes.PROPERTY, TestArena.dir().toString());
		try {
			Path policy = PolicyFiles.write(Files.createTempFile("mcrl-policy-", ".lt"), PolicyFiles.constant(18), false);
			policy.toFile().deleteOnExit();
			Files.writeString(MatchConfig.file(), "{\"botPolicy\": \"" + policy + "\"}");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void finish(GameTestHelper h) {
		MatchManager.reset(h.getLevel().getServer());
		Taps.matchPayloads = null;
		System.clearProperty(ArenaMeshes.PROPERTY);
		try {
			Files.deleteIfExists(MatchConfig.file());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		leaveAll();
	}

	private static void leaveAll() {
		MOCKS.forEach(GameTestSupport::leave);
		MOCKS.clear();
	}

	private static ServerPlayer player(GameTestHelper h, double rx, double rz) {
		ServerPlayer p = h.makeMockServerPlayerInLevel();
		MOCKS.add(p);
		Vec3 a = h.absoluteVec(new Vec3(rx, 2, rz));
		p.snapTo(a.x, a.y, a.z, 0f, 0f);
		p.setGameMode(GameType.SURVIVAL);
		return p;
	}

	private static ServerLevel stadium(GameTestHelper h) {
		return h.getLevel().getServer().getLevel(StadiumConst.KEY);
	}

	private static ActiveMatch current(GameTestHelper h) {
		ActiveMatch m = MatchManager.current();
		h.assertTrue(m != null, "no match");
		return m;
	}

	private static void ok(GameTestHelper h, MatchManager.Result r, String what) {
		h.assertTrue(r.ok(), what + " refused: " + r.message().getString());
	}

	private static Match.Phase phase(GameTestHelper h) {
		return current(h).match().phase();
	}

	private static Runnable within(GameTestHelper h, long[] t0, long n, String what, Runnable check) {
		return () -> {
			if (h.getTick() - t0[0] > n) {
				h.fail(what + ": not within " + n + " server ticks");
			}
			check.run();
		};
	}

	private static CarEntity botCar(GameTestHelper h, int i) {
		Slot s = current(h).slots().get(i);
		h.assertTrue(s.bot() && s.player() == null && s.carEntityId() >= 0, "slot " + i + " is no bot: " + s);
		Entity e = current(h).body(s.carEntityId());
		h.assertTrue(e instanceof CarEntity c && !c.isRemoved() && c.isBot(), "slot " + i + "'s car " + e);
		return (CarEntity) e;
	}

	private static SimFrame.CarSnap snap(GameTestHelper h, int carId) {
		SimHost host = SimHosts.get(StadiumConst.KEY);
		h.assertTrue(host != null, "no stadium host");
		SimFrame.CarSnap c = host.latest().car(carId);
		h.assertTrue(c != null, "car " + carId + " is not in the stadium host's frame");
		return c;
	}

	@GameTest(environment = "mcrl-test:bot_start_fills", maxTicks = 200)
	public void startFillsEmptySlotWithBot(GameTestHelper h) {
		begin(h);
		ServerPlayer p1 = player(h, 2, 2);
		List<MatchSetupS2C> setups = new ArrayList<>();
		Taps.matchPayloads = (p, payload) -> {
			if (p == p1 && payload instanceof MatchSetupS2C s) {
				setups.add(s);
			}
		};
		ok(h, MatchManager.create(p1, MatchSize.DUEL), "create");
		long[] t0 = {0};
		int[] bot = {-1};
		Pose[] at = {null};
		h.startSequence()
				.thenIdle(3)
				.thenExecute(() -> {
					setups.clear();
					ok(h, MatchManager.start(p1.createCommandSourceStack()), "start");
					t0[0] = h.getTick();
				})
				.thenWaitUntil(within(h, t0, 2, "a bot in slot 1", () -> {
					CarEntity c = botCar(h, 1);
					h.assertTrue(c.team() == 1, "team " + c.team());
					h.assertTrue(c.getPassengers().isEmpty(), "passengers " + c.getPassengers());
					h.assertTrue(c.matchOwned(), "not match-owned");
					snap(h, c.getId());
					h.assertTrue(!setups.isEmpty(), "no setup sent");
					MatchPayloads.SlotInfo i = setups.get(setups.size() - 1).slots().get(1);
					h.assertTrue(i.bot() && i.name().equals("Bot") && i.carEntityId() == c.getId(), "setup slot 1 " + i);
					bot[0] = c.getId();
				}))
				.thenWaitUntil(within(h, t0, 62, "the bot drives", () -> {
					SimFrame.CarSnap c = snap(h, bot[0]);
					h.assertTrue(c.applied().throttle() == 1f, "applied " + c.applied());
					at[0] = c.cur();
				}))
				.thenIdle(20)
				.thenExecute(() -> {
					Pose now = snap(h, bot[0]).cur();
					double moved = Math.hypot(now.x() - at[0].x(), now.z() - at[0].z());
					h.assertTrue(moved > 3, "moved " + moved + " blocks");
					h.assertTrue(phase(h) == Match.Phase.PLAYING, "phase " + phase(h));
				})
				.thenExecute(() -> finish(h))
				.thenSucceed();
	}

	@GameTest(environment = "mcrl-test:bot_refuses_rider", maxTicks = 40)
	public void botCarRefusesRider(GameTestHelper h) {
		begin(h);
		ServerPlayer p1 = player(h, 2, 2), p3 = player(h, 6, 6);
		ok(h, MatchManager.create(p1, MatchSize.DUEL), "create");
		h.startSequence()
				.thenIdle(3)
				.thenExecute(() -> ok(h, MatchManager.start(p1.createCommandSourceStack()), "start"))
				.thenIdle(2)
				.thenExecute(() -> {
					CarEntity c = botCar(h, 1);
					p3.teleportTo(stadium(h), c.getX(), c.getY(), c.getZ(), java.util.Set.of(), 0f, 0f, true);
					h.assertTrue(!p3.startRiding(c), "p3 got into the bot's car");
					h.assertTrue(c.getPassengers().isEmpty(), "passengers " + c.getPassengers());
				})
				.thenExecute(() -> finish(h))
				.thenSucceed();
	}
}
