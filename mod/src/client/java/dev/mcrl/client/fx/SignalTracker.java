package dev.mcrl.client.fx;

import static dev.mcrl.math.Coords.UU_PER_BLOCK;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.IntToDoubleFunction;

import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.client.fx.FrameSignals.NormalizedRpm;
import dev.mcrl.math.Pose;
import dev.mcrl.sim.CarFx;
import dev.mcrl.sim.CarState;
import dev.mcrl.sim.Hit;
import dev.mcrl.sim.HitboxPreset;
import dev.mcrl.sim.SimFrame;
import dev.mcrl.sim.WheelContact;
import dev.mcrl.sim.Wet;

public final class SignalTracker {
	private static final int MAX_EVENTS = 4;
	private static final double MAX_SPIN = 150;

	private static final int JUMPS = 0, DOUBLE_JUMPS = 1, DODGES = 2, FLIP_RESETS = 3, LANDS = 4, WORLD_HITS = 8,
			BALL_HITS = 9, SPLASHES = 10, COUNTERS = 11;

	private static final class Track {
		final int[] base = new int[COUNTERS];
		int flags;
		final double[] spin = new double[4];

		void baseline(SimFrame.CarSnap c) {
			CarFx fx = c.fx();
			base[JUMPS] = fx.jumps();
			base[DOUBLE_JUMPS] = fx.doubleJumps();
			base[DODGES] = fx.dodges();
			base[FLIP_RESETS] = fx.flipResets();
			for (int i = 0; i < 4; i++) {
				base[LANDS + i] = fx.wheel(i).lands();
			}
			base[WORLD_HITS] = fx.worldHits();
			base[BALL_HITS] = c.ballHits();
			base[SPLASHES] = fx.wet().splashes();
		}
	}

	private final Int2ObjectOpenHashMap<Track> tracks = new Int2ObjectOpenHashMap<>();
	private boolean ballSeen;
	private int ballBounces, ballSplashes;
	private final double[] rp = new double[9], rc = new double[9], rd = new double[9];

	public void reset() {
		tracks.clear();
		ballSeen = false;
	}

	public FrameSignals update(SimFrame f, IntFunction<@Nullable Pose> drawn, @Nullable Pose ballDrawn,
			Level level, NormalizedRpm rpm, IntToDoubleFunction throttle, int localCar, double simSeconds) {
		Map<Integer, CarSignals> cars = new LinkedHashMap<>();
		for (SimFrame.CarSnap c : f.cars()) {
			Pose d = drawn.apply(c.carId());
			cars.put(c.carId(), car(c, d != null ? d : c.cur(), level, rpm, (float) throttle.applyAsDouble(c.carId()),
					c.carId() == localCar));
		}
		tracks.keySet().removeIf(id -> !cars.containsKey(id));
		SimFrame.BallSnap b = f.ball();
		BallSignals ball = null;
		if (b == null) {
			ballSeen = false;
		} else {
			List<FxEvent> ev = new ArrayList<>(0);
			int n = ballSeen ? count(ballBounces, b.bounces()) : 0;
			if (!ballSeen || n > 0) {
				ballBounces = b.bounces();
			}
			if (n > 0) {
				Vec3 p = point(b.bounce()), nrm = normal(b.bounce());
				RlSurface s = SurfaceMap.at(level, p, nrm);
				for (int k = 0; k < n; k++) {
					ev.add(new FxEvent.BallBounce(b.bounceSpeed(), p, nrm, s));
				}
			}
			int splashes = ballSeen ? count(ballSplashes, b.wet().splashes()) : 0;
			if (!ballSeen || splashes > 0) {
				ballSplashes = b.wet().splashes();
			}
			for (int k = 0; k < splashes; k++) {
				ev.add(splash(b.wet(), true));
			}
			ballSeen = true;
			ball = new BallSignals(ballDrawn != null ? ballDrawn : b.cur(),
					new Vec3(b.vx() * UU_PER_BLOCK, b.vy() * UU_PER_BLOCK, b.vz() * UU_PER_BLOCK), b.worldContact(),
					b.lastTouchTeam(), ev);
		}
		return new FrameSignals(cars, ball, simSeconds);
	}

