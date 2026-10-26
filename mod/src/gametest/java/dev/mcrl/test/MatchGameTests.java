package dev.mcrl.test;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.entity.BallEntity;
import dev.mcrl.entity.CarEntity;
import dev.mcrl.entity.McrlEntities;
import dev.mcrl.match.ActiveMatch;
import dev.mcrl.match.ArenaMeshes;
import dev.mcrl.match.Match;
import dev.mcrl.match.MatchManager;
import dev.mcrl.match.MatchManager.JoinAs;
import dev.mcrl.match.MatchSize;
import dev.mcrl.match.ReturnPoint;
import dev.mcrl.match.Slot;
import dev.mcrl.match.StadiumConst;
import dev.mcrl.math.Pose;
import dev.mcrl.net.McrlPayloads;
import dev.mcrl.sim.BallState;
import dev.mcrl.sim.SimFrame;
import dev.mcrl.sim.SimHost;
import dev.mcrl.sim.SimHosts;
import dev.mcrl.sim.SoccarState;
import dev.mcrl.testkit.TestArena;

public class MatchGameTests {
	private static final double[][] KICKOFF = {{-40.96, -51.2}, {40.96, -51.2}, {-5.12, -76.8}, {5.12, -76.8},
			{0, -92.16}};

	private static final List<ServerPlayer> MOCKS = new ArrayList<>();

	private static void begin(GameTestHelper h) {
		leaveAll();
		GameTestSupport.clean(h);
		MinecraftServer server = h.getLevel().getServer();
		MatchManager.reset(server);
		System.setProperty(ArenaMeshes.PROPERTY, TestArena.dir().toString());
	}

