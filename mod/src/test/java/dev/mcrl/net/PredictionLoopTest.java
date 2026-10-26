package dev.mcrl.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;

import dev.mcrl.client.link.LeadControl;
import dev.mcrl.client.link.Prediction;
import dev.mcrl.match.ArenaMeshes;
import dev.mcrl.math.Pose;
import dev.mcrl.net.NetPayloads.InputC2S;
import dev.mcrl.net.SnapshotSender.Recipient;
import dev.mcrl.sim.BallState;
import dev.mcrl.sim.CarState;
import dev.mcrl.sim.InputFrame;
import dev.mcrl.sim.SimClock.Mode;
import dev.mcrl.sim.SimFrame;
import dev.mcrl.sim.SimHost;
import dev.mcrl.sim.SoccarState;
import dev.mcrl.sim.SyntheticTerrain;
import dev.mcrl.terrain.Grid;
import dev.mcrl.terrain.GridFiller;
import dev.mcrl.terrain.MeshWorkers;
import dev.mcrl.testkit.NetSim;
import dev.mcrl.testkit.TestArena;

class PredictionLoopTest {
	private static final long DT = 8_333_333L;
	private static final long FRAME = 16_666_667L;
	private static final long SECOND = 1_000_000_000L;
	private static final int MIN_Y = -64;
	private static final int OWN = 1, REMOTE = 2;
	private static final double CAR_Y = 64.34, BALL_Y = 65.863;
	private static final AtomicInteger VERSIONS = new AtomicInteger(1_000_000);

	private static void feed(SimHost h, SyntheticTerrain src, Set<SimHost.SectionKey> fed) throws Exception {
		feed(h, src, fed, k -> true);
	}

	private static void feed(SimHost h, SyntheticTerrain src, Set<SimHost.SectionKey> fed,
			Predicate<SimHost.SectionKey> where) throws Exception {
		h.flush();
		List<CompletableFuture<Integer>> fs = new ArrayList<>();
		for (SimHost.SectionKey k : h.neededSections()) {
			if (!where.test(k) || !fed.add(k)) {
				continue;
			}
			Grid grid = k.y() < src.minSectionY() || k.y() > src.maxSectionY()
					? new Grid(new short[GridFiller.N * GridFiller.N * GridFiller.N], false)
					: src.fillGrid(k.x(), k.y(), k.z());
			fs.add(MeshWorkers.submit(k, grid.cells(), null, VERSIONS.incrementAndGet(), h));
		}
		CompletableFuture.allOf(fs.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS);
		h.flush();
	}

	private static double dist(Pose a, Pose b) {
		return Math.sqrt(sq(a.x() - b.x()) + sq(a.y() - b.y()) + sq(a.z() - b.z()));
	}

	private static double sq(double v) {
		return v * v;
	}

	private static final class Loop implements AutoCloseable {
		final SyntheticTerrain terrain = new SyntheticTerrain();
		SimHost server;
		final SimHost client;
		Prediction p;
		SnapshotSender sender;
		final NetSim.Queue<byte[]> toClient;
		final NetSim.Queue<InputC2S> toServer;
		final Set<SimHost.SectionKey> serverFed = new HashSet<>(), clientFed = new HashSet<>();
		long now, nextTick, nextFrame, nextFeed;
		final Map<Long, Pose> serverOwn = new HashMap<>();
		final List<Object[]> predictedOwn = new ArrayList<>();
		final List<SimHost.Correction> corrections = new ArrayList<>();
		final List<Long> correctedAt = new ArrayList<>();
		LongFunction<InputFrame> script = t -> InputFrame.NEUTRAL;
		int ownId = OWN;
		Predicate<SimHost.SectionKey> clientTerrain = k -> true;
		LongConsumer serverHook = t -> { };
		final List<Long> anchorTimes = new ArrayList<>();
		private long anchorsSeen;
		int seq;

		Loop(NetSim.Params up, NetSim.Params down) throws Exception {
			this(up, down, false);
		}

		Loop(NetSim.Params up, NetSim.Params down, boolean productionAwait) throws Exception {
			this(up, down, productionAwait, null);
		}

