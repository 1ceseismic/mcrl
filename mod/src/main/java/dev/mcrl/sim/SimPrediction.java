package dev.mcrl.sim;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.mcrl.match.MatchBlock;
import dev.mcrl.math.Pose;
import dev.mcrl.natives.Breadcrumb;
import dev.mcrl.natives.Breadcrumb.Op;
import dev.mcrl.natives.Layouts;
import dev.mcrl.natives.McrlNative;
import dev.mcrl.net.ArenaBlock;
import dev.mcrl.net.InputHistory;
import dev.mcrl.net.NetProtocol;
import dev.mcrl.net.NetTuning;
import dev.mcrl.net.Snapshot;
import dev.mcrl.sim.SimHost.Correction;
import dev.mcrl.sim.SimHost.Kind;
import dev.mcrl.sim.SimHost.PredictStats;
import dev.mcrl.sim.SimHost.Published;
import dev.mcrl.sim.SimWorld.CarRec;

// Sim thread only, except where marked any thread.
final class SimPrediction {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static final int BALL = -1;

	private static final class BlobRing {
		final long[] tickOf;
		final byte[][] data;
		final boolean[] held;

		BlobRing(int capacity, int size) {
			tickOf = new long[capacity];
			Arrays.fill(tickOf, Long.MIN_VALUE);
			data = new byte[capacity][size];
			held = new boolean[capacity];
		}

		void put(long tick, MemorySegment blob, boolean heldThen) {
			int k = (int) Math.floorMod(tick, (long) tickOf.length);
			MemorySegment.copy(blob, JAVA_BYTE, 0, data[k], 0, data[k].length);
			tickOf[k] = tick;
			held[k] = heldThen;
		}

		byte @Nullable [] get(long tick) {
			int k = (int) Math.floorMod(tick, (long) tickOf.length);
			return tickOf[k] == tick ? data[k] : null;
		}

		boolean heldAt(long tick) {
			int k = (int) Math.floorMod(tick, (long) tickOf.length);
			return tickOf[k] == tick && held[k];
		}

		void truncateAfter(long tick) {
			for (int k = 0; k < tickOf.length; k++) {
				if (tickOf[k] > tick) {
					tickOf[k] = Long.MIN_VALUE;
				}
			}
		}
	}

	private final SimWorld w;
	private final SimSections sections;
	private final IntConsumer runTicks;

	private volatile int ownCar = -1;
	private volatile @Nullable InputHistory ownHistory;
	private Controls replayOwnLast = Controls.NEUTRAL;
	private int replayOwnMisses;
	private boolean replayOwnHit;
	private int ownLiveCar = -1;
	private long ownLiveTick, replayFillAfter = Long.MAX_VALUE;
	private boolean replaying;
	boolean reconciling;
	boolean ballParked;
	private long ballMentioned = Long.MIN_VALUE;
	private final Map<Integer, BlobRing> blobRings = new HashMap<>();
	private final Set<Integer> releasedStale = new HashSet<>();
	private long[] padTicks = new long[0], padMasks = new long[0];
	private final Map<Integer, Long> mentioned = new HashMap<>();
	private final List<Correction> pendingCorrections = new ArrayList<>();
	private final Object publishLock = new Object();
	private List<Correction> publishedCorrections = new ArrayList<>();
	private volatile PredictStats stats = PredictStats.NONE;
	private boolean loadFailed;

	SimPrediction(SimWorld w, SimSections sections, IntConsumer runTicks) {
		this.w = w;
		this.sections = sections;
		this.runTicks = runTicks;
	}

	// Any thread.
	void setOwnCar(int carId, @Nullable InputHistory history) {
		ownHistory = history;
		ownCar = carId;
	}

	PredictStats stats() {
		return stats;
	}

	boolean replayControls(CarRec c) {
		if (!replaying || c.id != ownCar) {
			return false;
		}
		InputHistory h = ownHistory;
		Controls past = h == null ? null : h.get(w.tick + 1);
		replayOwnHit = past != null;
		c.inputRing = null;
		c.appliedEdges = 0;
		c.applied = past != null ? past
				: replayOwnMisses + 1 > NetProtocol.HOLD_TICKS ? Controls.NEUTRAL : replayOwnLast;
		return true;
	}

