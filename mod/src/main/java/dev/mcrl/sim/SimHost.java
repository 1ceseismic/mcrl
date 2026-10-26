package dev.mcrl.sim;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.mcrl.bot.BotPolicies;
import dev.mcrl.match.ArenaMeshes;
import dev.mcrl.match.StadiumConst;
import dev.mcrl.math.Pose;
import dev.mcrl.natives.Breadcrumb;
import dev.mcrl.natives.Breadcrumb.Op;
import dev.mcrl.natives.McrlNative;
import dev.mcrl.net.InputHistory;
import dev.mcrl.net.NetProtocol;
import dev.mcrl.net.NetTuning;
import dev.mcrl.net.Snapshot;
import dev.mcrl.sim.SimWorld.CarRec;

// The sim thread owns the native world; others post.
public final class SimHost implements AutoCloseable {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static final long STALL_NANOS = 500_000_000L;
	private static final long LOCKSTEP_WAIT_NANOS = 500_000_000L;
	private static final long HELD_SNAPSHOT_NANOS = 250_000_000L;
	private static final long PREDICTION_MAX_GAP_NANOS = 1_000_000_000L;
	private static final int PREDICTION_MAX_TICKS = 120;
	private static final long NAN_WARN_NANOS = 1_000_000_000L;
	private static final int STEP_HOLDERS = PauseSource.CLIENT.bit() | PauseSource.SERVER.bit();
	public static final long AT_TICK = Long.MIN_VALUE;

	public record SectionKey(int x, int y, int z) {
		public static SectionKey containing(double x, double y, double z) {
			return new SectionKey(sec(x), sec(y), sec(z));
		}

		public static SectionKey containing(Pose p) {
			return containing(p.x(), p.y(), p.z());
		}

		static int sec(double v) {
			return (int) Math.floor(v / 16.0);
		}
	}

	public record Fault(int count, @Nullable String last) {
		public static final Fault NONE = new Fault(0, null);
	}

	public record InputStats(long ackTick, int slack, int epoch, long late, long early) {
		public static final InputStats NONE = new InputStats(0, 0, -1, 0, 0);
	}

	public enum Kind {
		TERRAIN, SOCCAR
	}

	public enum Role {
		SERVER, PREDICTION
	}

	public record PadLayout(float[] xyz, boolean[] big) {
	}

	public record Correction(int bodyId, double dx, double dy, double dz, float qw, float qx, float qy, float qz,
			boolean snap, boolean anchor) {
	}

	public record PredictStats(long accepted, long restored) {
		public static final PredictStats NONE = new PredictStats(0, 0);
	}

	public record Published(SimFrame frame, List<Correction> corrections) {
	}

	// Sim thread: must not block or touch Minecraft state.
	public interface SnapshotListener {
		void onSnapshot(SimFrame f, int[] origin, BlobReader blobs);
	}

	// Valid only during the SnapshotListener call.
	public interface BlobReader {
		default byte @Nullable [] car(int id) {
			return null;
		}

		default byte @Nullable [] ball() {
			return null;
		}

		default byte @Nullable [] arena() {
			return null;
		}
	}

	private static final BlobReader NO_BLOBS = new BlobReader() {
	};

	private record Command(String name, Runnable action) {
	}

	record Commit(SectionKey key, int version, MemorySegment built, @Nullable Runnable handled) {
	}

	record Skipped(SectionKey key, int version, @Nullable Runnable handled) {
	}

	private record Shutdown() {
	}

	private static final class PaceToken {
		long seq, nowNanos;
	}

	private record RunJob(Runnable r, CompletableFuture<Void> done) {
	}

	private record StepJob(int ticks, CompletableFuture<Void> done) {
	}

	private record Barrier(CompletableFuture<Void> done) {
	}

	private final SimWorld world;
	private final SimRules rules;
	private final SimSections sections;
	private final @Nullable SimPrediction prediction;
	private final Thread thread;
	private final CompletableFuture<Void> ready = new CompletableFuture<>();
	private final Command dueTicks = new Command("DueTicks", this::dueTicksNow);
	private final Command heldSnapshot = new Command("HeldSnapshot", () -> {
		if (holding() != 0) {
			publish();
		}
	});

	private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
	private final Object postLock = new Object();
	private boolean accepting = true;
	private long paceSeqGen;
	private int pauseMask;
	private SimClock.Mode requestedMode;
	private @Nullable PaceToken tailPace;
	private @Nullable StepJob lateStep;
	private volatile boolean closeRequested;
	private volatile boolean terminated;

