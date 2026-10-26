package dev.mcrl.math;

import org.joml.Vector3d;

public record Pose(double x, double y, double z, float qw, float qx, float qy, float qz) {
	// Minecraft's position limits; Entity.load clamps to them.
	public static final double MAX_XZ = 3.0000512e7;
	public static final double MAX_Y = 2e7;

	public boolean isFinite() {
		return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && Float.isFinite(qw)
			&& Float.isFinite(qx) && Float.isFinite(qy) && Float.isFinite(qz);
	}

	public Pose clampedToWorld() {
		double cx = clamp(x, MAX_XZ), cy = clamp(y, MAX_Y), cz = clamp(z, MAX_XZ);
		return cx == x && cy == y && cz == z ? this : new Pose(cx, cy, cz, qw, qx, qy, qz);
	}

	private static double clamp(double v, double lim) {
		return v < -lim ? -lim : v > lim ? lim : v;
	}

	public Pose lerp(Pose to, float a) {
		double t = Math.max(0.0, Math.min(1.0, (double) a));
		double bw = to.qw, bx = to.qx, by = to.qy, bz = to.qz;
		double dot = qw * bw + qx * bx + qy * by + qz * bz;
		if (dot < 0) {
			bw = -bw; bx = -bx; by = -by; bz = -bz;
			dot = -dot;
		}
		double k0, k1;
		if (dot > 0.9995) {
			k0 = 1 - t;
			k1 = t;
		} else {
			double theta = Math.acos(Math.min(1.0, dot));
			double sin = Math.sin(theta);
			k0 = Math.sin((1 - t) * theta) / sin;
			k1 = Math.sin(t * theta) / sin;
		}
		double w = k0 * qw + k1 * bw, x = k0 * qx + k1 * bx, y = k0 * qy + k1 * by, z = k0 * qz + k1 * bz;
		double n = Math.sqrt(w * w + x * x + y * y + z * z);
		return new Pose(this.x + (to.x - this.x) * t, this.y + (to.y - this.y) * t, this.z + (to.z - this.z) * t,
			(float) (w / n), (float) (x / n), (float) (y / n), (float) (z / n));
	}

	public Pose advanced(double vx, double vy, double vz, double wx, double wy, double wz, double seconds) {
		double px = x + vx * seconds, py = y + vy * seconds, pz = z + vz * seconds;
		double w = Math.sqrt(wx * wx + wy * wy + wz * wz);
		if (w == 0) {
			return new Pose(px, py, pz, qw, qx, qy, qz);
		}
		double half = w * seconds / 2, s = Math.sin(half) / w;
		double dw = Math.cos(half), dx = wx * s, dy = wy * s, dz = wz * s;
		double rw = dw * qw - dx * qx - dy * qy - dz * qz;
		double rx = dw * qx + dx * qw + dy * qz - dz * qy;
		double ry = dw * qy - dx * qz + dy * qw + dz * qx;
		double rz = dw * qz + dx * qy - dy * qx + dz * qw;
		double n = Math.sqrt(rw * rw + rx * rx + ry * ry + rz * rz);
		return new Pose(px, py, pz, (float) (rw / n), (float) (rx / n), (float) (ry / n), (float) (rz / n));
	}

	public static Pose upright(double x, double y, double z, float yawDeg) {
		double yaw = Math.toRadians(yawDeg);
		double fx = -Math.sin(yaw), fz = Math.cos(yaw);
		double half = Math.atan2(-fz, fx) / 2;
		return new Pose(x, y, z, (float) Math.cos(half), 0f, (float) Math.sin(half), 0f);
	}

	public float yawDegrees() {
		Vector3d f = forward();
		double hx = f.x, hz = f.z;
		if (hx * hx + hz * hz < 1e-6) {
			Vector3d u = up();
			double s = f.y > 0 ? -1 : 1;
			hx = s * u.x;
			hz = s * u.z;
		}
		return (float) Math.toDegrees(Math.atan2(-hx, hz));
	}

	public float pitchDegrees() {
		return (float) Math.toDegrees(-Math.asin(Math.max(-1.0, Math.min(1.0, forward().y))));
	}

	public Vector3d forward() {
		return rotate(1, 0, 0);
	}

	public Vector3d up() {
		return rotate(0, 1, 0);
	}

	public Vector3d right() {
		return rotate(0, 0, 1);
	}

	private Vector3d rotate(double vx, double vy, double vz) {
		double n = Math.sqrt((double) qw * qw + (double) qx * qx + (double) qy * qy + (double) qz * qz);
		double w = qw / n, ux = qx / n, uy = qy / n, uz = qz / n;
		double tx = 2 * (uy * vz - uz * vy), ty = 2 * (uz * vx - ux * vz), tz = 2 * (ux * vy - uy * vx);
		return new Vector3d(vx + w * tx + (uy * tz - uz * ty), vy + w * ty + (uz * tx - ux * tz),
			vz + w * tz + (ux * ty - uy * tx));
	}
}