	void recordControls() {
		InputHistory h = ownHistory;
		long tick = w.tick;
		for (CarRec c : w.cars.values()) {
			if (h != null && c.id == ownCar && (!replaying || tick > replayFillAfter && tick > h.newest())) {
				h.put(tick, c.given);
			}
			if (c.id == ownCar && !replaying) {
				ownLiveCar = c.id;
				ownLiveTick = tick;
			}
			if (replaying && c.id == ownCar) {
				replayOwnMisses = replayOwnHit ? 0 : replayOwnMisses + 1;
				replayOwnLast = c.applied;
			}
		}
	}

	void recordBlobs() {
		long tick = w.tick;
		if (w.ball != null) {
			if (!ballParked && !replaying && !reconciling && heldAt(BALL, tick - 1)) {
				releasedStale.add(BALL);
			}
			McrlNative.ballSave(w.handle, w.ballBlobSeg);
			blobRings.computeIfAbsent(BALL, k -> new BlobRing(w.ringCapacity, Layouts.BALL_BLOB_SIZE))
					.put(tick, w.ballBlobSeg, ballParked);
		}
		for (CarRec c : w.cars.values()) {
			boolean held = (c.cur.flags() & CarState.FLAG_HELD) != 0;
			if (!held && !replaying && !reconciling && c.id != ownCar && heldAt(c.id, tick - 1)) {
				releasedStale.add(c.id);
			}
			McrlNative.carSave(w.handle, c.id, w.carBlobSeg);
			blobRings.computeIfAbsent(c.id, k -> new BlobRing(w.ringCapacity, Layouts.CAR_BLOB_SIZE))
					.put(tick, w.carBlobSeg, held);
		}
	}

	void recordPads() {
		SoccarState sc = w.soccar;
		if (sc == null) {
			return;
		}
		int n = w.ringCapacity;
		if (padTicks.length != n) {
			padTicks = new long[n];
			padMasks = new long[n];
			Arrays.fill(padTicks, Long.MIN_VALUE);
		}
		int k = (int) Math.floorMod(w.tick, (long) n);
		padTicks[k] = w.tick;
		padMasks[k] = padMask(sc.padActive());
	}

	void forget(int carId) {
		blobRings.remove(carId);
		mentioned.remove(carId);
		releasedStale.remove(carId);
	}

	boolean releasedStale(int carId) {
		return releasedStale.contains(carId);
	}

	boolean ballHeld() {
		return w.ball != null && (ballParked || releasedStale.contains(BALL));
	}

	void publish(SimFrame frame, AtomicReference<SimFrame> latest) {
		synchronized (publishLock) {
			latest.set(frame);
			publishedCorrections.addAll(pendingCorrections);
		}
		pendingCorrections.clear();
	}

	// Any thread.
	Published take(AtomicReference<SimFrame> latest) {
		synchronized (publishLock) {
			List<Correction> cs = publishedCorrections;
			publishedCorrections = new ArrayList<>();
			return new Published(latest.get(), cs);
		}
	}

	boolean reconcile(Snapshot s, long targetTick, Map<Integer, Long> alsoMentioned) {
		for (Snapshot.Car c : s.cars()) {
			if (c.id() < 0) {
				LOG.warn("mcrl: prediction {}: a snapshot with car id {} ignored", w.dimName, c.id());
				return false;
			}
		}
		long st = s.tick();
		long target = Math.max(targetTick == SimHost.AT_TICK ? w.tick : targetTick, st);
		for (Map.Entry<Integer, Long> e : alsoMentioned.entrySet()) {
			mentioned.merge(e.getKey(), e.getValue(), Math::max);
		}
		if (target - st > 4L * NetTuning.HISTORY_TICKS) {
			LOG.warn("mcrl: prediction {}: a reconcile {} ticks ahead of its snapshot cut to {}", w.dimName, target - st,
					4 * NetTuning.HISTORY_TICKS);
			target = st + 4L * NetTuning.HISTORY_TICKS;
		}
		reconciling = true;
		try {
			reconcileWith(s, st, target);
		} finally {
			reconciling = false;
		}
		return true;
	}

