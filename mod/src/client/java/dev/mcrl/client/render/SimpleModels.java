package dev.mcrl.client.render;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.mcrl.entity.BallEntity;
import dev.mcrl.sim.HitboxPreset;

public final class SimpleModels {
	public static final int FLOATS_PER_VERTEX = 9;
	private static final int BODY_RGB = 0x2A6FDB;
	private static final int CABIN_RGB = 0x1B1F2A;
	private static final int WHEEL_RGB = 0x222222;
	private static final int PANEL_A_RGB = 0xEEEEEE;
	private static final int PANEL_B_RGB = 0x9AA0A6;
	public static final float WHEEL_WIDTH = 0.25f;
	private static final int WHEEL_SEGMENTS = 16;
	private static final int BALL_SUBDIVISIONS = 3;
	private static final double BEVEL = 0.08;

	private SimpleModels() {
	}

	public static float[] carBody(HitboxPreset h) {
		Mesh m = new Mesh();
		double cx = h.offsetForward(), cy = h.offsetUp(), cz = 0;
		double hx = h.halfForward(), hy = h.halfUp(), hz = h.halfRight();
		chamferedBox(m, cx, cy, cz, hx, hy, hz, BEVEL, BODY_RGB);

		double y0 = cy + hy, y1 = y0 + 0.30;
		double xr = cx - hx + 0.20, xf = cx;
		double rxr = xr + 0.12, rxf = xf - 0.42;
		double w0 = hz - 0.12, w1 = hz - 0.24;
		double[] c = {(xr + xf) / 2, (y0 + y1) / 2, 0};
		double[] b0 = {xr, y0, -w0}, b1 = {xf, y0, -w0}, b2 = {xf, y0, w0}, b3 = {xr, y0, w0};
		double[] t0 = {rxr, y1, -w1}, t1 = {rxf, y1, -w1}, t2 = {rxf, y1, w1}, t3 = {rxr, y1, w1};
		m.face(c, CABIN_RGB, b0, b1, b2, b3);
		m.face(c, CABIN_RGB, t0, t1, t2, t3);
		m.face(c, CABIN_RGB, b1, b2, t2, t1);
		m.face(c, CABIN_RGB, b0, b3, t3, t0);
		m.face(c, CABIN_RGB, b0, b1, t1, t0);
		m.face(c, CABIN_RGB, b3, b2, t2, t3);
		return m.toArray();
	}

	public static float[] wheel(float radius) {
		Mesh m = new Mesh();
		double h = WHEEL_WIDTH / 2.0;
		double[] cPos = {0, 0, h}, cNeg = {0, 0, -h};
		double[] up = {0, 0, 1}, down = {0, 0, -1};
		for (int i = 0; i < WHEEL_SEGMENTS; i++) {
			double a0 = 2 * Math.PI * i / WHEEL_SEGMENTS, a1 = 2 * Math.PI * (i + 1) / WHEEL_SEGMENTS;
			double c0 = Math.cos(a0), s0 = Math.sin(a0), c1 = Math.cos(a1), s1 = Math.sin(a1);
			double[] p0n = {radius * c0, radius * s0, -h}, p1n = {radius * c1, radius * s1, -h};
			double[] p0p = {radius * c0, radius * s0, h}, p1p = {radius * c1, radius * s1, h};
			double[] n0 = {c0, s0, 0}, n1 = {c1, s1, 0};
			m.tri(p0n, n0, p1n, n1, p1p, n1, WHEEL_RGB);
			m.tri(p0n, n0, p1p, n1, p0p, n0, WHEEL_RGB);
			m.tri(cPos, up, p0p, up, p1p, up, WHEEL_RGB);
			m.tri(cNeg, down, p1n, down, p0n, down, WHEEL_RGB);
		}
		return m.toArray();
	}

	public static float[] ball() {
		double t = (1 + Math.sqrt(5)) / 2;
		List<double[]> v = new ArrayList<>(List.of(new double[] {-1, t, 0}, new double[] {1, t, 0},
				new double[] {-1, -t, 0}, new double[] {1, -t, 0}, new double[] {0, -1, t}, new double[] {0, 1, t},
				new double[] {0, -1, -t}, new double[] {0, 1, -t}, new double[] {t, 0, -1}, new double[] {t, 0, 1},
				new double[] {-t, 0, -1}, new double[] {-t, 0, 1}));
		v.replaceAll(SimpleModels::unit);
		List<int[]> faces = new ArrayList<>(List.of(new int[] {0, 11, 5}, new int[] {0, 5, 1}, new int[] {0, 1, 7},
				new int[] {0, 7, 10}, new int[] {0, 10, 11}, new int[] {1, 5, 9}, new int[] {5, 11, 4},
				new int[] {11, 10, 2}, new int[] {10, 7, 6}, new int[] {7, 1, 8}, new int[] {3, 9, 4},
				new int[] {3, 4, 2}, new int[] {3, 2, 6}, new int[] {3, 6, 8}, new int[] {3, 8, 9},
				new int[] {4, 9, 5}, new int[] {2, 4, 11}, new int[] {6, 2, 10}, new int[] {8, 6, 7},
				new int[] {9, 8, 1}));
		for (int s = 0; s < BALL_SUBDIVISIONS; s++) {
			Map<Long, Integer> mid = new HashMap<>();
			List<int[]> next = new ArrayList<>(faces.size() * 4);
			for (int[] f : faces) {
				int ab = midpoint(v, mid, f[0], f[1]), bc = midpoint(v, mid, f[1], f[2]), ca = midpoint(v, mid, f[2], f[0]);
				next.add(new int[] {f[0], ab, ca});
				next.add(new int[] {f[1], bc, ab});
				next.add(new int[] {f[2], ca, bc});
				next.add(new int[] {ab, bc, ca});
			}
			faces = next;
		}
		Mesh m = new Mesh();
		double r = BallEntity.RADIUS;
		for (int i = 0; i < faces.size(); i++) {
			int[] f = faces.get(i);
			double[] a = v.get(f[0]), b = v.get(f[1]), c = v.get(f[2]);
			double[] n = cross(sub(b, a), sub(c, a));
			if (dot(n, a) + dot(n, b) + dot(n, c) < 0) {
				double[] tmp = b;
				b = c;
				c = tmp;
			}
			int rgb = i % 2 == 0 ? PANEL_A_RGB : PANEL_B_RGB;
			m.tri(scale(a, r), a, scale(b, r), b, scale(c, r), c, rgb);
		}
		return m.toArray();
	}