	private final AtomicReference<SimFrame> latest = new AtomicReference<>(SimFrame.EMPTY);
	private final ReentrantLock frameLock = new ReentrantLock();
	private final Condition frameCond = frameLock.newCondition();
	private volatile SimClock.Mode mode;
	private volatile float tickRateFactor = 1f;
	private volatile long busySince;
	private volatile String busyItem = "";
	private volatile Fault fault = Fault.NONE;
	private volatile @Nullable Runnable ticksListener;
	private volatile @Nullable SnapshotListener snapshotListener;
	private long warnedStall;
	private boolean warnedDead;

	private SimClock clock;
	private int paused;
	private long ackedPace;
	private long paceNanos;
	private float paceRate = 1f;
	private boolean frozen, faulted, stepUp;
	private long nanWarnedAt;
	private boolean nanWarned;
	private long lastPublishedTick;
	private long lastSnapTick = -1;
	private int lastSnapFlags;
	private long lastSnapNanos;
	private boolean snapshotFailed;

	private SimHost(String dimName, int ox, int oy, int oz, int minY, SimClock clock, Kind kind, Role role,
			ArenaMeshes.@Nullable Set meshes) {
		int ringCapacity = role == Role.PREDICTION ? NetTuning.HISTORY_TICKS : TickRing.CAPACITY;
		this.world = new SimWorld(dimName, ox, oy, oz, minY, kind, role, meshes, ringCapacity, this::entitiesChanged);
		this.rules = new SimRules(world);
		this.sections = new SimSections(world);
		this.prediction = role == Role.PREDICTION ? new SimPrediction(world, sections, this::runTicks) : null;
		this.clock = clock;
		this.mode = clock.mode();
		this.requestedMode = mode;
		String name = role == Role.PREDICTION ? "mcrl-sim/predict:" + dimName : "mcrl-sim/" + dimName;
		this.thread = Thread.ofPlatform().name(name).daemon(true).unstarted(this::run);
	}

	public static SimHost start(String dimName, int ox, int oy, int oz, int minY, SimClock.Mode mode) {
		return start(dimName, ox, oy, oz, minY, mode, Kind.TERRAIN, Role.SERVER);
	}

	public static SimHost start(String dimName, int ox, int oy, int oz, int minY, SimClock.Mode mode, Kind kind,
			Role role) {
		if (kind == Kind.SOCCAR) {
			throw new IllegalArgumentException("use startSoccar");
		}
		return start(dimName, ox, oy, oz, minY, mode, kind, role, null);
	}

	public static SimHost startSoccar(String dimName, ArenaMeshes.Set meshes, SimClock.Mode mode, Role role) {
		return start(dimName, StadiumConst.ORIGIN.getX(), StadiumConst.ORIGIN.getY(), StadiumConst.ORIGIN.getZ(),
				StadiumConst.MIN_Y, mode, Kind.SOCCAR, role, Objects.requireNonNull(meshes));
	}

