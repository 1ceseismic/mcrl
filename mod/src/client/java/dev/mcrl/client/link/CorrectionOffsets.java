package dev.mcrl.client.link;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import dev.mcrl.math.Pose;
import dev.mcrl.net.NetTuning;
import dev.mcrl.sim.SimHost;

final class CorrectionOffsets {
	record Offset(double dx, double dy, double dz, float qw, float qx, float qy, float qz) {
		Pose apply(Pose p) {
			double w = qw * p.qw() - qx * p.qx() - qy * p.qy() - qz * p.qz();
			double x = qw * p.qx() + qx * p.qw() + qy * p.qz() - qz * p.qy();
			double y = qw * p.qy() - qx * p.qz() + qy * p.qw() + qz * p.qx();
			double z = qw * p.qz() + qx * p.qy() - qy * p.qx() + qz * p.qw();
			return new Pose(p.x() + dx, p.y() + dy, p.z() + dz, (float) w, (float) x, (float) y, (float) z);
		}
	}

	private static final class Body {
		double dx, dy, dz, qw = 1, qx, qy, qz;
		long at;
		boolean timed;
	}

	private static final class Layer {
		final double tauS;
		final Map<Integer, Body> bodies = new HashMap<>();

		Layer(double tauS) {
			this.tauS = tauS;
		}

		void add(int bodyId, double dx, double dy, double dz, double qw, double qx, double qy, double qz, long now) {
			Body b = bodies.computeIfAbsent(bodyId, k -> new Body());
			decay(b, now);
			b.dx += dx;
			b.dy += dy;
			b.dz += dz;
			double w = b.qw * qw - b.qx * qx - b.qy * qy - b.qz * qz;
			double x = b.qw * qx + b.qx * qw + b.qy * qz - b.qz * qy;
			double y = b.qw * qy - b.qx * qz + b.qy * qw + b.qz * qx;
			double z = b.qw * qz + b.qx * qy - b.qy * qx + b.qz * qw;
			double n = Math.sqrt(w * w + x * x + y * y + z * z);
			b.qw = w / n;
			b.qx = x / n;
			b.qy = y / n;
			b.qz = z / n;
		}

		@Nullable Body get(int bodyId, long now) {
			Body b = bodies.get(bodyId);
			if (b != null) {
				decay(b, now);
				if (negligible(b)) {
					bodies.remove(bodyId);
					return null;
				}
			}
			return b;
		}

		void sweep(long now) {
			bodies.values().removeIf(b -> {
				decay(b, now);
				return negligible(b);
			});
		}

		private static boolean negligible(Body b) {
			double angle = 2 * Math.acos(Math.min(1, Math.abs(b.qw)));
			return b.dx * b.dx + b.dy * b.dy + b.dz * b.dz < NEGLIGIBLE * NEGLIGIBLE && angle < NEGLIGIBLE;
		}

		private void decay(Body b, long nowNanos) {
			long dt = nowNanos - b.at;
			boolean timed = b.timed;
			b.timed = true;
			b.at = nowNanos;
			if (!timed || dt <= 0) {
				return;
			}
			double k = Math.exp(-dt / 1e9 / tauS);
			b.dx *= k;
			b.dy *= k;
			b.dz *= k;
			double w = Math.max(-1, Math.min(1, b.qw));
			double sign = w < 0 ? -1 : 1;
			double half = Math.acos(w * sign);
			double s = Math.sin(half);
			if (s < 1e-12) {
				b.qw = 1;
				b.qx = b.qy = b.qz = 0;
				return;
			}
			double scaled = half * k, f = Math.sin(scaled) / s;
			b.qw = Math.cos(scaled);
			b.qx = b.qx * sign * f;
			b.qy = b.qy * sign * f;
			b.qz = b.qz * sign * f;
		}
	}

	private static final double NEGLIGIBLE = 1e-4;

	private final Layer corrections = new Layer(NetTuning.CORRECTION_TAU_S);
	private final Layer switches = new Layer(NetTuning.SWITCH_TAU_S);

	void add(SimHost.Correction c, long nowNanos) {
		if (c.snap()) {
			clear(c.bodyId());
			return;
		}
		(c.anchor() ? switches : corrections).add(c.bodyId(), c.dx(), c.dy(), c.dz(), c.qw(), c.qx(), c.qy(), c.qz(),
				nowNanos);
	}

	void addSwitch(int bodyId, Pose before, Pose after, long nowNanos) {
		Offset o = get(bodyId, nowNanos);
		if (o != null) {
			before = o.apply(before);
		}
		clear(bodyId);
		double dx = before.x() - after.x(), dy = before.y() - after.y(), dz = before.z() - after.z();
		if (dx * dx + dy * dy + dz * dz > NetTuning.SNAP_BLOCKS * NetTuning.SNAP_BLOCKS) {
			return;
		}
		double aw = after.qw(), ax = -after.qx(), ay = -after.qy(), az = -after.qz();
		double bw = before.qw(), bx = before.qx(), by = before.qy(), bz = before.qz();
		switches.add(bodyId, dx, dy, dz, bw * aw - bx * ax - by * ay - bz * az, bw * ax + bx * aw + by * az - bz * ay,
				bw * ay - bx * az + by * aw + bz * ax, bw * az + bx * ay - by * ax + bz * aw, nowNanos);
	}

	@Nullable Offset get(int bodyId, long nowNanos) {
		Body c = corrections.get(bodyId, nowNanos), s = switches.get(bodyId, nowNanos);
		if (c == null && s == null) {
			return null;
		}
		if (s == null) {
			return offset(c);
		}
		if (c == null) {
			return offset(s);
		}
		double w = s.qw * c.qw - s.qx * c.qx - s.qy * c.qy - s.qz * c.qz;
		double x = s.qw * c.qx + s.qx * c.qw + s.qy * c.qz - s.qz * c.qy;
		double y = s.qw * c.qy - s.qx * c.qz + s.qy * c.qw + s.qz * c.qx;
		double z = s.qw * c.qz + s.qx * c.qy - s.qy * c.qx + s.qz * c.qw;
		return new Offset(c.dx + s.dx, c.dy + s.dy, c.dz + s.dz, (float) w, (float) x, (float) y, (float) z);
	}

	private static Offset offset(Body b) {
		return new Offset(b.dx, b.dy, b.dz, (float) b.qw, (float) b.qx, (float) b.qy, (float) b.qz);
	}

	void sweep(long nowNanos) {
		corrections.sweep(nowNanos);
		switches.sweep(nowNanos);
	}

	void clear(int bodyId) {
		corrections.bodies.remove(bodyId);
		switches.bodies.remove(bodyId);
	}

	void clearAll() {
		corrections.bodies.clear();
		switches.bodies.clear();
	}
}
