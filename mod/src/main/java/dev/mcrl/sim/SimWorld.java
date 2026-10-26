package dev.mcrl.sim;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.mcrl.bot.BotPolicy;
import dev.mcrl.match.ArenaMeshes;
import dev.mcrl.math.Pose;
import dev.mcrl.natives.Breadcrumb;
import dev.mcrl.natives.Breadcrumb.Op;
import dev.mcrl.natives.Layouts;
import dev.mcrl.natives.McrlNative;
import dev.mcrl.sim.SimHost.Kind;
import dev.mcrl.sim.SimHost.PadLayout;
import dev.mcrl.sim.SimHost.Role;
import dev.mcrl.sim.SimHost.SectionKey;
import dev.mcrl.terrain.MeshWorkers;

// Sim thread only, except the volatile and concurrent fields.
final class SimWorld {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static final int PARK_DEPTH = 2048;
	private static final int RECENTRE_BLOCKS = 256;
	private static final double ANCHOR_SWITCH_BLOCKS = 16;

	record CarRead(CarState state, CarFx fx) {
	}

	record BallRead(BallState state, BallFx fx) {
	}

	record BotRec(BotPolicy policy, float temperature, long seed) {
	}

	static final class CarRec {
		final int id;
		int team, preset;
		Pose prev;
		CarState cur;
		CarFx fx;
		@Nullable Pose safe;
		int appliedEdges;
		Controls applied = Controls.NEUTRAL;
		Controls given = Controls.NEUTRAL;
		CarInputs.@Nullable Ring inputRing;
		TickRing ring;
		long demoTick = -1;
		@Nullable CarState respawnAt;
		int bumpsSeen;
		@Nullable BotRec bot;
		float stepDzSinceFrame;
		float stepDzFrame;

		CarRec(int id, int team, int preset, CarRead r, @Nullable Pose safe, int ringCapacity) {
			this.id = id;
			this.ring = new TickRing(ringCapacity);
			this.team = team;
			this.preset = preset;
			this.cur = r.state();
			this.fx = r.fx();
			this.prev = cur.pose();
			this.safe = safe;
			this.bumpsSeen = fx.bumps();
		}

		void read(CarRead r) {
			cur = r.state();
			fx = r.fx();
		}

		boolean demoed() {
			return demoTick >= 0;
		}

		boolean heldOrWreck() {
			return (cur.flags() & (CarState.FLAG_HELD | CarState.FLAG_DEMOED)) != 0;
		}

		TickRing.Entry entry(long tick, float stepDz) {
			boolean still = heldOrWreck();
			return new TickRing.Entry(tick, cur.pose(), still ? 0 : cur.vx(), still ? 0 : cur.vy(),
					still ? 0 : cur.vz(), stepDz, demoed(), still ? 0 : cur.wx(), still ? 0 : cur.wy(),
					still ? 0 : cur.wz());
		}

		SimFrame.CarSnap snap(boolean held) {
			boolean wreck = demoed();
			return new SimFrame.CarSnap(id, prev, cur.pose(), wreck ? 0 : cur.vx(), wreck ? 0 : cur.vy(),
					wreck ? 0 : cur.vz(), wreck ? 0 : cur.wx(), wreck ? 0 : cur.wy(), wreck ? 0 : cur.wz(), cur.boost(),
					cur.flags() | (held ? CarState.FLAG_HELD : 0), cur.wheelLocal(), cur.steerAngle(), cur.wheelContact(),
					cur.ballHits(), cur.ballHitSpeed(), stepDzFrame, fx, team, preset, applied,
					respawnAt == null ? null : respawnAt.pose());
		}
	}

	static final class BallRec {
		Pose prev;
		BallState cur;
		BallFx fx;
		@Nullable Pose safe;
		int parkedTicks;

		BallRec(BallRead r, @Nullable Pose safe) {
			this.cur = r.state();
			this.fx = r.fx();
			this.prev = cur.pose();
			this.safe = safe;
		}

		void read(BallRead r) {
			cur = r.state();
			fx = r.fx();
		}

		void placed() {
			prev = cur.pose();
			parkedTicks = 0;
		}

		TickRing.Entry entry(long tick) {
			return new TickRing.Entry(tick, cur.pose(), cur.vx(), cur.vy(), cur.vz(), 0f, false, cur.wx(), cur.wy(),
					cur.wz());
		}