	private CarSignals car(SimFrame.CarSnap c, Pose d, Level level, NormalizedRpm rpm, float throttle,
			boolean local) {
		int id = c.carId();
		Track t = tracks.get(id);
		boolean fresh = t == null;
		if (t == null) {
			t = new Track();
			tracks.put(id, t);
		}
		Pose pre = c.prev();
		matrix(pre, rp);
		matrix(c.cur(), rc);
		matrix(d, rd);
		CarFx fx = c.fx();
		int flags = c.carFlags();
		boolean onGround = (flags & CarState.FLAG_ON_GROUND) != 0;
		double vx = c.vx() * UU_PER_BLOCK, vy = c.vy() * UU_PER_BLOCK, vz = c.vz() * UU_PER_BLOCK;
		double wx = c.wx(), wy = c.wy(), wz = c.wz();
		double sin = Math.sin(c.steerAngle()), cos = Math.cos(c.steerAngle());
		double airSpin = Double.NaN;
		boolean airRead = false;
		HitboxPreset preset = HitboxPreset.of(c.preset());
		double frontRadius = preset.front().wheelRadius() * UU_PER_BLOCK;
		double backRadius = preset.back().wheelRadius() * UU_PER_BLOCK;
		WheelSignal[] wheels = new WheelSignal[4];
		for (int i = 0; i < 4; i++) {
			WheelContact w = fx.wheel(i);
			double ox = w.px() - pre.x(), oy = w.py() - pre.y(), oz = w.pz() - pre.z();
			double lx = rp[0] * ox + rp[3] * oy + rp[6] * oz;
			double ly = rp[1] * ox + rp[4] * oy + rp[7] * oz;
			double lz = rp[2] * ox + rp[5] * oy + rp[8] * oz;
			double rx = rd[0] * lx + rd[1] * ly + rd[2] * lz;
			double ry = rd[3] * lx + rd[4] * ly + rd[5] * lz;
			double rz = rd[6] * lx + rd[7] * ly + rd[8] * lz;
			double nx, ny, nz;
			if (w.contact()) {
				nx = w.nx();
				ny = w.ny();
				nz = w.nz();
			} else {
				nx = rd[1];
				ny = rd[4];
				nz = rd[7];
			}
			double ax, ay, az;
			if (i < 2) {
				ax = sin * rd[0] + cos * rd[2];
				ay = sin * rd[3] + cos * rd[5];
				az = sin * rd[6] + cos * rd[8];
			} else {
				ax = rd[2];
				ay = rd[5];
				az = rd[8];
			}
			double an = ax * nx + ay * ny + az * nz;
			ax -= nx * an;
			ay -= ny * an;
			az -= nz * an;
			double len = Math.sqrt(ax * ax + ay * ay + az * az);
			if (len > 1e-9) {
				ax /= len;
				ay /= len;
				az /= len;
			}
			double fx0 = ny * az - nz * ay, fy0 = nz * ax - nx * az, fz0 = nx * ay - ny * ax;
			double ux = (rc[0] * lx + rc[1] * ly + rc[2] * lz) * UU_PER_BLOCK;
			double uy = (rc[3] * lx + rc[4] * ly + rc[5] * lz) * UU_PER_BLOCK;
			double uz = (rc[6] * lx + rc[7] * ly + rc[8] * lz) * UU_PER_BLOCK;
			double cx = vx + wy * uz - wz * uy, cy = vy + wz * ux - wx * uz, cz = vz + wx * uy - wy * ux;
			double forward = cx * fx0 + cy * fy0 + cz * fz0;
			double spin;
			if (w.contact()) {
				spin = Math.clamp(forward / (i < 2 ? frontRadius : backRadius), -MAX_SPIN, MAX_SPIN);
			} else if (!onGround) {
				if (!airRead) {
					airSpin = rpm.of(id) * MAX_SPIN;
					airRead = true;
				}
				spin = Double.isNaN(airSpin) ? t.spin[i] : airSpin;
			} else {
				spin = t.spin[i];
			}
			t.spin[i] = spin;
			RlSurface surface = !w.contact() ? null
					: w.world() ? SurfaceMap.at(level, new Vec3(w.px(), w.py(), w.pz()), new Vec3(nx, ny, nz)) : RlSurface.BALL;
			wheels[i] = new WheelSignal(w.contact(), new Vec3(d.x() + rx, d.y() + ry, d.z() + rz), new Vec3(nx, ny, nz),
					forward, cx * ax + cy * ay + cz * az, cx * nx + cy * ny + cz * nz, spin, surface);
		}
		RlSurface body = fx.bodyContact()
				? SurfaceMap.at(level, new Vec3(fx.bpx(), fx.bpy(), fx.bpz()), new Vec3(fx.bnx(), fx.bny(), fx.bnz()))
				: null;
		List<FxEvent> events = fresh ? List.of() : events(t, c, level);
		if (fresh) {
			t.baseline(c);
		}
		t.flags = flags;
		return new CarSignals(id, d, new Vec3(vx, vy, vz), new Vec3(wx, wy, wz), wheels, onGround,
				(flags & CarState.FLAG_SUPERSONIC) != 0, (flags & CarState.FLAG_BOOSTING) != 0,
				vx * rd[0] + vy * rd[3] + vz * rd[6], throttle, local, body, events, fx, c.demoed());
	}