	private static SimHost start(String dimName, int ox, int oy, int oz, int minY, SimClock.Mode mode, Kind kind,
			Role role, ArenaMeshes.@Nullable Set meshes) {
		SimClock clock = role == Role.PREDICTION && mode == SimClock.Mode.CLIENT_PACED
				? new SimClock.ClientPaced(PREDICTION_MAX_GAP_NANOS, PREDICTION_MAX_TICKS)
				: SimClock.create(mode);
		SimHost h = new SimHost(dimName, ox, oy, oz, minY, clock, Objects.requireNonNull(kind),
				Objects.requireNonNull(role), meshes);
		h.thread.start();
		try {
			h.ready.get(30, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			h.close();
			throw new IllegalStateException("mcrl: sim world for " + dimName + " failed to start", e.getCause());
		} catch (TimeoutException e) {
			h.close();
			throw new IllegalStateException("mcrl: sim world for " + dimName + " did not start within 30 s");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			h.close();
			throw new IllegalStateException("mcrl: interrupted while starting the sim for " + dimName, e);
		}
		return h;
	}

	public String dimName() {
		return world.dimName;
	}

	public SimClock.Mode mode() {
		return mode;
	}

	public Kind kind() {
		return world.kind;
	}

	public Role role() {
		return world.role;
	}

	public @Nullable PadLayout padLayout() {
		return world.padLayout;
	}

	public List<CarState> soccarSpawns(int team) {
		return world.soccarSpawns(team);
	}

	public void kickoff(int seed, int lockTicks) {
		if (world.kind != Kind.SOCCAR) {
			throw new IllegalStateException("kickoff on a " + world.kind + " host");
		}
		if (lockTicks < 0) {
			throw new IllegalArgumentException("lockTicks " + lockTicks);
		}
		post("Kickoff", () -> world.kickoff(seed, lockTicks));
	}

	public void addBot(int carId, BotPolicies.Loaded policy, float temperature, long seed) {
		if (world.kind != Kind.SOCCAR || world.role != Role.SERVER) {
			throw new IllegalStateException("bots run on SOCCAR server hosts, not a " + world.kind + " " + world.role
					+ " host");
		}
		SimWorld.BotRec bot = new SimWorld.BotRec(policy.policy(), temperature, seed);
		post("AddBot", () -> world.addBot(carId, bot));
	}

	public void removeBot(int carId) {
		post("RemoveBot", () -> world.removeBot(carId));
	}

	public void addCar(int id, CarState s, int team, int preset) {
		checkTeamAndPreset(team, preset);
		Objects.requireNonNull(s);
		post("AddCar", () -> world.addCar(id, s, team, preset));
	}

	public void reconfigureCar(int id, int team, int preset) {
		checkTeamAndPreset(team, preset);
		post("Reconfigure", () -> world.reconfigure(id, team, preset));
	}

	// Before posting: a reconfigure must not fail mid-way.
	private static void checkTeamAndPreset(int team, int preset) {
		if (team != 0 && team != 1) {
			throw new IllegalArgumentException("team " + team + " (0 BLUE or 1 ORANGE)");
		}
		if (preset < 0 || preset >= HitboxPreset.ALL.size()) {
			throw new IllegalArgumentException("preset " + preset + " (0.." + (HitboxPreset.ALL.size() - 1) + ")");
		}
	}

	public void demolish(int carId, int attackerId) {
		post("Demolish", () -> rules.demolish(carId, attackerId));
	}

	public void setDemos(int mode, boolean teamDemos) {
		if (mode < 0 || mode > 2) {
			throw new IllegalArgumentException("demo mode " + mode + " (0 off, 1 normal, 2 on contact)");
		}
		post("SetDemos", () -> McrlNative.worldSetDemos(world.handle, mode, teamDemos));
	}

	public List<CarEvent> drainCarEvents() {
		return rules.drainEvents();
	}

	public void setPerCarGate(boolean on) {
		post("SetGateMode", () -> McrlNative.worldSetGateMode(world.handle, on));
	}

	public void removeCar(int id) {
		post("RemoveCar", () -> {
			if (world.removeCar(id) && prediction != null) {
				prediction.forget(id);
			}
		});
	}

	public void setCar(int id, CarState s) {
		Objects.requireNonNull(s);
		post("SetCar", () -> world.setCar(id, s));
	}

	public void setBall(BallState s) {
		Objects.requireNonNull(s);
		post("SetBall", () -> world.setBall(s));
	}

	public void addVelocity(int carId, double dvx, double dvy, double dvz) {
		post("AddVelocity", () -> world.addVelocity(carId, dvx, dvy, dvz));
	}

	public void removeBall() {
		post("RemoveBall", world::removeBall);
	}

	// Mesh workers only: a refused build is freed here.
	public void commit(SectionKey key, int version, MemorySegment built, @Nullable Runnable handled) {
		if (!post(new Commit(Objects.requireNonNull(key), version, Objects.requireNonNull(built), handled))) {
			try {
				SimSections.freeBuilt(key, built, 0);
			} finally {
				if (handled != null) {
					handled.run();
				}
			}
		}
	}

	public void skipped(SectionKey key, int version, @Nullable Runnable handled) {
		if (!post(new Skipped(Objects.requireNonNull(key), version, handled)) && handled != null) {
			handled.run();
		}
	}

	public void removeSection(int sx, int sy, int sz) {
		post("RemoveSection", () -> sections.remove(new SectionKey(sx, sy, sz)));
	}

	public void setFluid(SectionKey k, byte @Nullable [] cells) {
		Objects.requireNonNull(k);
		post("SetFluid", () -> sections.setFluid(k, cells));
	}

	public void setStepUp(boolean on) {
		post("SetStepUp", () -> {
			stepUp = on;
			McrlNative.worldSetStepUp(world.handle, on);
			publish();
		});
	}

	// Native refuses extras in the stadium.
	public void setExtras(int flags) {
		if (world.kind == Kind.SOCCAR) {
			return;
		}
		post("SetExtras", () -> {
			McrlNative.worldSetExtras(world.handle, flags);
			publish();
		});
	}

	public List<TickRing.Entry> ringSince(int carId, long afterTick) {
		TickRing r = world.rings.get(carId);
		return r == null ? List.of() : r.since(afterTick);
	}

	public long ringOldestTick(int carId) {
		TickRing r = world.rings.get(carId);
		return r == null ? Long.MAX_VALUE : r.oldestTick();
	}

	public List<TickRing.Entry> ballRingSince(long afterTick) {
		TickRing r = world.ballRing;
		return r == null ? List.of() : r.since(afterTick);
	}

	public long ballRingOldestTick() {
		TickRing r = world.ballRing;
		return r == null ? Long.MAX_VALUE : r.oldestTick();
	}

	public int lastBallToucher() {
		return world.lastBallToucher;
	}

	public void setPaused(PauseSource src, boolean on) {
		int bit = src.bit();
		synchronized (postLock) {
			int mask = on ? pauseMask | bit : pauseMask & ~bit;
			if (mask != pauseMask) {
				pauseMask = mask;
				post("SetPaused", () -> setPausedNow(mask));
			}
		}
	}

	public void setClock(SimClock.Mode mode) {
		Objects.requireNonNull(mode);
		synchronized (postLock) {
			if (mode != requestedMode) {
				requestedMode = mode;
				post("SetClock", () -> setClockNow(mode));
			}
		}
	}

	public void submitInput(int carId, InputFrame f) {
		world.inputs.submit(carId, f);
	}

	public void submitInputs(int carId, long firstTick, int epoch, Controls[] ticks) {
		world.inputs.submit(carId, firstTick, epoch, ticks, latest.get().tick());
	}

	public InputStats inputStats(int carId) {
		return world.inputs.stats(carId);
	}

	public void setOwnCar(int carId, @Nullable InputHistory history) {
		predictionOnly().setOwnCar(carId, history);
	}

	public void reconcile(Snapshot s, long targetTick) {
		reconcile(s, targetTick, Map.of());
	}

	public void reconcile(Snapshot s, long targetTick, Map<Integer, Long> mentioned) {
		Objects.requireNonNull(s);
		Map<Integer, Long> also = Map.copyOf(mentioned);
		post("Reconcile", () -> reconcileNow(s, targetTick, also));
	}

	public Published takeLatest() {
		return predictionOnly().take(latest);
	}

	public PredictStats predictStats() {
		return prediction == null ? PredictStats.NONE : prediction.stats();
	}

	private SimPrediction predictionOnly() {
		if (prediction == null) {
			throw new IllegalStateException("not a PREDICTION host: " + this);
		}
		return prediction;
	}

	public void setTickRateFactor(float factor) {
		if (Float.isFinite(factor) && factor > 0f) {
			tickRateFactor = factor;
		}
	}

	// Paces queued back to back coalesce: newest wins.
	public long pace(long nowNanos) {
		synchronized (postLock) {
			long seq = ++paceSeqGen;
			if (!accepting || closeRequested) {
				return seq;
			}
			if (tailPace == null) {
				tailPace = new PaceToken();
				queue.add(tailPace);
			}
			tailPace.seq = seq;
			tailPace.nowNanos = nowNanos;
			return seq;
		}
	}

	// Waits capped; a stuck sim holds one late job.
	public void stepLockstep(int ticks) {
		if (ticks <= 0) {
			return;
		}
		checkNotSimThread("stepLockstep");
		StepJob late;
		StepJob job = null;
		synchronized (postLock) {
			if (!accepting || closeRequested) {
				return;
			}
			late = lateStep;
			if (late != null && late.done().isDone()) {
				late = lateStep = null;
			}
			if (late == null) {
				job = new StepJob(ticks, new CompletableFuture<>());
				if (!post(job)) {
					return;
				}
			}
		}
		if (job == null) {
			await(late.done(), LOCKSTEP_WAIT_NANOS);
		} else if (!await(job.done(), LOCKSTEP_WAIT_NANOS)) {
			synchronized (postLock) {
				lateStep = job;
			}
		}
	}

	public void flush() {
		checkNotSimThread("flush");
		Barrier b = new Barrier(new CompletableFuture<>());
		if (post(b)) {
			await(b.done(), Long.MAX_VALUE);
		}
	}

	public SimFrame latest() {
		return latest.get();
	}

	public Fault fault() {
		return fault;
	}

	public int sectionCount() {
		return sections.count();
	}

	public long stalledNanos(long nowNanos) {
		return stalledNanos(nowNanos, busySince);
	}

	private long stalledNanos(long nowNanos, long since) {
		if ((terminated || !thread.isAlive()) && !closeRequested) {
			return Long.MAX_VALUE;
		}
		if (since == 0) {
			return 0;
		}
		long busy = nowNanos - since;
		return busy > STALL_NANOS ? busy : 0;
	}

	// Server thread. Compare-and-set: never over a newer frame.
	public void watchdog(long nowNanos) {
		long since = busySince;
		long stalled = stalledNanos(nowNanos, since);
		if (stalled == 0) {
			return;
		}
		SimFrame f = latest.get();
		if ((f.flags() & SimFrame.STALLED) == 0) {
			SimFrame flagged = f.withFlags(f.flags() | SimFrame.STALLED);
			if (latest.compareAndSet(f, flagged) && stalled != Long.MAX_VALUE && busySince != since) {
				latest.compareAndSet(flagged, f);
			}
		}
		if (stalled == Long.MAX_VALUE) {
			if (!warnedDead) {
				warnedDead = true;
				LOG.warn("mcrl: the sim thread of {} has ended without being closed; /mcrl physics restart rebuilds it",
						world.dimName);
			}
		} else if (since != warnedStall) {
			warnedStall = since;
			Throwable where = new Throwable("stack of " + thread.getName());
			where.setStackTrace(thread.getStackTrace());
			LOG.warn("mcrl: sim {} stalled: busy on {} for {} ms (/mcrl physics restart replaces it)", world.dimName,
					busyItem, stalled / 1_000_000, where);
		}
	}

	public SimFrame awaitPace(long seq, long timeoutNanos) {
		SimFrame f = latest.get();
		if (f.paceSeq() >= seq || timeoutNanos <= 0 || terminated) {
			return f;
		}
		long deadline = System.nanoTime() + timeoutNanos;
		frameLock.lock();
		try {
			while (latest.get().paceSeq() < seq && !terminated) {
				long left = deadline - System.nanoTime();
				if (left <= 0) {
					break;
				}
				frameCond.awaitNanos(left);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			frameLock.unlock();
		}
		return latest.get();
	}

	public Set<SectionKey> neededSections() {
		return sections.needed();
	}

	public int[] origin() {
		return world.originView.clone();
	}

	public boolean isClosed() {
		return closeRequested || terminated;
	}

	public boolean isTerminated() {
		return terminated;
	}

	public boolean wantsSection(SectionKey k) {
		return sections.wants(k);
	}

	public @Nullable Integer committedVersion(SectionKey k) {
		return sections.version(k);
	}

	public boolean holdsSection(SectionKey k) {
		return sections.holds(k);
	}

	// A slow r stalls the sim.
	public CompletableFuture<Void> runOnSimThread(Runnable r) {
		RunJob j = new RunJob(r, new CompletableFuture<>());
		if (!post(j)) {
			j.done().complete(null);
		}
		return j.done();
	}

	public void setTicksListener(@Nullable Runnable listener) {
		ticksListener = listener;
	}

	public void setSnapshotListener(@Nullable SnapshotListener listener) {
		snapshotListener = listener;
	}

	public @Nullable SnapshotListener snapshotListener() {
		return snapshotListener;
	}

	@Override
	public void close() {
		requestClose();
		if (Thread.currentThread() == thread) {
			return;
		}
		try {
			thread.join(2000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		if (thread.isAlive()) {
			LOG.warn("mcrl: sim thread {} did not stop within 2 s", thread.getName());
		}
	}

	// Closes without joining a stuck thread.
	public void abandon() {
		requestClose();
	}

	private void requestClose() {
		ticksListener = null;
		snapshotListener = null;
		synchronized (postLock) {
			if (!closeRequested) {
				closeRequested = true;
				if (accepting) {
					queue.add(new Shutdown());
					tailPace = null;
				}
			}
		}
	}

	@Override
	public String toString() {
		return "SimHost[" + world.dimName + "]";
	}

	private void post(String name, Runnable action) {
		post(new Command(name, action));
	}

	private boolean post(Object item) {
		synchronized (postLock) {
			if (!accepting || closeRequested) {
				return false;
			}
			queue.add(item);
			tailPace = null;
			return true;
		}
	}

	private void checkNotSimThread(String what) {
		if (Thread.currentThread() == thread) {
			throw new IllegalStateException(what + " called on the sim thread");
		}
	}

	private boolean await(CompletableFuture<Void> f, long timeoutNanos) {
		long start = System.nanoTime();
		while (true) {
			long slice = TimeUnit.MILLISECONDS.toNanos(100);
			if (timeoutNanos != Long.MAX_VALUE) {
				long left = timeoutNanos - (System.nanoTime() - start);
				if (left <= 0) {
					return f.isDone();
				}
				slice = Math.min(slice, left);
			}
			try {
				f.get(slice, TimeUnit.NANOSECONDS);
				return true;
			} catch (TimeoutException e) {
				if (!thread.isAlive()) {
					return f.isDone();
				}
			} catch (ExecutionException e) {
				return true;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return f.isDone();
			}
		}
	}

	private void signalFrames() {
		frameLock.lock();
		try {
			frameCond.signalAll();
		} finally {
			frameLock.unlock();
		}
	}

	private void run() {
		try (Arena arena = Arena.ofConfined()) {
			try {
				world.create(arena);
				publish();
			} catch (Throwable t) {
				ready.completeExceptionally(t);
				return;
			}
			ready.complete(null);
			loop();
		} catch (Throwable t) {
			LOG.error("mcrl: sim thread {} died", thread.getName(), t);
		} finally {
			drainForGood();
			world.destroy();
			sections.clear();
			Breadcrumb.release();
			terminated = true;
			signalFrames();
		}
	}

	private void loop() {
		while (true) {
			Object item;
			try {
				long wait = waitNanos();
				item = wait < 0 ? queue.take() : queue.poll(wait, TimeUnit.NANOSECONDS);
			} catch (InterruptedException e) {
				continue;
			}
			if (item == null) {
				item = realtimeWaitNanos() == 0 ? dueTicks : heldSnapshot;
			}
			if (item instanceof Shutdown) {
				return;
			}
			runItem(item);
			if (item != dueTicks && realtimeWaitNanos() == 0) {
				runItem(dueTicks);
			}
		}
	}

	private long realtimeWaitNanos() {
		return clock instanceof SimClock.Realtime rt && holding() == 0 ? rt.waitNanos(System.nanoTime()) : -1;
	}

	private long waitNanos() {
		long rt = realtimeWaitNanos();
		if (rt >= 0 || snapshotListener == null || holding() == 0) {
			return rt;
		}
		return Math.max(0, lastSnapNanos + HELD_SNAPSHOT_NANOS - System.nanoTime());
	}

	private void runItem(Object item) {
		String name = item instanceof Command c ? c.name() : item.getClass().getSimpleName();
		long t0 = System.nanoTime();
		busyItem = name;
		busySince = t0 == 0 ? 1 : t0;
		try {
			handle(item);
		} catch (Throwable t) {
			String m = t.getMessage();
			addFault(name + ": " + (m != null ? m : t.getClass().getSimpleName()));
			LOG.error("mcrl: sim {} failed on {}", world.dimName, name, t);
			try {
				publish();
			} catch (Throwable t2) {
				LOG.error("mcrl: sim {} could not publish its fault frame", world.dimName, t2);
			}
		} finally {
			busySince = 0;
			clearStalled();
			release(item);
			long took = System.nanoTime() - t0;
			if (took > STALL_NANOS) {
				LOG.info("mcrl: sim {} recovered after {} ms on {}", world.dimName, took / 1_000_000, name);
			}
		}
	}

	private void addFault(String reason) {
		faulted = true;
		fault = new Fault(fault.count() + 1, reason);
	}

	private void handle(Object item) {
		switch (item) {
			case Command c -> c.action().run();
			case Commit c -> sections.commit(c);
			case Skipped c -> sections.skipped(c);
			case PaceToken p -> paceNow(p);
			case StepJob j -> stepNow(j.ticks());
			case RunJob j -> j.r().run();
			case Barrier b -> {
			}
			default -> throw new IllegalStateException("unknown queue item " + item);
		}
	}

	private void reconcileNow(Snapshot s, long targetTick, Map<Integer, Long> mentioned) {
		if (prediction == null) {
			LOG.warn("mcrl: reconcile on the {} host {} ignored", world.role, world.dimName);
		} else if (prediction.reconcile(s, targetTick, mentioned)) {
			publish();
		}
	}

	private void clearStalled() {
		SimFrame f = latest.get();
		if ((f.flags() & SimFrame.STALLED) != 0) {
			latest.compareAndSet(f, f.withFlags(f.flags() & ~SimFrame.STALLED));
		}
	}

	private static void release(Object item) {
		switch (item) {
			case Commit c when c.handled() != null -> c.handled().run();
			case Skipped c when c.handled() != null -> c.handled().run();
			case StepJob j -> j.done().complete(null);
			case RunJob j -> j.done().complete(null);
			case Barrier b -> b.done().complete(null);
			default -> {
			}
		}
	}

	private void drainForGood() {
		List<Object> rest = new ArrayList<>();
		synchronized (postLock) {
			accepting = false;
			queue.drainTo(rest);
		}
		for (Object o : rest) {
			if (o instanceof Commit c) {
				try {
					SimSections.freeBuilt(c.key(), c.built(), world.tick);
				} catch (Throwable t) {
					LOG.error("mcrl: could not free a queued section build", t);
				}
			}
			release(o);
		}
	}

	// Resets the clock so resuming adds no time.
	private void setPausedNow(int mask) {
		int was = holding();
		paused = mask;
		if (holding() != was) {
			clock.reset();
			paceNanos = 0;
		}
		publish();
	}

	// A client never pauses a shared REALTIME world.
	private int holding() {
		return mode == SimClock.Mode.REALTIME ? paused & ~PauseSource.CLIENT.bit() : paused;
	}

	private void setClockNow(SimClock.Mode m) {
		if (m == mode) {
			return;
		}
		clock = SimClock.create(m);
		mode = m;
		paceNanos = 0;
		paceRate = 1f;
		LOG.info("mcrl: sim {} now runs the {} clock", world.dimName, m);
		publish();
	}

	private void dueTicksNow() {
		if (!(clock instanceof SimClock.Realtime rt) || holding() != 0) {
			return;
		}
		float rate = tickRateFactor;
		int n = rt.due(System.nanoTime(), rate);
		if (n > 0) {
			paceNanos = rt.lastDeadline();
			paceRate = rate;
			runTicks(n);
			afterStep(n);
			publish();
		}
	}

	private void paceNow(PaceToken t) {
		long seq, nowNanos;
		synchronized (postLock) {
			if (tailPace == t) {
				tailPace = null;
			}
			seq = t.seq;
			nowNanos = t.nowNanos;
		}
		try {
			if (holding() != 0) {
				clock.reset();
				paceNanos = 0;
			} else {
				float rate = tickRateFactor;
				int n = clock.onPace(nowNanos, rate);
				if (mode == SimClock.Mode.CLIENT_PACED) {
					paceNanos = nowNanos;
					paceRate = rate;
				}
				if (n > 0) {
					runTicks(n);
					afterStep(n);
				}
			}
		} finally {
			ackedPace = seq;
		}
		publish();
	}

	// FREEZE alone lets a /tick step through.
	private void stepNow(int ticks) {
		if ((holding() & STEP_HOLDERS) == 0) {
			runTicks(ticks);
			afterStep(ticks);
		}
		publish();
	}

	private void runTicks(int n) {
		boolean server = world.role == Role.SERVER;
		for (int i = 0; i < n; i++) {
			int ran, stepFlags;
			world.stepCrumb();
			try {
				applyControls();
				world.flagsSeg.set(JAVA_INT, 0, 0);
				ran = McrlNative.step(world.handle, 1, world.flagsSeg);
				stepFlags = world.flagsSeg.get(JAVA_INT, 0);
				if (prediction != null) {
					prediction.ballParked = (stepFlags & McrlNative.STEP_BALL_PARKED) != 0;
				}
				if (ran >= 1) {
					world.tick++;
					world.readStates(rules);
					if (prediction != null) {
						prediction.recordBlobs();
					}
					if (world.kind == Kind.SOCCAR) {
						world.readSoccar();
						if (prediction != null) {
							prediction.recordPads();
						}
					}
					world.readBotControls();
					world.neutralWhereNotRun();
					if (prediction != null) {
						prediction.recordControls();
					}
					for (CarRec c : world.cars.values()) {
						world.inputs.ran(c, world.tick);
					}
				} else {
					for (CarRec c : world.cars.values()) {
						if (c.inputRing != null) {
							c.inputRing.notRun();
						}
					}
				}
			} finally {
				Breadcrumb.exit();
			}
			frozen = (stepFlags & McrlNative.STEP_FROZEN) != 0;
			if (ran < 1) {
				world.holdStill();
				if (server) {
					rules.apply(false, stepFlags);
				}
				return;
			}
			if ((stepFlags & McrlNative.STEP_RESTORED) != 0) {
				world.placeRestored(stepFlags);
				nanRestored();
			}
			if (server) {
				rules.apply(true, stepFlags);
			}
		}
	}

	private void applyControls() {
		for (CarRec c : world.cars.values()) {
			if (c.bot != null) {
				c.inputRing = null;
				c.appliedEdges = 0;
				continue;
			}
			if (prediction == null || !prediction.replayControls(c)) {
				world.inputs.apply(c, world.tick + 1);
			}
			c.given = c.applied;
			c.applied.writeTo(world.ctrlSeg);
			McrlNative.carSetControls(world.handle, c.id, world.ctrlSeg);
		}
	}

	private void nanRestored() {
		String why = "NaN guard restored the bodies at tick " + world.tick;
		addFault(why);
		long now = System.nanoTime();
		if (!nanWarned || now - nanWarnedAt >= NAN_WARN_NANOS) {
			nanWarned = true;
			nanWarnedAt = now;
			LOG.warn("mcrl: sim {}: {} (non-finite physics state; {} fault(s) so far)", world.dimName, why,
					fault.count());
		}
	}

	private void entitiesChanged() {
		sections.refresh();
		publish();
	}

	private void afterStep(int ticks) {
		if (world.role == Role.SERVER) {
			world.recentre();
		}
		sections.refresh();
		sections.expire(ticks);
	}

	private void publish() {
		if (prediction != null && prediction.reconciling) {
			return;
		}
		long tick = world.tick;
		boolean advanced = tick != lastPublishedTick;
		List<SimFrame.CarSnap> cs = new ArrayList<>(world.cars.size());
		for (CarRec c : world.cars.values()) {
			if (advanced) {
				c.stepDzFrame = c.stepDzSinceFrame;
				c.stepDzSinceFrame = 0f;
			}
			cs.add(c.snap(prediction != null && prediction.releasedStale(c.id)));
		}
		lastPublishedTick = tick;
		int flags = (stepUp ? SimFrame.STEPUP : 0) | (frozen ? SimFrame.FROZEN : 0) | (holding() != 0 ? SimFrame.PAUSED : 0)
				| (faulted ? SimFrame.FAULT : 0) | (prediction != null && prediction.ballHeld() ? SimFrame.BALL_HELD : 0);
		SimFrame frame = new SimFrame(tick, clock.alpha(), flags, cs, world.ball == null ? null : world.ball.snap(),
				ackedPace, paceNanos, paceRate, world.soccar);
		if (prediction != null) {
			prediction.publish(frame, latest);
		} else {
			latest.set(frame);
		}
		signalFrames();
		Runnable l = ticksListener;
		if (advanced && l != null) {
			try {
				l.run();
			} catch (RuntimeException e) {
				ticksListener = null;
				LOG.error("mcrl: the ticks listener failed; it is removed (mob hits wait for the server tick)", e);
			}
		}
		SnapshotListener sl = snapshotListener;
		long now = sl == null ? 0 : System.nanoTime();
		if (sl != null && (Math.floorDiv(tick, NetProtocol.SNAP_INTERVAL_TICKS)
				> Math.floorDiv(lastSnapTick, NetProtocol.SNAP_INTERVAL_TICKS) || flags != lastSnapFlags
				|| holding() != 0 && now - lastSnapNanos >= HELD_SNAPSHOT_NANOS)) {
			lastSnapTick = tick;
			lastSnapFlags = flags;
			lastSnapNanos = now;
			try {
				sl.onSnapshot(frame, world.originView, world.role == Role.SERVER ? serverBlobs : NO_BLOBS);
			} catch (RuntimeException e) {
				if (!snapshotFailed) {
					snapshotFailed = true;
					LOG.error("mcrl: sim {}: a snapshot failed (further failures are not logged)", world.dimName, e);
				}
			}
		}
	}

	private final BlobReader serverBlobs = new BlobReader() {
		@Override
		public byte @Nullable [] car(int id) {
			CarRec c = world.cars.get(id);
			return c == null ? null : save(c.cur.pose(), () -> McrlNative.carSave(world.handle, id, world.carBlobSeg),
					world.carBlobSeg);
		}

		@Override
		public byte @Nullable [] ball() {
			SimWorld.BallRec b = world.ball;
			return b == null ? null : save(b.cur.pose(), () -> McrlNative.ballSave(world.handle, world.ballBlobSeg),
					world.ballBlobSeg);
		}

		@Override
		public byte @Nullable [] arena() {
			return world.kind != Kind.SOCCAR ? null
					: save(null, () -> McrlNative.soccarSave(world.handle, world.soccarBlobSeg), world.soccarBlobSeg);
		}

		private byte[] save(@Nullable Pose at, Runnable save, MemorySegment blob) {
			if (at == null) {
				world.crumbAtOrigin(Op.SAVE);
			} else {
				world.crumb(Op.SAVE, at);
			}
			try {
				save.run();
			} finally {
				Breadcrumb.exit();
			}
			return blob.toArray(JAVA_BYTE);
		}
	};
}