	private void reconcileWith(Snapshot s, long st, long target) {
		boolean shifted = s.ox() != w.ox || s.oz() != w.oz;
		if (shifted) {
			w.shiftOrigin(s.ox(), s.oz());
			blobRings.clear();
			sections.refresh();
		}
		if (s.oy() != w.oy) {
			LOG.warn("mcrl: a snapshot with O.y {} for the prediction host at O.y {}; its heights are off", s.oy(), w.oy);
		}
		int[] o = s.origin();
		for (Snapshot.Car c : s.cars()) {
			mentioned.put(c.id(), st);
		}
		boolean everyBody = s.arena().kind() != ArenaBlock.KIND_TERRAIN;
		for (CarRec c : List.copyOf(w.cars.values())) {
			Long seen = mentioned.get(c.id);
			if (seen == null || seen < st - NetTuning.CAR_STALE_TICKS || everyBody && seen < st) {
				removeCar(c.id);
			}
		}
		if (s.ball() != null) {
			ballMentioned = st;
		} else if (w.ball != null && (everyBody || ballMentioned < st - NetTuning.CAR_STALE_TICKS)) {
			w.removeBall();
			releasedStale.remove(BALL);
		}
		PredictStats was = stats;
		if (target == w.tick && accepts(s, o)) {
			holdRemoteControls(s);
			stats = new PredictStats(was.accepted() + 1, was.restored());
			return;
		}
		Map<Integer, Pose> before = new HashMap<>();
		for (CarRec c : w.cars.values()) {
			before.put(c.id, c.cur.pose());
		}
		Pose ballBefore = w.ball == null ? null : w.ball.cur.pose();
		boolean anchor = target != w.tick;
		replayFillAfter = ownLiveCar == ownCar && ownCar >= 0 ? ownLiveTick : Long.MAX_VALUE;

		McrlNative.worldSetTick(w.handle, st);
		w.tick = st;
		for (Snapshot.Car c : s.cars()) {
			CarRec have = w.cars.get(c.id());
			if (have == null) {
				w.addCar(c.id(), CarState.of(c.head().pose(o), 0, 0, 0, 0, 0, 0), c.team(), c.preset());
			} else if (have.team != c.team() || have.preset != c.preset()) {
				w.reconfigure(c.id(), c.team(), c.preset());
			}
		}
		for (CarRec c : List.copyOf(w.cars.values())) {
			Snapshot.Car sc = s.car(c.id);
			byte[] blob = sc != null ? sc.blob() : ownBlob(c.id, st);
			if (blob == null && sc == null && shifted) {
				continue;
			}
			if (blob == null || !loadCar(c, blob)) {
				removeCar(c.id);
			}
		}
		releasedStale.clear();
		if (w.kind == Kind.SOCCAR && s.arenaBlob() != null) {
			loadSoccar(s.arenaBlob());
		}
		byte[] ballBlob = s.ball() != null ? s.ball().blob() : w.ball != null ? ownBlob(BALL, st) : null;
		if (ballBlob != null) {
			loadBall(ballBlob);
		} else if (w.ball != null) {
			w.removeBall();
		}
		ballParked = false;
		McrlNative.worldRewindEvents(w.handle, st);
		if (w.kind == Kind.SOCCAR) {
			w.readSoccar();
			recordPads();
		}
		for (CarRec c : w.cars.values()) {
			c.ring.truncateAfter(st - 1);
			c.read(w.readCar(c.id));
			c.prev = c.cur.pose();
			w.syncDemoFlag(c);
			c.ring.add(c.entry(st, 0f));
		}
		if (w.ballRing != null) {
			w.ballRing.truncateAfter(st - 1);
		}
		for (BlobRing r : blobRings.values()) {
			r.truncateAfter(st - 1);
		}
		if (w.ball != null) {
			w.ball.read(w.readBall());
			w.ball.placed();
			if (w.ballRing == null) {
				w.ballRing = new TickRing(w.ringCapacity);
			}
			w.ballRing.add(w.ball.entry(st));
		}
		recordBlobs();
		holdRemoteControls(s);
		Snapshot.Car own = s.car(ownCar);
		InputHistory h = ownHistory;
		Controls atS = own != null ? own.applied() : h != null ? h.get(st) : null;
		replayOwnLast = atS != null ? atS : Controls.NEUTRAL;
		replayOwnMisses = 0;

		replaying = true;
		try {
			runTicks.accept((int) (target - st));
		} finally {
			replaying = false;
		}
		sections.refresh();
		for (CarRec c : w.cars.values()) {
			Pose b = before.get(c.id);
			if (b != null) {
				queueCorrection(c.id, b, c.cur.pose(), anchor);
			}
		}
		if (ballBefore != null && w.ball != null) {
			queueCorrection(BALL, ballBefore, w.ball.cur.pose(), anchor);
		}
		stats = new PredictStats(was.accepted(), was.restored() + 1);
	}

