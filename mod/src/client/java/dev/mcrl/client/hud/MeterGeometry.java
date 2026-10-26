package dev.mcrl.client.hud;

import static dev.mcrl.client.hud.MeterStyle.FOCAL_LENGTH;
import static dev.mcrl.client.hud.MeterStyle.PROJECTION_OFFSET_X;
import static dev.mcrl.client.hud.MeterStyle.PROJECTION_OFFSET_Y;
import static dev.mcrl.client.hud.MeterStyle.RING_SIZE;
import static dev.mcrl.client.hud.MeterStyle.ROT_X_DEG;
import static dev.mcrl.client.hud.MeterStyle.ROT_Y_DEG;
import static dev.mcrl.client.hud.MeterStyle.WEDGE_DEG_PER_FRAME;
import static dev.mcrl.client.hud.MeterStyle.WEDGE_RADIUS;

final class MeterGeometry {
	private static final int GRID = 8;
	private static final double COS_X = Math.cos(Math.toRadians(ROT_X_DEG)), SIN_X = Math.sin(Math.toRadians(ROT_X_DEG));
	private static final double COS_Y = Math.cos(Math.toRadians(ROT_Y_DEG)), SIN_Y = Math.sin(Math.toRadians(ROT_Y_DEG));
	private static final double AFFINE_STEP = 1;

	private MeterGeometry() {
	}

	record Tris(float[] xy, float[] uv) {
		int count() {
			return xy.length / 6;
		}
	}

	static double[] project(double x, double y, double z) {
		double y1 = y * COS_X - z * SIN_X, z1 = y * SIN_X + z * COS_X;
		double x2 = x * COS_Y + z1 * SIN_Y, z2 = -x * SIN_Y + z1 * COS_Y;
		double k = FOCAL_LENGTH / (FOCAL_LENGTH + z2);
		return new double[] { PROJECTION_OFFSET_X + (x2 - PROJECTION_OFFSET_X) * k,
				PROJECTION_OFFSET_Y + (y1 - PROJECTION_OFFSET_Y) * k };
	}

	static Tris wedge(double deg) {
		int n = deg <= 0 ? 0 : (int) Math.ceil(deg / WEDGE_DEG_PER_FRAME - 1e-9);
		float[] xy = new float[n * 6], uv = new float[n * 6];
		for (int i = 0; i < n; i++) {
			put(xy, uv, i * 3, 0, 0);
			rim(xy, uv, i * 3 + 1, Math.toRadians(deg * i / n));
			rim(xy, uv, i * 3 + 2, Math.toRadians(deg * (i + 1) / n));
		}
		return new Tris(xy, uv);
	}

	static Tris square(double size) {
		float[] xy = new float[GRID * GRID * 12], uv = new float[GRID * GRID * 12];
		double cell = size / GRID, h = size / 2;
		int v = 0;
		for (int j = 0; j < GRID; j++) {
			for (int i = 0; i < GRID; i++) {
				double x0 = -h + i * cell, y0 = -h + j * cell, x1 = x0 + cell, y1 = y0 + cell;
				double u0 = (double) i / GRID, v0 = (double) j / GRID, u1 = (double) (i + 1) / GRID, v1 = (double) (j + 1) / GRID;
				double[][] corners = { { x0, y0, u0, v0 }, { x0, y1, u0, v1 }, { x1, y1, u1, v1 }, { x0, y0, u0, v0 },
						{ x1, y1, u1, v1 }, { x1, y0, u1, v0 } };
				for (double[] c : corners) {
					xy[v * 2] = (float) c[0];
					xy[v * 2 + 1] = (float) c[1];
					uv[v * 2] = (float) c[2];
					uv[v * 2 + 1] = (float) c[3];
					v++;
				}
			}
		}
		return new Tris(xy, uv);
	}

	static Tris toScreen(Tris t, double z, double[] centre, double scale) {
		float[] xy = new float[t.xy().length];
		for (int i = 0; i < xy.length; i += 2) {
			double[] p = project(t.xy()[i], t.xy()[i + 1], z);
			xy[i] = (float) (centre[0] + p[0] * scale);
			xy[i + 1] = (float) (centre[1] + p[1] * scale);
		}
		return new Tris(xy, t.uv());
	}

	static float[] textAffine(double cx, double cy, double z) {
		double h = AFFINE_STEP;
		double[] xp = project(cx + h, cy, z), xm = project(cx - h, cy, z);
		double[] yp = project(cx, cy + h, z), ym = project(cx, cy - h, z), c = project(cx, cy, z);
		double m00 = (xp[0] - xm[0]) / (2 * h), m10 = (xp[1] - xm[1]) / (2 * h);
		double m01 = (yp[0] - ym[0]) / (2 * h), m11 = (yp[1] - ym[1]) / (2 * h);
		return new float[] { (float) m00, (float) m01, (float) (c[0] - m00 * cx - m01 * cy), (float) m10, (float) m11,
				(float) (c[1] - m10 * cx - m11 * cy) };
	}

	private static void rim(float[] xy, float[] uv, int vertex, double theta) {
		put(xy, uv, vertex, -WEDGE_RADIUS * Math.sin(theta), WEDGE_RADIUS * Math.cos(theta));
	}

	private static void put(float[] xy, float[] uv, int vertex, double x, double y) {
		xy[vertex * 2] = (float) x;
		xy[vertex * 2 + 1] = (float) y;
		uv[vertex * 2] = (float) (x / RING_SIZE + 0.5);
		uv[vertex * 2 + 1] = (float) (y / RING_SIZE + 0.5);
	}
}
