package dev.mcrl.sim;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.mcrl.math.Pose;
import dev.mcrl.natives.Breadcrumb;
import dev.mcrl.natives.Breadcrumb.Op;
import dev.mcrl.natives.McrlNative;
import dev.mcrl.sim.SimHost.Kind;
import dev.mcrl.sim.SimWorld.BallRec;
import dev.mcrl.sim.SimWorld.CarRec;

// Sim thread only, except drainEvents.
final class SimRules {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static final int EVENT_CAP = 256;

	private final SimWorld w;
	private final Random spawnRng;
	private final ArrayDeque<CarEvent> events = new ArrayDeque<>();

	SimRules(SimWorld w) {
		this.w = w;
		this.spawnRng = new Random(w.dimName.hashCode());
	}

	void demolish(int id, int attackerId) {
		CarRec c = w.cars.get(id);
		if (c == null) {
			LOG.warn("mcrl: demolish for unknown car {} in {}", id, w.dimName);
			return;
		}
		if (demolishCar(c, attackerId)) {
			w.changed.run();
		}
	}

	private boolean demolishCar(CarRec c, int attackerId) {
		if (c.demoed()) {
			return false;
		}
		w.crumb(Op.SET_CAR, c.cur.pose());
		try {
			McrlNative.carDemolish(w.handle, c.id);
			c.read(w.readCar(c.id));
		} finally {
			Breadcrumb.exit();
		}
		c.bumpsSeen = c.fx.bumps();
		demolished(c, attackerId);
		return true;
	}

	void demolished(CarRec c, int attackerId) {
		c.demoTick = w.tick;
		c.respawnAt = respawnState(c);
		emit(new CarEvent.Demolished(c.id, attackerId, c.cur.pose(), w.tick));
	}

	private void respawn(CarRec c, CarState s) {
		if (w.anchorCar() == c) {
			w.recentreOn(s.pose());
		}
		s.writeTo(w.carSeg);
		w.crumb(Op.SET_CAR, s.pose());
		try {
			McrlNative.carRespawn(w.handle, c.id, w.carSeg);
			c.read(w.readCar(c.id));
		} finally {
			Breadcrumb.exit();
		}
		w.placed(c);
		c.demoTick = -1;
		c.respawnAt = null;
		c.bumpsSeen = c.fx.bumps();
		emit(new CarEvent.Respawned(c.id, c.cur.pose(), w.tick));
	}

	private CarState respawnState(CarRec c) {
		if (w.kind == Kind.SOCCAR) {
			List<CarState> spots = w.soccarSpawns(c.team);
			return spots.get(spawnRng.nextInt(spots.size()));
		}
		Pose p = RespawnRules.outOfDemoVoid(RespawnRules.respawnPose(c.safe, c.cur.pose()), w.minY);
		return CarState.of(p, 0, 0, 0, 0, 0, 0);
	}

	private void emit(CarEvent e) {
		synchronized (events) {
			if (events.size() >= EVENT_CAP) {
				events.pollFirst();
			}
			events.addLast(e);
		}
	}

	List<CarEvent> drainEvents() {
		synchronized (events) {
			List<CarEvent> out = List.copyOf(events);
			events.clear();
			return out;
		}
	}

	// Cars first: the ball then respawns by the anchor.
	void apply(boolean ticked, int stepFlags) {
		long tick = w.tick;
		boolean record = w.kind == Kind.TERRAIN && ticked && tick % RespawnRules.SAFE_POSE_EVERY_TICKS == 0;
		for (CarRec c : w.cars.values()) {
			if (c.demoed()) {
				if (ticked && tick - c.demoTick >= RespawnRules.RESPAWN_TICKS) {
					LOG.debug("mcrl: car {} of {} respawns", c.id, w.dimName);
					respawn(c, c.respawnAt != null ? c.respawnAt : respawnState(c));
				}
			} else if (w.kind == Kind.TERRAIN && c.safe != null && RespawnRules.carDemoVoid(c.cur.pose(), w.minY)) {
				LOG.debug("mcrl: car {} in the void of {} at {}: demolished", c.id, w.dimName, c.cur.pose());
				demolishCar(c, -1);
			} else if (record && RespawnRules.carSafe(c.cur)) {
				c.safe = c.cur.pose();
			}
		}
		BallRec b = w.ball;
		if (b == null || w.kind != Kind.TERRAIN) {
			return;
		}
		boolean parked = (stepFlags & McrlNative.STEP_BALL_PARKED) != 0;
		if (ticked) {
			b.parkedTicks = parked ? b.parkedTicks + 1 : 0;
		}
		CarRec anchor = w.anchorCar();
		Pose a = anchor == null || anchor.demoed() ? null : anchor.cur.pose();
		if (RespawnRules.ballMustRespawn(b.cur.pose(), b.parkedTicks, a, w.minY)) {
			BallState to = RespawnRules.ballRespawn(a, b.safe, w.minY);
			if (to != null) {
				LOG.debug("mcrl: ball of {} at {} (parked {} ticks) respawns", w.dimName, b.cur.pose(), b.parkedTicks);
				b.read(w.placeBall(to, false));
				w.ballPlaced(b);
			}
		} else if (record && RespawnRules.ballSafe(b.cur.pose(), parked, w.minY)) {
			b.safe = b.cur.pose();
		}
	}
}
