package dev.mcrl.sim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.mcrl.bot.BotPolicies;
import dev.mcrl.bot.BotPolicy;
import dev.mcrl.match.ArenaMeshes;
import dev.mcrl.math.Pose;
import dev.mcrl.net.NetTuning;
import dev.mcrl.sim.SimClock.Mode;
import dev.mcrl.sim.SimHost.SectionKey;
import dev.mcrl.terrain.Grid;
import dev.mcrl.terrain.GridFiller;
import dev.mcrl.terrain.MeshWorkers;
import dev.mcrl.terrain.TerrainSource;
import dev.mcrl.testkit.PolicyFiles;
import dev.mcrl.testkit.TestArena;

@Timeout(value = 120, unit = TimeUnit.SECONDS)
class SimHostTest {
	static final int MIN_Y = -64;
	private static final AtomicInteger VERSIONS = new AtomicInteger();

	@BeforeAll
	static void boot() {
		SyntheticTerrain.boot();
	}

	static CarState carAt(double x, double y, double z) {
		return CarState.of(new Pose(x, y, z, 1, 0, 0, 0), 0, 0, 0, 0, 0, 0);
	}

	static void feedAll(SimHost host, TerrainSource src) throws Exception {
		host.flush();
		List<CompletableFuture<Integer>> fs = new ArrayList<>();
		for (SectionKey k : host.neededSections()) {
			Grid grid = k.y() < src.minSectionY() || k.y() > src.maxSectionY()
					? new Grid(new short[GridFiller.N * GridFiller.N * GridFiller.N], false)
					: src.fillGrid(k.x(), k.y(), k.z());
			assertNotNull(grid, "grid " + k);
			fs.add(MeshWorkers.submit(k, grid.cells(), null, VERSIONS.incrementAndGet(), host));
		}
		CompletableFuture.allOf(fs.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS);
		host.flush();
	}

	private static boolean frozen(SimFrame f) {
		return (f.flags() & SimFrame.FROZEN) != 0;
	}

	@Test
	void frozen_until_neighbourhood_ready() throws Exception {
		try (SimHost h = SimHost.start("t-frozen", 0, 0, 0, MIN_Y, Mode.LOCKSTEP)) {
			h.addCar(1, carAt(8.5, 65, 8.5), 0, 0);
			h.stepLockstep(6);
			SimFrame f = h.latest();
			assertEquals(0, f.tick());
			assertTrue(frozen(f), "flags " + f.flags());
			assertEquals(65, f.car(1).cur().y(), 1e-6, "the car must not fall while frozen");

			feedAll(h, new SyntheticTerrain());
			h.stepLockstep(6);
			f = h.latest();
			assertEquals(6, f.tick());
			assertFalse(frozen(f));
		}
	}

	@Test
	void fault_flagged_and_thread_survives() throws Exception {
		try (SimHost h = SimHost.start("t-fault", 0, 0, 0, MIN_Y, Mode.LOCKSTEP)) {
			h.addCar(1, carAt(8.5, 65, 8.5), 0, 0);
			h.flush();
			assertEquals(0, h.latest().flags() & SimFrame.FAULT);
			assertEquals(new SimHost.Fault(0, null), h.fault());
			h.setCar(1, carAt(Double.NaN, 65, 8.5));
			h.flush();
			assertTrue((h.latest().flags() & SimFrame.FAULT) != 0);
			SimHost.Fault f = h.fault();
			assertEquals(1, f.count(), "one failed command");
			assertNotNull(f.last());
			assertTrue(f.last().startsWith("SetCar: "), "last fault names the item: " + f.last());
			feedAll(h, new SyntheticTerrain());
			h.stepLockstep(6);
			assertEquals(6, h.latest().tick(), "the host keeps serving after a fault");
			assertTrue((h.latest().flags() & SimFrame.FAULT) != 0);
			h.setCar(1, carAt(8.5, Double.NaN, 8.5));
			h.flush();
			assertEquals(2, h.fault().count(), "a second failed command counts too");
		}
	}