		SimFrame.BallSnap snap() {
			return new SimFrame.BallSnap(prev, cur.pose(), cur.vx(), cur.vy(), cur.vz(), cur.wx(), cur.wy(), cur.wz(),
					cur.bounces(), cur.bounceSpeed(), fx.lastTouchTeam(), fx.worldContact(), fx.bounce(), fx.wet());
		}
	}

	final String dimName;
	final Kind kind;
	final Role role;
	final int oy;
	final int minY;
	final int ringCapacity;
	private final ArenaMeshes.@Nullable Set meshes;
	final Runnable changed;
	final CarInputs inputs = new CarInputs();

	MemorySegment handle;
	MemorySegment carSeg, carFxSeg, ballSeg, ballFxSeg, ctrlSeg, flagsSeg, soccarSeg, carBlobSeg, ballBlobSeg,
			soccarBlobSeg;
	long tick;
	int ox, oz;
	volatile int[] originView;
	final LinkedHashMap<Integer, CarRec> cars = new LinkedHashMap<>();
	@Nullable BallRec ball;
	private int anchorId = -1;
	final ConcurrentHashMap<Integer, TickRing> rings = new ConcurrentHashMap<>();
	volatile @Nullable TickRing ballRing;
	volatile int lastBallToucher = -1;
	@Nullable SoccarState soccar;
	volatile @Nullable PadLayout padLayout;
	volatile List<List<CarState>> spawns = List.of();
	private final Map<BotPolicy, MemorySegment> botHandles = new IdentityHashMap<>();

	SimWorld(String dimName, int ox, int oy, int oz, int minY, Kind kind, Role role, ArenaMeshes.@Nullable Set meshes,
			int ringCapacity, Runnable changed) {
		this.dimName = dimName;
		this.kind = kind;
		this.role = role;
		this.meshes = meshes;
		this.ox = ox;
		this.oy = oy;
		this.oz = oz;
		this.minY = minY;
		this.originView = new int[] {ox, oy, oz};
		this.ringCapacity = ringCapacity;
		this.changed = changed;
	}

	void create(Arena arena) {
		Breadcrumb.enter(Op.CREATE, ox >> 4, oy >> 4, oz >> 4, 0);
		try {
			if (kind == Kind.SOCCAR) {
				handle = McrlNative.worldCreateSoccar(Objects.requireNonNull(meshes).handle(), ox, oy, oz);
			} else {
				handle = McrlNative.worldCreate(MeshWorkers.shapes(), ox, oy, oz);
				McrlNative.worldSetSpawnGate(handle, true);
			}
			if (role == Role.PREDICTION) {
				McrlNative.worldSetDemos(handle, 0, false);
				if (kind == Kind.TERRAIN) {
					McrlNative.worldSetGateMode(handle, true);
				}
			} else if (kind == Kind.TERRAIN) {
				McrlNative.worldSetDemos(handle, 1, true);
			}
			carSeg = arena.allocate(Layouts.CAR_STATE);
			carFxSeg = arena.allocate(Layouts.CAR_FX);
			ballSeg = arena.allocate(Layouts.BALL_STATE);
			ballFxSeg = arena.allocate(Layouts.BALL_FX);
			ctrlSeg = arena.allocate(Layouts.CONTROLS);
			flagsSeg = arena.allocate(JAVA_INT);
			carBlobSeg = arena.allocate(Layouts.CAR_BLOB_SIZE);
			ballBlobSeg = arena.allocate(Layouts.BALL_BLOB_SIZE);
			parkBall();
			if (kind == Kind.SOCCAR) {
				soccarSeg = arena.allocate(Layouts.SOCCAR_STATE);
				soccarBlobSeg = arena.allocate(Layouts.SOCCAR_BLOB_SIZE);
				readSoccarLayout(arena);
				readSoccar();
			}
		} finally {
			Breadcrumb.exit();
		}
	}

	void destroy() {
		if (handle == null) {
			return;
		}
		Breadcrumb.enter(Op.DESTROY, ox >> 4, oy >> 4, oz >> 4, tick);
		try {
			McrlNative.worldDestroy(handle);
		} catch (Throwable t) {
			LOG.error("mcrl: sim {} could not destroy its world", dimName, t);
		} finally {
			Breadcrumb.exit();
		}
		handle = null;
		for (MemorySegment h : botHandles.values()) {
			McrlNative.botPolicyFree(h);
		}
		botHandles.clear();
	}

