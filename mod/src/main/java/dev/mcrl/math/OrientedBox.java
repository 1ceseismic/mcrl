package dev.mcrl.math;

import org.joml.Vector3d;

import dev.mcrl.sim.HitboxPreset;

public final class OrientedBox {
	private final double cx, cy, cz;
	private final double[][] axes;
	private final double[] half;

	private OrientedBox(double cx, double cy, double cz, double[][] axes, double[] half) {
		this.cx = cx;
		this.cy = cy;
		this.cz = cz;
		this.axes = axes;
		this.half = half;
	}

	public static OrientedBox car(Pose p, HitboxPreset h) {
		Vector3d f = p.forward(), u = p.up(), r = p.right();
		double of = h.offsetForward(), ou = h.offsetUp();
		double cx = p.x() + f.x * of + u.x * ou;
		double cy = p.y() + f.y * of + u.y * ou;
		double cz = p.z() + f.z * of + u.z * ou;
		return new OrientedBox(cx, cy, cz, new double[][] {{f.x, f.y, f.z}, {u.x, u.y, u.z}, {r.x, r.y, r.z}},
			new double[] {h.halfForward(), h.halfUp(), h.halfRight()});
	}

	public OrientedBox extendedDown(double d) {
		double[] u = axes[1];
		return new OrientedBox(cx - u[0] * d / 2, cy - u[1] * d / 2, cz - u[2] * d / 2, axes,
			new double[] {half[0], half[1] + d / 2, half[2]});
	}

	public double cx() {
		return cx;
	}

	public double cy() {
		return cy;
	}

	public double cz() {
		return cz;
	}

	public double[] aabb() {
		double[] e = new double[3];
		for (int i = 0; i < 3; i++) {
			e[i] = half[0] * Math.abs(axes[0][i]) + half[1] * Math.abs(axes[1][i]) + half[2] * Math.abs(axes[2][i]);
		}
		return new double[] {cx - e[0], cy - e[1], cz - e[2], cx + e[0], cy + e[1], cz + e[2]};
	}

	public boolean intersects(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		double[] a = {(maxX - minX) / 2, (maxY - minY) / 2, (maxZ - minZ) / 2};
		double[] t = {cx - (minX + maxX) / 2, cy - (minY + maxY) / 2, cz - (minZ + maxZ) / 2};
		double[] b = half;
		double[][] r = new double[3][3], ar = new double[3][3];
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 3; j++) {
				r[i][j] = axes[j][i];
				ar[i][j] = Math.abs(r[i][j]) + 1e-9;
			}
		}
		for (int i = 0; i < 3; i++) {
			if (Math.abs(t[i]) > a[i] + b[0] * ar[i][0] + b[1] * ar[i][1] + b[2] * ar[i][2]) {
				return false;
			}
		}
		for (int j = 0; j < 3; j++) {
			double ra = a[0] * ar[0][j] + a[1] * ar[1][j] + a[2] * ar[2][j];
			if (Math.abs(t[0] * r[0][j] + t[1] * r[1][j] + t[2] * r[2][j]) > ra + b[j]) {
				return false;
			}
		}
		for (int i = 0; i < 3; i++) {
			int i1 = (i + 1) % 3, i2 = (i + 2) % 3;
			for (int j = 0; j < 3; j++) {
				int j1 = (j + 1) % 3, j2 = (j + 2) % 3;
				double ra = a[i1] * ar[i2][j] + a[i2] * ar[i1][j];
				double rb = b[j1] * ar[i][j2] + b[j2] * ar[i][j1];
				if (Math.abs(t[i2] * r[i1][j] - t[i1] * r[i2][j]) > ra + rb) {
					return false;
				}
			}
		}
		return true;
	}
}