	private static List<FxEvent> events(Track t, SimFrame.CarSnap c, Level level) {
		CarFx is = c.fx();
		int[] base = t.base;
		List<FxEvent> out = new ArrayList<>(0);
		for (int k = bump(base, JUMPS, is.jumps()); k > 0; k--) {
			out.add(new FxEvent.Jumped());
		}
		for (int k = bump(base, DOUBLE_JUMPS, is.doubleJumps()); k > 0; k--) {
			out.add(new FxEvent.DoubleJumped());
		}
		for (int k = bump(base, DODGES, is.dodges()); k > 0; k--) {
			out.add(new FxEvent.Dodged());
		}
		for (int k = bump(base, FLIP_RESETS, is.flipResets()); k > 0; k--) {
			out.add(new FxEvent.FlipReset());
		}
		for (int i = 0; i < 4; i++) {
			WheelContact w = is.wheel(i);
			for (int k = bump(base, LANDS + i, w.lands()); k > 0; k--) {
				out.add(new FxEvent.Landed(i, w.landSpeed()));
			}
		}
		int n = bump(base, WORLD_HITS, is.worldHits());
		if (n > 0) {
			Vec3 p = point(is.worldHit()), nrm = normal(is.worldHit());
			RlSurface s = SurfaceMap.at(level, p, nrm);
			for (int k = 0; k < n; k++) {
				out.add(new FxEvent.WorldHit(is.worldHitSpeed(), p, nrm, s));
			}
		}
		n = bump(base, BALL_HITS, c.ballHits());
		if (n > 0) {
			Vec3 p = point(is.ballHit()), nrm = normal(is.ballHit());
			for (int k = 0; k < n; k++) {
				out.add(new FxEvent.BallHit(c.ballHitSpeed(), p, nrm));
			}
		}
		int flags = c.carFlags();
		if (rose(t.flags, flags, CarState.FLAG_SUPERSONIC)) {
			out.add(new FxEvent.SupersonicStart());
		}
		if (rose(t.flags, flags, CarState.FLAG_BOOSTING)) {
			out.add(new FxEvent.BoostStart());
		} else if (rose(flags, t.flags, CarState.FLAG_BOOSTING)) {
			out.add(new FxEvent.BoostStop());
		}
		for (int k = bump(base, SPLASHES, is.wet().splashes()); k > 0; k--) {
			out.add(splash(is.wet(), false));
		}
		return out;
	}

	private static FxEvent.Splash splash(Wet w, boolean ball) {
		return new FxEvent.Splash(w.splashSpeed(), new Vec3(w.sx(), w.sy(), w.sz()), w.fluid(), ball);
	}

	private static int bump(int[] base, int i, int is) {
		int n = count(base[i], is);
		if (n > 0) {
			base[i] = is;
		}
		return n;
	}

	private static int count(int was, int is) {
		int n = is - was;
		return n <= 0 ? 0 : Math.min(n, MAX_EVENTS);
	}

	private static boolean rose(int was, int is, int bit) {
		return (was & bit) == 0 && (is & bit) != 0;
	}

	private static Vec3 point(Hit h) {
		return new Vec3(h.px(), h.py(), h.pz());
	}

	private static Vec3 normal(Hit h) {
		return new Vec3(h.nx(), h.ny(), h.nz());
	}

	private static void matrix(Pose p, double[] m) {
		double w = p.qw(), x = p.qx(), y = p.qy(), z = p.qz();
		double n = Math.sqrt(w * w + x * x + y * y + z * z);
		w /= n;
		x /= n;
		y /= n;
		z /= n;
		m[0] = 1 - 2 * (y * y + z * z);
		m[1] = 2 * (x * y - w * z);
		m[2] = 2 * (x * z + w * y);
		m[3] = 2 * (x * y + w * z);
		m[4] = 1 - 2 * (x * x + z * z);
		m[5] = 2 * (y * z - w * x);
		m[6] = 2 * (x * z - w * y);
		m[7] = 2 * (y * z + w * x);
		m[8] = 1 - 2 * (x * x + y * y);
	}
}