		Loop(NetSim.Params up, NetSim.Params down, boolean productionAwait, ArenaMeshes.@Nullable Set stadium)
				throws Exception {
			toClient = new NetSim.Queue<>(down);
			toServer = new NetSim.Queue<>(up);
			if (stadium == null) {
				server = startServer(0, null);
				client = SimHost.start("loop-predict", 0, 0, 0, MIN_Y, Mode.CLIENT_PACED, SimHost.Kind.TERRAIN,
						SimHost.Role.PREDICTION);
			} else {
				server = SimHost.startSoccar("loop-stadium", stadium, Mode.LOCKSTEP, SimHost.Role.SERVER);
				CarState at = CarState.of(new Pose(0.5, CAR_Y, 0.5, 1, 0, 0, 0), 0, 0, 0, 0, 0, 0);
				server.addCar(OWN, at, 0, 0);
				server.addCar(REMOTE, at, 1, 0);
				server.flush();
				sender = new SnapshotSender(SimHost.Kind.SOCCAR, server::inputStats,
						(r, snap) -> toClient.offer(snap.data(), now));
				server.setSnapshotListener(sender);
				nextTick = DT;
				client = SimHost.startSoccar("loop-stadium-predict", stadium, Mode.CLIENT_PACED,
						SimHost.Role.PREDICTION);
			}
			p = new Prediction(client, new LeadControl(), new InputHistory(),
					productionAwait ? Prediction.FRAME_WAIT_NANOS : 10 * SECOND);
			nextFrame = FRAME;
		}

		SimHost startServer(long firstTickNanos, Pose at) throws Exception {
			SimHost s = SimHost.start("loop-server", 0, 0, 0, MIN_Y, Mode.LOCKSTEP);
			Pose own = at != null ? at : new Pose(0.5, CAR_Y, 0.5, 1, 0, 0, 0);
			s.addCar(OWN, CarState.of(own, 0, 0, 0, 0, 0, 0), 0, 0);
			s.setBall(new BallState(new Pose(own.x() + 15, BALL_Y, own.z(), 1, 0, 0, 0), 0, 0, 0, 0, 0, 0));
			serverFed.clear();
			feed(s, terrain, serverFed);
			sender = new SnapshotSender(SimHost.Kind.TERRAIN, s::inputStats, (r, snap) -> toClient.offer(snap.data(), now));
			s.setSnapshotListener(sender);
			nextTick = firstTickNanos + DT;
			return s;
		}

		void runTo(long end) throws Exception {
			while (true) {
				long next = Math.min(nextTick, nextFrame);
				if (next > end) {
					return;
				}
				now = next;
				if (now >= nextFeed) {
					feed(server, terrain, serverFed);
					feed(client, terrain, clientFed, clientTerrain);
					nextFeed = now + 50_000_000L;
				}
				if (now == nextTick) {
					serverTick();
				}
				if (now == nextFrame) {
					clientFrame();
				}
			}
		}

		void serverTick() {
			serverHook.accept(now);
			for (InputC2S in : toServer.poll(now)) {
				if (ownId >= 0) {
					server.submitInputs(ownId, in.firstTick(), in.epoch(), in.ticks());
				}
			}
			SimFrame f = server.latest();
			SimFrame.CarSnap own = ownId < 0 ? null : f.car(ownId);
			if (own != null) {
				sender.setRecipients(List.of(new Recipient(null, own.cur().x(), own.cur().y(), own.cur().z(), ownId,
						NetMode.PREDICT)));
			} else if (ownId < 0) {
				sender.setRecipients(List.of(new Recipient(null, 0, 70, 0, -1, NetMode.PREDICT)));
			}
			server.stepLockstep(1);
			SimFrame g = server.latest();
			if (g.car(OWN) != null) {
				serverOwn.put(g.tick(), g.car(OWN).cur());
			}
			nextTick += DT;
		}