	@Test
	void bad_team_preset_or_demo_mode_is_refused() throws Exception {
		try (SimHost h = SimHost.start("t-refuse", 0, 0, 0, MIN_Y, Mode.LOCKSTEP)) {
			CarState s = carAt(8.5, 65, 8.5);
			assertThrows(IllegalArgumentException.class, () -> h.addCar(1, s, 2, 0));
			assertThrows(IllegalArgumentException.class, () -> h.addCar(1, s, -1, 0));
			assertThrows(IllegalArgumentException.class, () -> h.addCar(1, s, 0, 7));
			assertThrows(IllegalArgumentException.class, () -> h.addCar(1, s, 0, -1));
			h.addCar(1, s, 1, 6);
			assertThrows(IllegalArgumentException.class, () -> h.reconfigureCar(1, 0, 7));
			assertThrows(IllegalArgumentException.class, () -> h.reconfigureCar(1, 3, 0));
			assertThrows(IllegalArgumentException.class, () -> h.setDemos(3, true));
			assertThrows(IllegalArgumentException.class, () -> h.setDemos(-1, true));
			h.flush();
			assertEquals(SimHost.Fault.NONE, h.fault());
			assertEquals(1, h.latest().cars().size());
			assertEquals(1, h.latest().car(1).team());
			assertEquals(6, h.latest().car(1).preset());
		}
	}

	@Test
	void lockstep_wait_is_capped_while_stalled() throws Exception {
		try (SimHost h = SimHost.start("t-cap", 0, 0, 0, MIN_Y, Mode.LOCKSTEP)) {
			h.stepLockstep(6);
			assertEquals(6, h.latest().tick());

			CountDownLatch release = SimTestHooks.blockNow(h);
			long t0 = System.nanoTime();
			h.stepLockstep(6);
			long first = System.nanoTime() - t0;
			t0 = System.nanoTime();
			h.stepLockstep(6);
			long second = System.nanoTime() - t0;
			assertTrue(first < 700_000_000L, "the first call took " + first / 1_000_000 + " ms");
			assertTrue(first >= 400_000_000L, "the first call returned before the cap: " + first / 1_000_000 + " ms");
			assertTrue(second < 700_000_000L, "the second call took " + second / 1_000_000 + " ms");
			assertEquals(6, h.latest().tick(), "nothing ran while blocked");

			release.countDown();
			h.flush();
			assertEquals(12, h.latest().tick(), "two calls while stalled must queue one 6-tick job");
			h.stepLockstep(6);
			assertEquals(18, h.latest().tick(), "after the stall a call posts its own job again");
		}
	}

	@Test
	void abandoned_host_touches_only_its_own_world() throws Exception {
		SyntheticTerrain terrain = new SyntheticTerrain();
		SimHost old = SimHost.start("t-iso", 0, 0, 0, MIN_Y, Mode.LOCKSTEP);
		SimHost nu = null;
		try {
			old.addCar(1, carAt(8.5, 65, 8.5), 0, 0);
			feedAll(old, terrain);
			old.stepLockstep(6);
			assertEquals(6, old.latest().tick());
			CountDownLatch release = SimTestHooks.blockNow(old);
			old.stepLockstep(6);
			old.abandon();

			nu = SimHost.start("t-iso", 0, 0, 0, MIN_Y, Mode.LOCKSTEP);
			nu.addCar(1, carAt(8.5, 65, 8.5), 0, 0);
			feedAll(nu, terrain);
			nu.stepLockstep(12);
			SimFrame nuBefore = nu.latest();
			int nuSections = nu.sectionCount();
			assertEquals(12, nuBefore.tick());
			assertTrue(nuSections > 0);

			release.countDown();
			long deadline = System.nanoTime() + 2_000_000_000L;
			while (!old.isTerminated() && System.nanoTime() < deadline) {
				Thread.sleep(5);
			}
			assertTrue(old.isTerminated(), "the released old thread did not end within 2 s");
			assertEquals(12, old.latest().tick(), "the old thread ran its own late 6-tick job and published it to itself");
			assertEquals(0, old.sectionCount(), "the old world was destroyed");
			assertTrue(nuBefore == nu.latest(), "something published to the new host's frame");
			assertEquals(nuSections, nu.sectionCount(), "the new host's sections changed");
			nu.stepLockstep(240);
			SimFrame f = nu.latest();
			assertEquals(252, f.tick(), "the new host stepped on after the old thread ended");
			assertEquals(0, f.flags() & (SimFrame.FAULT | SimFrame.FROZEN), "flags " + f.flags());
			assertEquals(64.34, f.car(1).cur().y(), 0.02, "the new host's car rests on its own terrain");
		} finally {
			old.close();
			if (nu != null) {
				nu.close();
			}
		}
	}