	CarRead readCar(int id) {
		McrlNative.carGetState(handle, id, carSeg);
		McrlNative.carGetFx(handle, id, carFxSeg);
		return new CarRead(CarState.readFrom(carSeg), CarFx.readFrom(carFxSeg));
	}

	BallRead readBall() {
		McrlNative.ballGetState(handle, ballSeg);
		McrlNative.ballGetFx(handle, ballFxSeg);
		return new BallRead(BallState.readFrom(ballSeg), BallFx.readFrom(ballFxSeg));
	}

	private void readBodies() {
		for (CarRec c : cars.values()) {
			c.read(readCar(c.id));
		}
		if (ball != null) {
			ball.read(readBall());
		} else {
			parkBall();
		}
	}

	void readSoccar() {
		McrlNative.soccarGetState(handle, soccarSeg);
		soccar = SoccarState.readFrom(soccarSeg, tick, soccar);
	}

	void crumb(Op op, Pose p) {
		Breadcrumb.enter(op, SectionKey.sec(p.x()), SectionKey.sec(p.y()), SectionKey.sec(p.z()), tick);
	}

	void crumbAtOrigin(Op op) {
		Breadcrumb.enter(op, ox >> 4, oy >> 4, oz >> 4, tick);
	}

	void stepCrumb() {
		CarRec anchor = anchorCar();
		if (anchor == null) {
			crumbAtOrigin(Op.STEP);
		} else {
			crumb(Op.STEP, anchor.cur.pose());
		}
	}

	void readStates(SimRules rules) {
		int toucher = Integer.MIN_VALUE;
		for (CarRec c : cars.values()) {
			c.prev = c.cur.pose();
			int hitsBefore = c.cur.ballHits();
			c.read(readCar(c.id));
			if (c.cur.ballHits() != hitsBefore && c.id > toucher) {
				toucher = c.id;
			}
			if (role == Role.PREDICTION) {
				syncDemoFlag(c);
			} else if ((c.cur.flags() & CarState.FLAG_DEMOED) != 0 && !c.demoed()) {
				rules.demolished(c, c.fx.bumps() != c.bumpsSeen ? c.fx.bumpedBy() : -1);
			}
			c.bumpsSeen = c.fx.bumps();
			float dz = c.cur.stepDz();
			c.stepDzSinceFrame += dz;
			c.ring.add(c.entry(tick, dz));
		}
		if (toucher != Integer.MIN_VALUE) {
			lastBallToucher = toucher;
		}
		if (ball != null) {
			ball.prev = ball.cur.pose();
			ball.read(readBall());
			TickRing r = ballRing;
			if (r != null) {
				r.add(ball.entry(tick));
			}
		}
	}

	void syncDemoFlag(CarRec c) {
		boolean flag = (c.cur.flags() & CarState.FLAG_DEMOED) != 0;
		if (flag && !c.demoed()) {
			c.demoTick = tick;
		} else if (!flag && c.demoed()) {
			c.demoTick = -1;
		}
	}

	void readBotControls() {
		for (CarRec c : cars.values()) {
			if (c.bot != null) {
				McrlNative.carGetControls(handle, c.id, ctrlSeg);
				c.applied = Controls.readFrom(ctrlSeg);
			}
		}
	}

	void neutralWhereNotRun() {
		for (CarRec c : cars.values()) {
			if (c.heldOrWreck()) {
				c.applied = Controls.NEUTRAL;
				if ((c.cur.flags() & CarState.FLAG_HELD) != 0) {
					c.appliedEdges = 0;
				}
			}
		}
	}

	void holdStill() {
		for (CarRec c : cars.values()) {
			c.prev = c.cur.pose();
		}
		if (ball != null) {
			ball.prev = ball.cur.pose();
		}
	}

	void placeRestored(int stepFlags) {
		for (CarRec c : cars.values()) {
			if ((c.cur.flags() & CarState.FLAG_RESTORED) != 0) {
				placed(c);
			}
		}
		if (ball != null && (stepFlags & McrlNative.STEP_BALL_RESTORED) != 0) {
			ballPlaced(ball);
		}
	}