	private static void finish(GameTestHelper h) {
		MatchManager.reset(h.getLevel().getServer());
		System.clearProperty(ArenaMeshes.PROPERTY);
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

	private static SoccarState soccar(GameTestHelper h) {
		SimHost host = SimHosts.get(StadiumConst.KEY);
		h.assertTrue(host != null, "no stadium host");
		SoccarState s = host.latest().soccar();
		h.assertTrue(s != null, "no SOCCAR state");
		return s;
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

	private static boolean onKickoffSpot(Pose p, int team) {
		double s = team == 0 ? 1 : -1;
		for (double[] k : KICKOFF) {
			if (Math.abs(p.x() - s * k[0]) <= 0.05 && Math.abs(p.z() - s * k[1]) <= 0.05) {
				return true;
			}
		}
		return false;
	}

	private static void shoot(GameTestHelper h, double z0, double vz) {
		BallEntity ball = BallEntity.active(stadium(h));
		h.assertTrue(ball != null, "no stadium ball");
		ball.relocate(new BallState(new Pose(0, 65.863, z0, 1, 0, 0, 0), 0, 0, vz, 0, 0, 0));
	}

	@GameTest(environment = "mcrl-test:match_create_join_start", maxTicks = 200)
	public void createJoinStart(GameTestHelper h) {
		begin(h);
		ServerPlayer p1 = player(h, 2, 2), p2 = player(h, 4, 4);
		ok(h, MatchManager.create(p1, MatchSize.DUEL), "create");
		ActiveMatch m = current(h);
		h.assertTrue(m.match().phase() == Match.Phase.LOBBY, "phase " + m.match().phase());
		Slot s0 = m.slots().get(0);
		h.assertTrue(p1.getUUID().equals(s0.player()) && s0.team() == 0, "slot 0 " + s0);
		h.assertTrue(p1.level().dimension().equals(StadiumConst.KEY), "p1 in " + p1.level().dimension());
		h.assertTrue(p1.getVehicle() instanceof CarEntity c && c.team() == 0 && c.getId() == s0.carEntityId(),
				"p1 rides " + p1.getVehicle());
		ok(h, MatchManager.join(p2, JoinAs.ORANGE), "join orange");
		Slot s1 = current(h).slots().get(1);
		h.assertTrue(p2.getUUID().equals(s1.player()) && s1.team() == 1, "slot 1 " + s1);
		long[] t0 = {0};
		h.startSequence()
				.thenIdle(5)
				.thenExecute(() -> {
					ok(h, MatchManager.start(p1.createCommandSourceStack()), "start");
					t0[0] = h.getTick();
				})
				.thenWaitUntil(within(h, t0, 2, "KICKOFF with the cars on their spots", () -> {
					h.assertTrue(phase(h) == Match.Phase.KICKOFF, "phase " + phase(h));
					SimFrame f = SimHosts.get(StadiumConst.KEY).latest();
					for (Slot s : current(h).slots()) {
						SimFrame.CarSnap c = f.car(s.carEntityId());
						h.assertTrue(c != null && onKickoffSpot(c.cur(), s.team()), "slot " + s.index() + " at "
								+ (c == null ? null : c.cur()));
					}
				}))
				.thenWaitUntil(within(h, t0, 62, "PLAYING", () -> h.assertTrue(phase(h) == Match.Phase.PLAYING,
						"phase " + phase(h))))
				.thenExecute(() -> finish(h))
				.thenSucceed();
	}

	@GameTest(environment = "mcrl-test:match_goal", maxTicks = 300)
	public void goalScoresAndKicksOff(GameTestHelper h) {
		begin(h);
		ServerPlayer p1 = player(h, 2, 2);
		ok(h, MatchManager.create(p1, MatchSize.DUEL), "create");
		long[] t0 = {0};
		h.startSequence()
				.thenIdle(3)
				.thenExecute(() -> ok(h, MatchManager.start(p1.createCommandSourceStack()), "start"))
				.thenWaitUntil(() -> h.assertTrue(phase(h) == Match.Phase.PLAYING, "phase " + phase(h)))
				.thenExecute(() -> {
					shoot(h, 96, 40);
					t0[0] = h.getTick();
				})
				.thenWaitUntil(within(h, t0, 10, "the goal", () -> h.assertTrue(current(h).match().blue() == 1,
						"blue " + current(h).match().blue())))
				.thenWaitUntil(within(h, t0, 70, "the next kickoff", () -> h.assertTrue(soccar(h).kickoffs() == 2,
						"kickoffs " + soccar(h).kickoffs())))
				.thenExecute(() -> finish(h))
				.thenSucceed();
	}

	@GameTest(environment = "mcrl-test:match_end_returns", maxTicks = 200)
	public void endReturnsEveryone(GameTestHelper h) {
		begin(h);
		ServerPlayer p1 = player(h, 2, 2), p2 = player(h, 4, 4);
		ok(h, MatchManager.create(p1, MatchSize.DUEL), "create");
		ok(h, MatchManager.join(p2, JoinAs.SPECTATE), "spectate");
		long[] t0 = {0};
		h.startSequence()
				.thenIdle(3)
				.thenExecute(() -> {
					ok(h, MatchManager.end(p1.createCommandSourceStack()), "end");
					h.assertTrue(phase(h) == Match.Phase.ENDED, "phase " + phase(h));
					t0[0] = h.getTick();
				})
				.thenWaitUntil(within(h, t0, 105, "everyone back", () -> {
					h.assertTrue(MatchManager.current() == null, "the match is still there");
					h.assertTrue(SimHosts.get(StadiumConst.KEY) == null, "the stadium host is still there");
					h.assertTrue(p1.level() == h.getLevel() && p2.level() == h.getLevel(), "not back");
					h.assertTrue(p1.getVehicle() == null, "p1 still rides " + p1.getVehicle());
					h.assertTrue(stadium(h).getEntities(McrlEntities.CAR, c -> !c.isRemoved()).isEmpty(), "cars left");
					h.assertTrue(stadium(h).getEntities(McrlEntities.BALL, b -> !b.isRemoved()).isEmpty(), "ball left");
				}))
				.thenExecute(() -> finish(h))
				.thenSucceed();
	}

	@GameTest(environment = "mcrl-test:match_not_saved", maxTicks = 80)
	public void matchEntitiesNotSaved(GameTestHelper h) {
		begin(h);
		ServerPlayer p1 = player(h, 2, 2);
		ok(h, MatchManager.create(p1, MatchSize.DUEL), "create");
		CarEntity match = (CarEntity) p1.getVehicle();
		h.assertTrue(match != null && !match.shouldBeSaved(), "the match car is saved");
		CarEntity free = McrlEntities.CAR.create(h.getLevel(), EntitySpawnReason.COMMAND);
		h.assertTrue(free != null && free.shouldBeSaved(), "a free-roam car is not saved");
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
		p1.saveWithoutId(out);
		CompoundTag saved = out.buildResult();
		h.assertTrue(saved.contains("RootVehicle"), "p1's save holds no RootVehicle (the test needs one)");
		Vec3 at = match.position();
		ServerPlayer p2 = player(h, 4, 4);
		CarEntity[] loaded = {null};
		h.startSequence()
				.thenWaitUntil(() -> {
					BallEntity ball = BallEntity.active(stadium(h));
					h.assertTrue(ball != null, "no stadium ball yet");
					h.assertTrue(!ball.shouldBeSaved(), "the match ball is saved");
				})
				.thenExecute(() -> {
					MatchManager.reset(h.getLevel().getServer());
					p2.teleportTo(stadium(h), at.x, at.y, at.z, java.util.Set.of(), 0f, 0f, true);
					p2.loadAndSpawnParentVehicle(TagValueInput.create(ProblemReporter.DISCARDING,
							h.getLevel().registryAccess(), saved));
					loaded[0] = p2.getVehicle() instanceof CarEntity c ? c : null;
					h.assertTrue(loaded[0] != null, "the saved car did not load (the test needs it)");
				})
				.thenWaitUntil(() -> h.assertTrue(loaded[0].isRemoved(), "the match car from the save is still there"))
				.thenExecute(() -> {
					h.assertTrue(!(p2.getVehicle() instanceof CarEntity), "p2 rides " + p2.getVehicle());
					SimHost host = SimHosts.get(StadiumConst.KEY);
					h.assertTrue(host == null || host.latest().car(loaded[0].getId()) == null, "it entered a sim");
					finish(h);
				})
				.thenSucceed();
	}

	@GameTest(environment = "mcrl-test:match_exit_safe", maxTicks = 100)
	public void exitKeyKeepsMatchPlayerSafe(GameTestHelper h) {
		begin(h);
		ServerPlayer p1 = player(h, 2, 2), p3 = player(h, 6, 6);
		ok(h, MatchManager.create(p1, MatchSize.DUEL), "create");
		CarEntity car = (CarEntity) p1.getVehicle();
		McrlPayloads.handleExit(p1);
		h.assertTrue(p1.getVehicle() == car, "the exit key took p1 out of the match car");
		p1.stopRiding();
		p3.teleportTo(stadium(h), 0, StadiumConst.ORIGIN.getY() - 20, 0, java.util.Set.of(), 0f, 0f, true);
		long[] t0 = {h.getTick()};
		h.startSequence()
				.thenWaitUntil(within(h, t0, 3, "the safety net", () -> {
					h.assertTrue(p1.getVehicle() == car, "p1 is not back in the car: " + p1.getVehicle());
					h.assertTrue(p3.position().distanceTo(StadiumConst.SPECTATOR_POS) <= 0.01, "p3 at " + p3.position());
				}))
				.thenExecuteAfter(20, () -> {
					h.assertTrue(p1.isAlive() && p1.getVehicle() == car, "p1 alive " + p1.isAlive() + ", riding "
							+ p1.getVehicle());
					finish(h);
				})
				.thenSucceed();
	}

	private static void inStadium(GameTestHelper h, ServerPlayer p, @org.jspecify.annotations.Nullable ReturnPoint rp) {
		p.teleportTo(stadium(h), 0, 70, -40, java.util.Set.of(), 0f, 0f, true);
		if (rp != null) {
			p.setAttached(ReturnPoint.ATTACHMENT, rp);
		} else {
			p.removeAttached(ReturnPoint.ATTACHMENT);
		}
	}

	@GameTest(environment = "mcrl-test:match_login_no_point", maxTicks = 40)
	public void loginRescueNoReturnPoint(GameTestHelper h) {
		begin(h);
		ServerPlayer p = player(h, 2, 2);
		p.setGameMode(GameType.CREATIVE);
		inStadium(h, p, null);
		MinecraftServer server = h.getLevel().getServer();
		net.minecraft.world.level.storage.LevelData.RespawnData spawn = server.getRespawnData();
		MatchManager.onJoin(p);
		long[] t0 = {h.getTick()};
		h.startSequence()
				.thenWaitUntil(within(h, t0, 2, "the rescue", () -> {
					h.assertTrue(p.level().dimension().equals(spawn.dimension()), "in " + p.level().dimension());
					h.assertTrue(Math.abs(p.getX() - (spawn.pos().getX() + 0.5)) <= 0.01
							&& Math.abs(p.getZ() - (spawn.pos().getZ() + 0.5)) <= 0.01, "at " + p.position()
							+ ", spawn " + spawn.pos());
					h.assertTrue(p.gameMode.getGameModeForPlayer() == server.getDefaultGameType(), "mode "
							+ p.gameMode.getGameModeForPlayer());
				}))
				.thenExecute(() -> finish(h))
				.thenSucceed();
	}

	@GameTest(environment = "mcrl-test:match_remount", structure = GameTestSupport.RUNWAY, maxTicks = 200)
	public void returnRemountsCar(GameTestHelper h) {
		begin(h);
		GameTestSupport.fill(h, 0, 0, 0, 47, 0, 15, net.minecraft.world.level.block.Blocks.STONE);
		CarEntity own = GameTestSupport.spawnCar(h, 8.0, 1.34, 8.0, GameTestSupport.EAST);
		ServerPlayer p1 = player(h, 2, 2);
		h.startSequence()
				.thenIdle(3)
				.thenExecute(() -> {
					h.assertTrue(p1.startRiding(own), "could not ride the free-roam car");
					ok(h, MatchManager.create(p1, MatchSize.DUEL), "create");
					h.assertTrue(p1.getVehicle() != own, "still in the free-roam car");
					h.assertTrue(!own.isRemoved(), "the free-roam car left");
				})
				.thenIdle(3)
				.thenExecute(() -> ok(h, MatchManager.leave(p1), "leave"))
				.thenWaitUntil(() -> h.assertTrue(p1.getVehicle() == own, "rides " + p1.getVehicle()))
				.thenExecute(() -> {
					p1.stopRiding();
					own.discard();
					finish(h);
				})
				.thenSucceed();
	}
}