	@Test
	void restart_reenters_a_non_finite_car_upright_at_rest() {
		CarState ok = CarState.of(new Pose(1.5, 70, -3.25, 0.7071068f, 0, 0.7071068f, 0), 3, -1, 2, 0.5, 0, -0.25);
		assertTrue(SimHosts.reentryState(ok, 9, 9, 9, 45f) == ok, "a finite state must enter unchanged");
		Pose upright = Pose.upright(10.5, 64.34, -20.5, -90f);
		CarState[] bad = {
				CarState.of(new Pose(Double.NaN, 70, 0, 1, 0, 0, 0), 0, 0, 0, 0, 0, 0),
				CarState.of(new Pose(0, 70, 0, Float.NaN, 0, 0, 0), 0, 0, 0, 0, 0, 0),
				CarState.of(new Pose(0, 70, 0, 1, 0, 0, 0), Double.POSITIVE_INFINITY, 0, 0, 0, 0, 0),
				CarState.of(new Pose(0, 70, 0, 1, 0, 0, 0), 0, 0, 0, 0, Double.NaN, 0)};
		for (CarState s : bad) {
			CarState r = SimHosts.reentryState(s, 10.5, 64.34, -20.5, -90f);
			assertEquals(upright, r.pose(), "from " + s);
			assertArrayEquals(new double[6], new double[] {r.vx(), r.vy(), r.vz(), r.wx(), r.wy(), r.wz()}, "at rest");
		}
		CarState lost = CarState.of(new Pose(Double.NaN, 70, 0, 1, 0, 0, 0), 0, 0, 0, 0, 0, 0);
		assertTrue(SimHosts.reentryState(lost, Double.NaN, 64, 0, 0f) == lost,
				"without a finite entity position the state stays (the car then waits out of the sim)");
	}

	@Test
	void predictionRoleHasNoRules() throws Exception {
		try (SimHost h = SimHost.start("t-predict", 0, 0, 0, MIN_Y, Mode.CLIENT_PACED, SimHost.Kind.TERRAIN,
				SimHost.Role.PREDICTION)) {
			String[] name = new String[1];
			h.runOnSimThread(() -> name[0] = Thread.currentThread().getName()).get(10, TimeUnit.SECONDS);
			assertEquals("mcrl-sim/predict:t-predict", name[0]);
			h.addCar(1, carAt(8.5, MIN_Y - 20, 8.5), 0, 0);
			h.addCar(2, carAt(300.5, 65, 8.5), 0, 0);
			h.setBall(new BallState(new Pose(8.5, 80, 20.5, 1, 0, 0, 0), 0, 0, 0, 0, 0, 0));
			h.flush();
			long t = 1_000_000_000L;
			for (int i = 0; i <= 60; i++, t += 16_666_667L) {
				h.awaitPace(h.pace(t), 5_000_000_000L);
			}
			SimFrame f = h.latest();
			assertTrue(f.tick() >= 100, "paced: " + f.tick());
			assertEquals(MIN_Y - 20, f.car(1).cur().y(), 1e-3, "in the void and still there");
			assertEquals(0, f.car(1).carFlags() & CarState.FLAG_DEMOED);
			assertEquals(List.of(), h.drainCarEvents());
			assertArrayEquals(new int[] {0, 0, 0}, h.origin(), "no recentre");
			assertTrue(h.ringSince(1, Long.MIN_VALUE).size() > TickRing.CAPACITY, "a longer ring");
			assertTrue(h.ringSince(1, Long.MIN_VALUE).size() <= NetTuning.HISTORY_TICKS);
			assertTrue(h.ballRingSince(Long.MIN_VALUE).size() > TickRing.CAPACITY, "the ball's ring");
			assertEquals(SimHost.PredictStats.NONE, h.predictStats());
		}
	}

	private static void assertPose(Pose want, Pose got, double tol, String what) {
		double[] a = {want.x(), want.y(), want.z(), want.qw(), want.qx(), want.qy(), want.qz()};
		double[] b = {got.x(), got.y(), got.z(), got.qw(), got.qx(), got.qy(), got.qz()};
		for (int i = 0; i < a.length; i++) {
			assertEquals(a[i], b[i], tol, what + ": component " + i + " of " + got + ", want " + want);
		}
	}