	void addCar(int id, CarState s, int team, int preset) {
		if (cars.containsKey(id)) {
			LOG.warn("mcrl: car {} added twice in {}; setting its state instead", id, dimName);
			setCar(id, s);
			return;
		}
		CarState b = bounded(s);
		if (cars.isEmpty() && role == Role.SERVER) {
			recentreOn(b.pose());
		}
		b.writeTo(carSeg);
		CarRead added;
		crumb(Op.ADD_CAR, b.pose());
		try {
			McrlNative.carAdd(handle, id, team, preset, carSeg);
			added = readCar(id);
		} finally {
			Breadcrumb.exit();
		}
		CarRec rec = new CarRec(id, team, preset, added, RespawnRules.initialCarSafe(added.state().pose(), minY),
				ringCapacity);
		cars.put(id, rec);
		rings.put(id, rec.ring);
		changed.run();
	}

	boolean removeCar(int id) {
		CarRec c = cars.get(id);
		if (c == null) {
			return false;
		}
		crumb(Op.REMOVE, c.cur.pose());
		try {
			McrlNative.carRemove(handle, id);
		} finally {
			Breadcrumb.exit();
		}
		cars.remove(id);
		rings.remove(id);
		inputs.remove(id);
		changed.run();
		return true;
	}

	void reconfigure(int id, int team, int preset) {
		CarRec c = cars.get(id);
		if (c == null) {
			LOG.warn("mcrl: reconfigureCar for unknown car {} in {}", id, dimName);
			return;
		}
		CarState s = c.cur;
		s.writeTo(carSeg);
		crumb(Op.ADD_CAR, s.pose());
		try {
			McrlNative.carRemove(handle, id);
			McrlNative.carAdd(handle, id, team, preset, carSeg);
			if (c.demoed()) {
				McrlNative.carDemolish(handle, id);
			}
			if (c.bot != null) {
				nativeAddBot(id, c.bot);
			}
			c.read(readCar(id));
		} finally {
			Breadcrumb.exit();
		}
		c.team = team;
		c.preset = preset;
		c.bumpsSeen = c.fx.bumps();
		placed(c);
		changed.run();
	}

	void setCar(int id, CarState s) {
		CarRec c = cars.get(id);
		if (c == null) {
			LOG.warn("mcrl: setCar for unknown car {} in {}", id, dimName);
			return;
		}
		if (c.demoed()) {
			LOG.debug("mcrl: setCar for wreck {} in {} ignored; it respawns on its timer", id, dimName);
			return;
		}
		CarState b = bounded(s);
		if (anchorCar() == c && role == Role.SERVER) {
			recentreOn(b.pose());
		}
		b.writeTo(carSeg);
		crumb(Op.SET_CAR, b.pose());
		try {
			McrlNative.carSetState(handle, c.id, carSeg);
			c.read(readCar(c.id));
		} finally {
			Breadcrumb.exit();
		}
		placed(c);
		changed.run();
	}

	void placed(CarRec c) {
		c.prev = c.cur.pose();
		c.ring = new TickRing(ringCapacity);
		rings.put(c.id, c.ring);
		c.stepDzSinceFrame = 0f;
		c.stepDzFrame = 0f;
	}

	void addVelocity(int carId, double dvx, double dvy, double dvz) {
		if (carId == -1) {
			BallRec b = ball;
			if (b == null) {
				return;
			}
			crumb(Op.SET_BALL, b.cur.pose());
			try {
				McrlNative.ballAddVelocity(handle, dvx, dvy, dvz);
			} finally {
				Breadcrumb.exit();
			}
			return;
		}
		CarRec car = cars.get(carId);
		if (car == null) {
			return;
		}
		crumb(Op.SET_CAR, car.cur.pose());
		try {
			McrlNative.carAddVelocity(handle, carId, dvx, dvy, dvz);
		} finally {
			Breadcrumb.exit();
		}
	}

	void setBall(BallState s) {
		BallRead b = placeBall(bounded(s), ball == null);
		if (ball == null) {
			ball = new BallRec(b, RespawnRules.initialSafe(b.state().pose(), minY));
			ballRing = new TickRing(ringCapacity);
			lastBallToucher = -1;
		} else {
			ball.read(b);
			ballPlaced(ball);
		}
		changed.run();
	}

	BallRead placeBall(BallState s, boolean fresh) {
		s.writeTo(ballSeg);
		crumb(Op.SET_BALL, s.pose());
		try {
			if (fresh) {
				McrlNative.ballClearTouch(handle);
			}
			McrlNative.ballSetState(handle, ballSeg);
			return readBall();
		} finally {
			Breadcrumb.exit();
		}
	}