	private void removeCar(int id) {
		if (w.removeCar(id)) {
			forget(id);
		}
	}

	// Held remote cars are drawn from snapshots: presence suffices.
	private boolean accepts(Snapshot s, int[] o) {
		if (w.kind == Kind.SOCCAR && s.arena().kind() == ArenaBlock.KIND_SOCCAR) {
			int k = (int) Math.floorMod(s.tick(), (long) Math.max(1, padTicks.length));
			MatchBlock m;
			try {
				m = MatchBlock.decode(s.arena().body());
			} catch (RuntimeException e) {
				return false;
			}
			if (padTicks.length == 0 || padTicks[k] != s.tick() || padMasks[k] != padMask(m.padActive())) {
				return false;
			}
		}
		for (Snapshot.Car sc : s.cars()) {
			CarRec c = w.cars.get(sc.id());
			TickRing.Entry e = c == null ? null : c.ring.at(s.tick());
			Snapshot.CarHead h = sc.head();
			if (releasedStale.contains(sc.id())) {
				return false;
			}
			if (c != null && (c.team != sc.team() || c.preset != sc.preset())) {
				return false;
			}
			if (e != null && sc.id() != ownCar
					&& ((h.flags() & CarState.FLAG_HELD) != 0 || heldAt(sc.id(), s.tick()))) {
				continue;
			}
			boolean wreck = (h.flags() & CarState.FLAG_DEMOED) != 0;
			if (e == null || e.wreck() != wreck
					|| !near(e, h.pose(o), h.vx(), h.vy(), h.vz(), h.wx(), h.wy(), h.wz(), !wreck)) {
				return false;
			}
		}
		Snapshot.Ball sb = s.ball();
		if (sb != null) {
			TickRing r = w.ballRing;
			TickRing.Entry e = r == null ? null : r.at(s.tick());
			Snapshot.BallHead h = sb.head();
			if (releasedStale.contains(BALL)) {
				return false;
			}
			if (e != null && heldAt(BALL, s.tick())) {
				return true;
			}
			return e != null && near(e, h.pose(o), h.vx(), h.vy(), h.vz(), h.wx(), h.wy(), h.wz(), true);
		}
		return true;
	}

	private static boolean near(TickRing.Entry e, Pose p, double vx, double vy, double vz, double wx, double wy,
			double wz, boolean turn) {
		Pose q = e.pose();
		double dp = Math.sqrt(sq(q.x() - p.x()) + sq(q.y() - p.y()) + sq(q.z() - p.z()));
		double dv = Math.sqrt(sq(e.vx() - vx) + sq(e.vy() - vy) + sq(e.vz() - vz));
		if (dp > NetTuning.ACCEPT_POS_BLOCKS || dv > NetTuning.ACCEPT_VEL_BLOCKS_S) {
			return false;
		}
		return !turn || angle(q, p) <= NetTuning.ACCEPT_ROT_RAD
				&& Math.sqrt(sq(e.wx() - wx) + sq(e.wy() - wy) + sq(e.wz() - wz)) <= NetTuning.ACCEPT_ANGVEL_RAD_S;
	}

