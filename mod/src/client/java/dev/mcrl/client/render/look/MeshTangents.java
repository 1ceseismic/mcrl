package dev.mcrl.client.render.look;

import dev.mcrl.rlassets.MeshData;

public final class MeshTangents {
	private MeshTangents() {
	}

	public static float[] compute(float[] vertices, int[] indices) {
		int s = MeshData.STRIDE;
		int n = vertices.length / s;
		double[] tan = new double[3 * n];
		double[] bit = new double[3 * n];
		for (int t = 0; t + 2 < indices.length; t += 3) {
			int a = indices[t], b = indices[t + 1], c = indices[t + 2];
			double e1x = vertices[b * s] - vertices[a * s], e1y = vertices[b * s + 1] - vertices[a * s + 1],
					e1z = vertices[b * s + 2] - vertices[a * s + 2];
			double e2x = vertices[c * s] - vertices[a * s], e2y = vertices[c * s + 1] - vertices[a * s + 1],
					e2z = vertices[c * s + 2] - vertices[a * s + 2];
			double du1 = vertices[b * s + 6] - vertices[a * s + 6], dv1 = vertices[b * s + 7] - vertices[a * s + 7];
			double du2 = vertices[c * s + 6] - vertices[a * s + 6], dv2 = vertices[c * s + 7] - vertices[a * s + 7];
			double det = du1 * dv2 - du2 * dv1;
			if (Math.abs(det) < 1e-12) {
				continue;
			}
			double r = 1 / det;
			double tx = (e1x * dv2 - e2x * dv1) * r, ty = (e1y * dv2 - e2y * dv1) * r, tz = (e1z * dv2 - e2z * dv1) * r;
			double bx = (e2x * du1 - e1x * du2) * r, by = (e2y * du1 - e1y * du2) * r, bz = (e2z * du1 - e1z * du2) * r;
			for (int v : new int[] {a, b, c}) {
				tan[3 * v] += tx;
				tan[3 * v + 1] += ty;
				tan[3 * v + 2] += tz;
				bit[3 * v] += bx;
				bit[3 * v + 1] += by;
				bit[3 * v + 2] += bz;
			}
		}
		float[] out = new float[4 * n];
		for (int v = 0; v < n; v++) {
			double nx = vertices[v * s + 3], ny = vertices[v * s + 4], nz = vertices[v * s + 5];
			double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (nl > 0) {
				nx /= nl;
				ny /= nl;
				nz /= nl;
			}
			double tx = tan[3 * v], ty = tan[3 * v + 1], tz = tan[3 * v + 2];
			double d = nx * tx + ny * ty + nz * tz;
			tx -= nx * d;
			ty -= ny * d;
			tz -= nz * d;
			double tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
			double w = 1;
			if (tl < 1e-9) {
				double ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
				double kx = ax <= ay && ax <= az ? 1 : 0, ky = kx == 0 && ay <= az ? 1 : 0, kz = kx == 0 && ky == 0 ? 1 : 0;
				tx = ny * kz - nz * ky;
				ty = nz * kx - nx * kz;
				tz = nx * ky - ny * kx;
				tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
			} else {
				double cx = ny * tz - nz * ty, cy = nz * tx - nx * tz, cz = nx * ty - ny * tx;
				w = cx * bit[3 * v] + cy * bit[3 * v + 1] + cz * bit[3 * v + 2] < 0 ? -1 : 1;
			}
			out[4 * v] = (float) (tx / tl);
			out[4 * v + 1] = (float) (ty / tl);
			out[4 * v + 2] = (float) (tz / tl);
			out[4 * v + 3] = (float) w;
		}
		return out;
	}
}