	private static CarEvent only(SimHost h) {
		List<CarEvent> evs = h.drainCarEvents();
		assertEquals(1, evs.size(), "events " + evs);
		return evs.get(0);
	}

	private static Pose rest(SimHost h, int id) throws Exception {
		h.addCar(id, carAt(8.5, 64.34, 8.5), 0, 0);
		feedAll(h, new SyntheticTerrain());
		h.stepLockstep(120);
		h.drainCarEvents();
		return h.latest().car(id).cur();
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void void_demolishes_once_and_respawns_at_safe_pose(boolean perCarGate) throws Exception {
		try (SimHost h = SimHost.start("t-voidcar-" + perCarGate, 0, 0, 0, MIN_Y, Mode.LOCKSTEP)) {
			h.setPerCarGate(perCarGate);
			Pose safe = rest(h, 1);
			h.setCar(1, CarState.of(new Pose(8.5, MIN_Y - 5, 8.5, 1, 0, 0, 0), 3, -20, 0, 0, 0, 1));
			h.stepLockstep(1);
			long t = h.latest().tick();
			assertTrue(h.latest().car(1).demoed());
			CarEvent.Demolished d = (CarEvent.Demolished) only(h);
			assertEquals(-1, d.attackerId(), "the void");
			assertTrue(d.pose().y() < MIN_Y - RespawnRules.DEMO_VOID_MARGIN, "y " + d.pose().y());

			h.stepLockstep(120);
			assertFalse(frozen(h.latest()), "the gate skips the wreck");
			assertEquals(List.of(), h.drainCarEvents(), "demolished once");
			h.stepLockstep(RespawnRules.RESPAWN_TICKS - 120);
			assertEquals(t + RespawnRules.RESPAWN_TICKS, h.latest().tick());
			SimFrame.CarSnap c = h.latest().car(1);
			assertFalse(c.demoed());
			assertPose(safe, c.cur(), 0.01, "respawned at its safe pose");
			assertTrue(only(h) instanceof CarEvent.Respawned);

			h.stepLockstep(30);
			assertFalse(frozen(h.latest()), "it drives on from its safe pose");
			assertEquals(safe.y(), h.latest().car(1).cur().y(), 0.02, "and rests there");
		}
	}

	@Test
	void car_added_in_the_void_band_is_left_alone() throws Exception {
		try (SimHost h = SimHost.start("t-voidband", 0, 0, 0, MIN_Y, Mode.LOCKSTEP)) {
			h.addCar(1, carAt(8.5, MIN_Y - RespawnRules.DEMO_VOID_MARGIN - 0.5, 8.5), 0, 0);
			h.stepLockstep(800);
			assertEquals(List.of(), h.drainCarEvents());
			assertFalse(h.latest().car(1).demoed());
		}
	}

	private static void ram(SimHost h) throws Exception {
		h.addCar(1, CarState.of(new Pose(8.5, 64.34, 8.5, 1, 0, 0, 0), 45, 0, 0, 0, 0, 0), 0, 0);
		h.addCar(2, CarState.of(Pose.upright(20.5, 64.34, 8.5, 90f), 0, 0, 0, 0, 0, 0), 0, 0);
		feedAll(h, new SyntheticTerrain());
		h.submitInput(1, InputFrame.of(1, 1f, 0f, 0f, 0f, 0f, InputFrame.BOOST, 0));
	}

	@Test
	void car_car_demo_reports_attacker() throws Exception {
		try (SimHost h = SimHost.start("t-ram", 0, 0, 0, MIN_Y, Mode.LOCKSTEP)) {
			ram(h);
			CarEvent.Demolished d = null;
			for (int t = 0; t < 40 && d == null; t++) {
				h.stepLockstep(1);
				assertFalse(h.latest().car(1).demoed(), "car 1 is never demoed");
				for (CarEvent e : h.drainCarEvents()) {
					if (e instanceof CarEvent.Demolished x) {
						d = x;
					}
				}
			}
			assertNotNull(d, "no demolition within 40 ticks");
			assertEquals(2, d.carId());
			assertEquals(1, d.attackerId(), "the bumper's car");
			assertTrue(h.latest().car(2).demoed());
		}
	}

	@Test
	void wreck_carries_its_respawn_and_ignores_sets() throws Exception {
		try (SimHost h = SimHost.start("t-wreck", 0, 0, 0, MIN_Y, Mode.LOCKSTEP)) {
			Pose safe = rest(h, 1);
			assertNull(h.latest().car(1).respawnAt(), "not a wreck");
			long t0 = h.latest().tick();
			h.demolish(1, -1);
			h.flush();
			SimFrame.CarSnap w = h.latest().car(1);
			assertNotNull(w.respawnAt());
			assertPose(safe, w.respawnAt(), 0.01, "planned respawn");
			h.drainCarEvents();
			h.setCar(1, carAt(30.5, 70, 8.5));
			h.flush();
			assertPose(w.cur(), h.latest().car(1).cur(), 1e-9, "the wreck stays");
			assertTrue(h.latest().car(1).demoed());
			h.stepLockstep(10);
			List<TickRing.Entry> ring = h.ringSince(1, t0);
			assertFalse(ring.isEmpty());
			for (TickRing.Entry e : ring) {
				assertTrue(e.wreck(), "tick " + e.tick());
			}
			h.stepLockstep(RespawnRules.RESPAWN_TICKS - 10);
			SimFrame.CarSnap back = h.latest().car(1);
			assertFalse(back.demoed());
			assertNull(back.respawnAt());
			assertPose(w.respawnAt(), back.cur(), 0.01, "respawned where planned");
			long t1 = h.latest().tick();
			h.stepLockstep(5);
			for (TickRing.Entry e : h.ringSince(1, t1)) {
				assertFalse(e.wreck(), "tick " + e.tick());
			}
			assertEquals(List.of(CarEvent.Respawned.class), h.drainCarEvents().stream().map(Object::getClass).toList());
		}
	}

	@Test
	void absurd_positions_from_java_are_clamped() throws Exception {
		try (SimHost h = SimHost.start("t-clamppos", 0, 0, 0, MIN_Y, Mode.LOCKSTEP)) {
			h.addCar(1, carAt(1e12, 1e10, -1e12), 0, 0);
			h.flush();
			assertEquals(0, h.latest().flags() & SimFrame.FAULT);
			Pose p = h.latest().car(1).cur();
			assertEquals(Pose.MAX_XZ, p.x(), 3, "x");
			assertEquals(Pose.MAX_Y, p.y(), 3, "y");
			assertEquals(-Pose.MAX_XZ, p.z(), 3, "z");
			h.stepLockstep(1);
			feedAll(h, new SyntheticTerrain());
			h.stepLockstep(1);
			assertFalse(frozen(h.latest()), "the car's swept box is finite and fed: the gate passes");

			h.setBall(new BallState(new Pose(-1e300, 70, 8.5, 1, 0, 0, 0), 0, 0, 0, 0, 0, 0));
			h.flush();
			assertEquals(-Pose.MAX_XZ, h.latest().ball().cur().x(), 3, "the ball too");
		}
	}

	private static final double[][] KICKOFF_SPOTS = {{-40.96, -51.2}, {40.96, -51.2}, {-5.12, -76.8}, {5.12, -76.8},
			{0, -92.16}};

	private static SimHost soccar(String name) throws ArenaMeshes.Missing {
		return SimHost.startSoccar(name, TestArena.load(), Mode.LOCKSTEP, SimHost.Role.SERVER);
	}

	private static boolean onSpot(Pose p, double[][] spots, double sx, double sz, double tol) {
		for (double[] s : spots) {
			if (Math.abs(p.x() - sx * s[0]) <= tol && Math.abs(p.z() - sz * s[1]) <= tol) {
				return true;
			}
		}
		return false;
	}

	private static void steps(SimHost h, int ticks) {
		for (int done = 0; done < ticks; done += 6) {
			h.stepLockstep(Math.min(6, ticks - done));
		}
	}

	@Test
	void soccar_kickoff_places_and_locks() throws Exception {
		try (SimHost h = soccar("t-soccar-kickoff")) {
			assertEquals(SimHost.Kind.SOCCAR, h.kind());
			h.addCar(1, carAt(10, 64.34, 10), 0, 0);
			h.addCar(2, carAt(-10, 64.34, -10), 1, 0);
			h.setBall(new BallState(new Pose(20, 70, 20, 1, 0, 0, 0), 0, 0, 0, 0, 0, 0));
			h.kickoff(5, 360);
			h.flush();
			SimFrame f0 = h.latest();
			SoccarState s0 = f0.soccar();
			assertNotNull(s0);
			assertEquals(1, s0.kickoffs());
			assertEquals(0, s0.kickoffTick());
			assertEquals(360, s0.lockTicks());
			assertFalse(s0.ballOutOfPlay());
			for (int id = 1; id <= 2; id++) {
				SimFrame.CarSnap c = f0.car(id);
				assertEquals(c.cur(), c.prev(), "a kickoff is a teleport: car " + id);
				assertTrue(onSpot(c.cur(), KICKOFF_SPOTS, id == 1 ? 1 : -1, id == 1 ? 1 : -1, 1e-3), "car " + id + " at "
						+ c.cur());
				assertEquals(100f / 3, c.boost(), 0.01);
			}
			assertEquals(f0.ball().cur(), f0.ball().prev(), "the ball is placed too");
			assertEquals(0, f0.ball().cur().x(), 1e-3);
			assertEquals(65.863, f0.ball().cur().y(), 1e-3);
			assertEquals(0, f0.ball().cur().z(), 1e-3);

			h.stepLockstep(6);
			SimFrame f = h.latest();
			assertEquals(6, f.tick());
			SoccarState s = f.soccar();
			assertEquals(1, s.kickoffs());
			assertEquals(0, s.kickoffTick(), "the tick before the step");
			assertEquals(354, s.lockTicks());
			for (int id = 1; id <= 2; id++) {
				assertTrue(onSpot(f.car(id).cur(), KICKOFF_SPOTS, id == 1 ? 1 : -1, id == 1 ? 1 : -1, 0.02), "car " + id);
			}
			assertEquals(65.863, f.ball().cur().y(), 1e-3);
		}
	}

	private static SoccarState shot(String name, int per) throws Exception {
		try (SimHost h = soccar(name)) {
			h.kickoff(1, 0);
			h.setBall(new BallState(new Pose(0, 65.863, 96, 1, 0, 0, 0), 0, 0, 40, 0, 0, 0));
			for (int t = 0; t < 240; t += per) {
				h.stepLockstep(per);
				SoccarState s = h.latest().soccar();
				if (s.goals() > 0) {
					return s;
				}
			}
			return h.latest().soccar();
		}
	}

	@Test
	void soccar_goal_in_frame() throws Exception {
		SoccarState one = shot("t-soccar-goal-1", 1);
		assertEquals(1, one.goals());
		long latch = one.goalTick();
		SoccarState six = shot("t-soccar-goal-6", 6);
		assertEquals(1, six.goals());
		assertEquals(latch, six.goalTick(), "the latch tick inside a 6-tick step");
		assertEquals(0, six.goalTeam());
		assertTrue(six.ballOutOfPlay());
		assertTrue(six.gz() > 104.31, "goal z " + six.gz());
		assertEquals(-1, six.goalToucher());
	}

	private static BotPolicy constantPolicy(int k) {
		return new BotPolicy(java.nio.file.Path.of("constant-" + k), new int[] {89, 90},
				PolicyFiles.constant(k).toArray(new float[0][]));
	}

	private static SimHost botDuel(String name) throws Exception {
		SimHost h = soccar(name);
		h.addCar(1, carAt(-20, 64.34, -30), 0, 0);
		h.addCar(2, carAt(20, 64.34, 30), 1, 0);
		BotPolicy p = constantPolicy(18);
		h.addBot(1, new BotPolicies.Loaded(p.source(), p), 0, 1);
		return h;
	}

	@Test
	void bot_ignores_submitted_input() throws Exception {
		try (SimHost h = botDuel("t-bot-input")) {
			h.kickoff(5, 0);
			h.submitInput(1, InputFrame.of(1, -1f, 0f, 0f, 0f, 0f, 0, 0));
			steps(h, 12);
			assertEquals(1f, h.latest().car(1).applied().throttle(), "the bot's throttle, not the submitted -1");
		}
	}
}