		void clientFrame() {
			for (byte[] data : toClient.poll(now)) {
				p.onSnapshot(SnapshotCodec.decode(Unpooled.wrappedBuffer(data)), now, 150);
			}
			SimFrame f = p.frame(now, ownId < 0 ? null : script.apply(now), ownId);
			long anchors = p.anchors();
			if (anchors != anchorsSeen) {
				anchorsSeen = anchors;
				anchorTimes.add(now);
			}
			SimFrame.CarSnap own = f.car(OWN);
			if (own != null) {
				predictedOwn.add(new Object[] {f.tick(), own.cur()});
			}
			for (SimHost.Correction c : p.drainCorrections()) {
				corrections.add(c);
				correctedAt.add(now);
			}
			for (InputC2S in : p.takeOutgoing()) {
				toServer.offer(in, now);
			}
			nextFrame += FRAME;
		}

		InputFrame drive(float throttle, float steer, boolean boost, boolean jumpEdge, boolean jumpHeld) {
			int buttons = (boost ? InputFrame.BOOST : 0) | (jumpHeld || jumpEdge ? InputFrame.JUMP : 0);
			return InputFrame.of(++seq, throttle, steer, 0, steer, 0, buttons, jumpEdge ? InputFrame.JUMP : 0);
		}

		List<SimHost.Correction> own() {
			return corrections.stream().filter(c -> c.bodyId() == OWN).toList();
		}

		long anchorsAfter(long fromNanos) {
			return anchorTimes.stream().filter(t -> t >= fromNanos).count();
		}

		double maxOwnAfter(long fromNanos) {
			double m = 0;
			for (int i = 0; i < corrections.size(); i++) {
				SimHost.Correction c = corrections.get(i);
				if (c.bodyId() == OWN && correctedAt.get(i) >= fromNanos) {
					m = Math.max(m, Math.sqrt(sq(c.dx()) + sq(c.dy()) + sq(c.dz())));
				}
			}
			return m;
		}

		@Override
		public void close() {
			server.close();
			client.close();
		}
	}

	private static final NetSim.Params UP = new NetSim.Params(75, 10, 0.01, 1);
	private static final NetSim.Params DOWN = new NetSim.Params(75, 10, 0.01, 2);

	private static InputFrame script(Loop l, long t) {
		double s = t / 1e9;
		if (s < 2) {
			return l.drive(1, 0, false, false, false);
		}
		if (s < 3) {
			return l.drive(1, 0.5f, true, false, false);
		}
		if (s < 4) {
			return l.drive(1, 0, false, t >= 3 * SECOND && t < 3 * SECOND + FRAME, s < 3.2);
		}
		return l.drive(0, 0, false, false, false);
	}

	private static void ownCar(NetSim.Params up, NetSim.Params down, double steady) throws Exception {
		ownCar(up, down, steady, true);
	}

	private static void ownCar(NetSim.Params up, NetSim.Params down, double steady, boolean bounded)
			throws Exception {
		try (Loop l = new Loop(up, down)) {
			l.script = t -> script(l, t);
			l.runTo(6 * SECOND);
			SimHost.PredictStats ps = l.client.predictStats();
			List<SimHost.Correction> own = l.own();
			double maxOwn = own.stream().mapToDouble(c -> Math.sqrt(sq(c.dx()) + sq(c.dy()) + sq(c.dz()))).max()
					.orElse(0);
			double maxPath = 0, pathSettled = 0;
			int compared = 0;
			for (Object[] e : l.predictedOwn) {
				Pose srv = l.serverOwn.get((Long) e[0]);
				if (srv != null) {
					double d = dist(srv, (Pose) e[1]);
					maxPath = Math.max(maxPath, d);
					if ((Long) e[0] >= 60) {
						pathSettled = Math.max(pathSettled, d);
					}
					compared++;
				}
			}
			SimFrame end = l.server.latest();
			double settled = l.maxOwnAfter(SECOND / 2);
			assertEquals(0, own.stream().filter(SimHost.Correction::snap).count(), "no own snaps");
			assertTrue(ps.accepted() + ps.restored() >= 250, "reconciles keep coming: " + ps);
			assertEquals(0, l.anchorsAfter(SECOND), "no anchor after 1 s: " + l.anchorTimes);
			if (!bounded) {
				return;
			}
			assertTrue(end.car(OWN).cur().x() > 40, "the server's car drove: " + end.car(OWN).cur().x());
			assertTrue(end.car(OWN).ballHits() >= 1, "it hits the ball");
			assertTrue(maxOwn <= 0.05, "own corrections at most 0.05 block: " + maxOwn);
			assertTrue(settled <= steady, "own corrections from 0.5 s at most " + steady + " block: " + settled);
			assertTrue(ps.accepted() >= 0.9 * (ps.accepted() + ps.restored()), ps.toString());
			if (up.loss() > 0) {
				assertTrue(ps.restored() >= 1, "a lossy link restores at least once: " + ps);
			}
			assertTrue(compared > 200, "compared " + compared);
			assertTrue(maxPath <= 0.05, "the predicted own path: " + maxPath);
			assertTrue(pathSettled <= steady, "the predicted own path from 0.5 s: " + pathSettled);
		}
	}