	void removeBall() {
		if (ball == null) {
			return;
		}
		Breadcrumb.enter(Op.SET_BALL, ox >> 4, (kind == Kind.SOCCAR ? oy : oy - PARK_DEPTH) >> 4, oz >> 4, tick);
		try {
			parkBall();
		} finally {
			Breadcrumb.exit();
		}
		ball = null;
		ballRing = null;
		lastBallToucher = -1;
		changed.run();
	}

	void ballPlaced(BallRec b) {
		b.placed();
		ballRing = new TickRing(ringCapacity);
	}

	// The native ball parks where no section is READY.
	private void parkBall() {
		if (kind == Kind.SOCCAR) {
			McrlNative.soccarParkBall(handle);
			return;
		}
		new BallState(new Pose(ox + 8, oy - PARK_DEPTH, oz + 8, 1, 0, 0, 0), 0, 0, 0, 0, 0, 0).writeTo(ballSeg);
		McrlNative.ballSetState(handle, ballSeg);
	}

	@Nullable CarRec anchorCar() {
		if (cars.isEmpty()) {
			anchorId = -1;
			return null;
		}
		double mx = 0, mz = 0;
		for (CarRec c : cars.values()) {
			mx += c.cur.pose().x();
			mz += c.cur.pose().z();
		}
		mx /= cars.size();
		mz /= cars.size();
		CarRec nearest = null;
		double best = Double.POSITIVE_INFINITY;
		for (CarRec c : cars.values()) {
			double d = Math.hypot(c.cur.pose().x() - mx, c.cur.pose().z() - mz);
			if (nearest == null || d < best || (d == best && c.id < nearest.id)) {
				nearest = c;
				best = d;
			}
		}
		CarRec current = cars.get(anchorId);
		if (current != null && !(Math.hypot(current.cur.pose().x() - mx, current.cur.pose().z() - mz) - best
				> ANCHOR_SWITCH_BLOCKS)) {
			return current;
		}
		anchorId = nearest.id;
		return nearest;
	}

	void recentre() {
		CarRec anchor = anchorCar();
		if (anchor != null) {
			recentreOn(anchor.cur.pose());
		}
	}

	// Also before teleports, so native gets states near O.
	void recentreOn(Pose p) {
		if (kind != Kind.TERRAIN) {
			return;
		}
		if (!Double.isFinite(p.x()) || !Double.isFinite(p.z())
				|| Math.max(Math.abs(p.x() - ox), Math.abs(p.z() - oz)) <= RECENTRE_BLOCKS) {
			return;
		}
		shiftOrigin((int) Math.floor(p.x() / 16.0) * 16, (int) Math.floor(p.z() / 16.0) * 16);
	}

	void shiftOrigin(int nx, int nz) {
		Breadcrumb.enter(Op.SHIFT, nx >> 4, oy >> 4, nz >> 4, tick);
		try {
			McrlNative.worldShiftOrigin(handle, nx, oy, nz);
			ox = nx;
			oz = nz;
			originView = new int[] {ox, oy, oz};
			readBodies();
		} finally {
			Breadcrumb.exit();
		}
	}

	private void readSoccarLayout(Arena arena) {
		int n = Layouts.SOCCAR_PADS;
		MemorySegment xyz = arena.allocate(JAVA_FLOAT, 3L * n), big = arena.allocate(JAVA_BYTE, n);
		McrlNative.soccarPadLayout(handle, xyz, big);
		boolean[] isBig = new boolean[n];
		for (int i = 0; i < n; i++) {
			isBig[i] = big.get(JAVA_BYTE, i) != 0;
		}
		padLayout = new PadLayout(xyz.toArray(JAVA_FLOAT), isBig);
		List<List<CarState>> s = new ArrayList<>(2);
		for (int team = 0; team <= 1; team++) {
			List<CarState> spots = new ArrayList<>(4);
			for (int i = 0; i < 4; i++) {
				McrlNative.soccarRespawnState(handle, team, i, carSeg);
				spots.add(CarState.readFrom(carSeg));
			}
			s.add(List.copyOf(spots));
		}
		spawns = List.copyOf(s);
	}

