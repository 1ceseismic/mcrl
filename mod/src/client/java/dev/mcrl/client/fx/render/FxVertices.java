package dev.mcrl.client.fx.render;

import java.nio.FloatBuffer;
import java.util.Arrays;

public final class FxVertices implements FxGeometry.Sink {
	public static final int FLOATS = 22;

	private float[] data = new float[FLOATS * 1024];
	private int vertices;
	private float r, g, b, a, d0, d1, d2, d3, zUu;

	@Override
	public void particle(float r, float g, float b, float a, float d0, float d1, float d2, float d3, float zUu) {
		this.r = r;
		this.g = g;
		this.b = b;
		this.a = a;
		this.d0 = d0;
		this.d1 = d1;
		this.d2 = d2;
		this.d3 = d3;
		this.zUu = zUu;
	}

	@Override
	public void vertex(float x, float y, float z, float u, float v, int argb, int light, float nx, float ny, float nz) {
		vertex(x, y, z, u, v, 0, 0, 0, argb, light, nx, ny, nz);
	}

	@Override
	public void vertex(float x, float y, float z, float u, float v, float u2, float v2, float w, int argb, int light,
			float nx, float ny, float nz) {
		int o = vertices * FLOATS;
		if (o + FLOATS > data.length) {
			data = Arrays.copyOf(data, data.length * 2);
		}
		float[] f = data;
		f[o] = x;
		f[o + 1] = y;
		f[o + 2] = z;
		f[o + 3] = nx;
		f[o + 4] = ny;
		f[o + 5] = nz;
		f[o + 6] = u;
		f[o + 7] = v;
		f[o + 8] = u2;
		f[o + 9] = v2;
		f[o + 10] = r;
		f[o + 11] = g;
		f[o + 12] = b;
		f[o + 13] = a;
		f[o + 14] = d0;
		f[o + 15] = d1;
		f[o + 16] = d2;
		f[o + 17] = d3;
		f[o + 18] = light >> 4 & 0xF;
		f[o + 19] = light >> 20 & 0xF;
		f[o + 20] = zUu;
		f[o + 21] = w;
		vertices++;
	}

	public int count() {
		return vertices;
	}

	public float get(int i, int k) {
		return data[i * FLOATS + k];
	}

	public void copy(int from, int n, FloatBuffer out) {
		out.put(data, from * FLOATS, n * FLOATS);
	}

	public void clear() {
		vertices = 0;
	}
}