	@Test
	void ownCarNoRubberBand() throws Exception {
		ownCar(UP, DOWN, 0.05);
		ownCar(new NetSim.Params(75, 10, 0, 1), new NetSim.Params(75, 10, 0, 2), 0.01);
	}

	@Test
	void removedBallGoes() throws Exception {
		try (Loop l = new Loop(new NetSim.Params(75, 10, 0, 1), new NetSim.Params(75, 10, 0, 2))) {
			l.runTo(SECOND);
			assertTrue(l.client.latest().ball() != null, "the prediction has the ball");
			l.server.removeBall();
			l.server.flush();
			l.runTo(SECOND + 3 * SECOND / 4);
			assertTrue(l.client.latest().ball() == null, "gone from the prediction 60 ticks and a trip after");
		}
	}

	private static final double BALL_REST_Y = 64 + 93.15 / 50;

	private static ArenaMeshes.Set stadium() throws Exception {
		return TestArena.load();
	}

	@Test
	void goalResetSnaps() throws Exception {
		try (Loop l = new Loop(UP, DOWN, false, stadium())) {
			l.server.setBall(new BallState(new Pose(0, BALL_REST_Y, 0, 1, 0, 0, 0), 0, 0, 0, 0, 0, 0));
			l.server.kickoff(1, 0);
			l.server.flush();
			l.server.submitInput(REMOTE, InputFrame.of(1, 1, 0, 0, 0, 0, 0, 0));
			l.script = t -> l.drive(t < SECOND ? 1 : 0, 0, false, false, false);
			l.runTo(SECOND);
			l.server.submitInput(REMOTE, InputFrame.of(2, 0, 0, 0, 0, 0, 0, 0));
			l.runTo(2 * SECOND);
			l.server.setBall(new BallState(new Pose(0, BALL_REST_Y, 98, 1, 0, 0, 0), 0, 0, 40, 0, 0, 0));
			l.runTo(3 * SECOND + SECOND / 2);
			SoccarState goal = l.server.latest().soccar();
			int before = l.corrections.size();
			l.server.kickoff(2, 120);
			l.runTo(5 * SECOND + SECOND / 2);
			Map<Integer, Integer> snaps = new HashMap<>();
			for (int i = before; i < l.corrections.size(); i++) {
				SimHost.Correction c = l.corrections.get(i);
				if (c.snap()) {
					snaps.merge(c.bodyId(), 1, Integer::sum);
				}
			}
			assertEquals(1, goal.goals(), "the scripted goal");
			assertTrue(goal.ballOutOfPlay(), "the goal took the ball out of play");
			assertEquals(1, snaps.getOrDefault(OWN, 0), "the own car snaps once at the reset: " + snaps);
			assertEquals(1, snaps.getOrDefault(REMOTE, 0), "the remote car snaps once at the reset: " + snaps);
			assertTrue(snaps.getOrDefault(-1, 0) <= 1, "the ball snaps at most once: " + snaps);
			snaps.keySet().removeAll(Set.of(OWN, REMOTE, -1));
			assertTrue(snaps.isEmpty(), "no other snaps: " + snaps);
		}
	}

}