	private static void chamferedBox(Mesh m, double cx, double cy, double cz, double hx, double hy, double hz,
			double b, int rgb) {
		double[] c = {cx, cy, cz};
		double[] h = {hx, hy, hz};
		int[][] signs = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
		for (int k = 0; k < 3; k++) {
			int k1 = (k + 1) % 3, k2 = (k + 2) % 3;
			for (int sk = -1; sk <= 1; sk += 2) {
				double[][] q = new double[4][];
				for (int j = 0; j < 4; j++) {
					int[] s = new int[3];
					s[k] = sk;
					s[k1] = signs[j][0];
					s[k2] = signs[j][1];
					q[j] = corner(c, h, b, s, k);
				}
				m.face(c, rgb, q);
			}
			for (int[] e : signs) {
				int[] sLo = new int[3], sHi = new int[3];
				sLo[k] = -1;
				sHi[k] = 1;
				sLo[k1] = sHi[k1] = e[0];
				sLo[k2] = sHi[k2] = e[1];
				m.face(c, rgb, corner(c, h, b, sLo, k1), corner(c, h, b, sHi, k1), corner(c, h, b, sHi, k2),
						corner(c, h, b, sLo, k2));
			}
		}
		for (int sx = -1; sx <= 1; sx += 2) {
			for (int sy = -1; sy <= 1; sy += 2) {
				for (int sz = -1; sz <= 1; sz += 2) {
					int[] s = {sx, sy, sz};
					m.face(c, rgb, corner(c, h, b, s, 0), corner(c, h, b, s, 1), corner(c, h, b, s, 2));
				}
			}
		}
	}

	private static double[] corner(double[] c, double[] h, double b, int[] s, int full) {
		double[] p = new double[3];
		for (int i = 0; i < 3; i++) {
			p[i] = c[i] + s[i] * (i == full ? h[i] : h[i] - b);
		}
		return p;
	}

	private static int midpoint(List<double[]> v, Map<Long, Integer> cache, int i, int j) {
		long key = (long) Math.min(i, j) << 32 | Math.max(i, j);
		Integer hit = cache.get(key);
		if (hit != null) {
			return hit;
		}
		double[] a = v.get(i), b = v.get(j);
		v.add(unit(new double[] {(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2}));
		cache.put(key, v.size() - 1);
		return v.size() - 1;
	}

	private static double[] unit(double[] a) {
		double n = Math.sqrt(dot(a, a));
		return new double[] {a[0] / n, a[1] / n, a[2] / n};
	}

	private static double[] scale(double[] a, double s) {
		return new double[] {a[0] * s, a[1] * s, a[2] * s};
	}

	private static double[] sub(double[] a, double[] b) {
		return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
	}

	private static double[] cross(double[] a, double[] b) {
		return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
	}

	private static double dot(double[] a, double[] b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	private static final class Mesh {
		private float[] data = new float[1024];
		private int size;

		void face(double[] centre, int rgb, double[]... p) {
			double[] n = unit(cross(sub(p[1], p[0]), sub(p[2], p[0])));
			double[] mid = new double[3];
			for (double[] q : p) {
				for (int i = 0; i < 3; i++) {
					mid[i] += q[i] / p.length;
				}
			}
			boolean flip = dot(n, sub(mid, centre)) < 0;
			if (flip) {
				n = scale(n, -1);
			}
			for (int i = 1; i + 1 < p.length; i++) {
				if (flip) {
					tri(p[0], n, p[i + 1], n, p[i], n, rgb);
				} else {
					tri(p[0], n, p[i], n, p[i + 1], n, rgb);
				}
			}
		}

		void tri(double[] a, double[] na, double[] b, double[] nb, double[] c, double[] nc, int rgb) {
			vertex(a, na, rgb);
			vertex(b, nb, rgb);
			vertex(c, nc, rgb);
		}

		private void vertex(double[] p, double[] n, int rgb) {
			if (size + FLOATS_PER_VERTEX > data.length) {
				data = Arrays.copyOf(data, data.length * 2);
			}
			data[size++] = (float) p[0];
			data[size++] = (float) p[1];
			data[size++] = (float) p[2];
			data[size++] = (float) n[0];
			data[size++] = (float) n[1];
			data[size++] = (float) n[2];
			data[size++] = (rgb >> 16 & 0xFF) / 255f;
			data[size++] = (rgb >> 8 & 0xFF) / 255f;
			data[size++] = (rgb & 0xFF) / 255f;
		}

		float[] toArray() {
			return Arrays.copyOf(data, size);
		}
	}
}