	List<CarState> soccarSpawns(int team) {
		List<List<CarState>> s = spawns;
		return team >= 0 && team < s.size() ? s.get(team) : List.of();
	}

	// Revives wrecks silently: kickoff effects cover them.
	void kickoff(int seed, int lockTicks) {
		crumbAtOrigin(Op.KICKOFF);
		try {
			McrlNative.soccarKickoff(handle, seed, lockTicks);
			readBodies();
			readSoccar();
		} finally {
			Breadcrumb.exit();
		}
		for (CarRec c : cars.values()) {
			placed(c);
			c.bumpsSeen = c.fx.bumps();
			if (c.demoed()) {
				c.demoTick = -1;
				c.respawnAt = null;
			}
		}
		if (ball != null) {
			ball.placed();
		}
		changed.run();
	}

	void addBot(int id, BotRec b) {
		CarRec c = cars.get(id);
		if (c == null) {
			LOG.warn("mcrl: addBot for unknown car {} in {}", id, dimName);
			return;
		}
		if (c.bot != null) {
			McrlNative.botRemove(handle, id);
		}
		nativeAddBot(id, b);
		c.bot = b;
	}

	// Per host: BotPolicies' cache may free its handle.
	private void nativeAddBot(int id, BotRec b) {
		MemorySegment h = botHandles.computeIfAbsent(b.policy(), p -> McrlNative.botPolicyCreate(p.dims(), p.params()));
		McrlNative.botAdd(handle, id, h, b.temperature(), b.seed());
	}

	void removeBot(int id) {
		CarRec c = cars.get(id);
		if (c == null || c.bot == null) {
			return;
		}
		McrlNative.botRemove(handle, id);
		c.bot = null;
	}

	static CarState bounded(CarState s) {
		Pose p = boundedPose("car", s.pose());
		double[] v = {s.vx(), s.vy(), s.vz(), s.wx(), s.wy(), s.wz()}, m = boundedMotion("car", v);
		return m == v && p == s.pose() ? s : new CarState(p, m[0], m[1], m[2], m[3], m[4], m[5], s.boost(), s.flags(),
				s.wheelLocal(), s.steerAngle(), s.wheelContact(), s.ballHits(), s.ballHitSpeed(), s.stepDz());
	}

	static BallState bounded(BallState s) {
		Pose p = boundedPose("ball", s.pose());
		double[] v = {s.vx(), s.vy(), s.vz(), s.wx(), s.wy(), s.wz()}, m = boundedMotion("ball", v);
		return m == v && p == s.pose() ? s
				: new BallState(p, m[0], m[1], m[2], m[3], m[4], m[5], s.bounces(), s.bounceSpeed());
	}

	private static Pose boundedPose(String what, Pose p) {
		Pose c = p.clampedToWorld();
		if (c != p) {
			LOG.warn("mcrl: {} position ({}, {}, {}) set from Java clamped to ({}, {}, {})", what, p.x(), p.y(), p.z(),
					c.x(), c.y(), c.z());
		}
		return c;
	}

	// v itself when nothing changed.
	private static double[] boundedMotion(String what, double[] v) {
		double[] m = {fin(v[0]), fin(v[1]), fin(v[2]), fin(v[3]), fin(v[4]), fin(v[5])};
		double big = Math.max(Math.abs(m[0]), Math.max(Math.abs(m[1]), Math.abs(m[2])));
		if (big > 0) {
			double ux = m[0] / big, uy = m[1] / big, uz = m[2] / big;
			double n = Math.sqrt(ux * ux + uy * uy + uz * uz);
			if (big * n > NeededSections.MAX_SPEED) {
				double k = NeededSections.MAX_SPEED / n;
				m[0] = ux * k;
				m[1] = uy * k;
				m[2] = uz * k;
			}
		}
		if (m[0] == v[0] && m[1] == v[1] && m[2] == v[2] && m[3] == v[3] && m[4] == v[4] && m[5] == v[5]) {
			return v;
		}
		LOG.warn("mcrl: {} velocity ({}, {}, {}) and angular velocity ({}, {}, {}) set from Java bounded to ({}, {}, {}) "
				+ "and ({}, {}, {})", what, v[0], v[1], v[2], v[3], v[4], v[5], m[0], m[1], m[2], m[3], m[4], m[5]);
		return m;
	}

	private static double fin(double v) {
		return Double.isFinite(v) ? v : 0;
	}
}