	// Unlike 2·acos(|a·b|), exact near 0.
	private static double angle(Pose a, Pose b) {
		double minus = Math.sqrt(sq(a.qw() - b.qw()) + sq(a.qx() - b.qx()) + sq(a.qy() - b.qy()) + sq(a.qz() - b.qz()));
		double plus = Math.sqrt(sq(a.qw() + b.qw()) + sq(a.qx() + b.qx()) + sq(a.qy() + b.qy()) + sq(a.qz() + b.qz()));
		return 4 * Math.asin(Math.min(1, Math.min(minus, plus) / 2));
	}

	private static double sq(double v) {
		return v * v;
	}

	private void holdRemoteControls(Snapshot s) {
		for (Snapshot.Car sc : s.cars()) {
			if (sc.id() != ownCar && w.cars.containsKey(sc.id())) {
				w.inputs.hold(sc.id(), sc.applied());
			}
		}
	}

	private static long padMask(boolean[] active) {
		long m = 0;
		for (int i = 0; i < active.length; i++) {
			if (active[i]) {
				m |= 1L << i;
			}
		}
		return m;
	}

	private boolean heldAt(int id, long t) {
		BlobRing r = blobRings.get(id);
		return r != null && r.heldAt(t);
	}

	private byte @Nullable [] ownBlob(int id, long t) {
		BlobRing r = blobRings.get(id);
		return r == null ? null : r.get(t);
	}

	private void loadFailed(String what) {
		if (!loadFailed) {
			loadFailed = true;
			LOG.warn("mcrl: prediction {}: {} (further failures not logged)", w.dimName, what);
		}
	}

	private void loadSoccar(byte[] blob) {
		load("the arena", blob, w.soccarBlobSeg, null, () -> McrlNative.soccarLoad(w.handle, w.soccarBlobSeg));
	}

	private boolean loadCar(CarRec c, byte[] blob) {
		return load("car " + c.id, blob, w.carBlobSeg, c.cur.pose(), () -> McrlNative.carLoad(w.handle, c.id, w.carBlobSeg));
	}

	private void loadBall(byte[] blob) {
		load("the ball", blob, w.ballBlobSeg, null, () -> {
			McrlNative.ballLoad(w.handle, w.ballBlobSeg);
			if (w.ball == null) {
				w.ball = new SimWorld.BallRec(w.readBall(), null);
			}
		});
	}

	private boolean load(String what, byte[] blob, MemorySegment seg, @Nullable Pose car, Runnable load) {
		if (blob.length != seg.byteSize()) {
			loadFailed(what + "'s blob has " + blob.length + " bytes, not " + seg.byteSize());
			return false;
		}
		MemorySegment.copy(blob, 0, seg, JAVA_BYTE, 0, blob.length);
		if (car == null) {
			w.crumbAtOrigin(Op.SET_BALL);
		} else {
			w.crumb(Op.SET_CAR, car);
		}
		try {
			load.run();
			return true;
		} catch (RuntimeException e) {
			loadFailed(what + "'s blob does not load: " + e);
			return false;
		} finally {
			Breadcrumb.exit();
		}
	}

	private void queueCorrection(int id, Pose before, Pose after, boolean anchor) {
		double dx = before.x() - after.x(), dy = before.y() - after.y(), dz = before.z() - after.z();
		double w1 = before.qw(), x1 = before.qx(), y1 = before.qy(), z1 = before.qz();
		double w2 = after.qw(), x2 = after.qx(), y2 = after.qy(), z2 = after.qz();
		double qw = w1 * w2 + x1 * x2 + y1 * y2 + z1 * z2;
		double qx = -w1 * x2 + x1 * w2 - y1 * z2 + z1 * y2;
		double qy = -w1 * y2 + x1 * z2 + y1 * w2 - z1 * x2;
		double qz = -w1 * z2 - x1 * y2 + y1 * x2 + z1 * w2;
		double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (d == 0 && Math.abs(Math.abs(qw) - 1) < 1e-9) {
			return;
		}
		pendingCorrections.add(new Correction(id, dx, dy, dz, (float) qw, (float) qx, (float) qy, (float) qz,
				d > NetTuning.SNAP_BLOCKS, anchor));
	}
}
