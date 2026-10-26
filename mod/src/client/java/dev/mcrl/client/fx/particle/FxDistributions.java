package dev.mcrl.client.fx.particle;

import java.util.random.RandomGenerator;

import dev.mcrl.rlassets.model.Distribution;

public final class FxDistributions {
	private FxDistributions() {
	}

	public static int components(Distribution d) {
		if (d.entries() == 0 && d.param() != null) {
			return d.param().constant().length;
		}
		return Math.max(1, d.values());
	}

	public static void value(Distribution d, float t, RandomGenerator rng, FxParams p, float[] out) {
		Distribution.Param param = d.param();
		if (param != null && p.has(param.name())) {
			int comps = param.constant().length;
			int n = p.read(param.name(), rng, out);
			for (int k = comps - 1; k >= 0; k--) {
				out[k] = map(param, k, out[Math.min(k, n - 1)]);
			}
			return;
		}
		sample(d, t, rng, out);
	}

	public static float scalar(Distribution d, float t, RandomGenerator rng, FxParams p) {
		Distribution.Param param = d.param();
		if (param != null && p.has(param.name())) {
			return map(param, 0, p.readFirst(param.name(), rng));
		}
		int n = d.entries();
		if (n == 0) {
			return param != null ? param.constant()[0] : 0f;
		}
		float[] table = d.table();
		int chunk = d.chunk();
		float x = d.timeScale() == 0 ? 0 : Math.max(0, (t - d.startTime()) * d.timeScale());
		int i = (int) Math.min(Math.floor(x), n - 1), j = Math.min(i + 1, n - 1);
		float f = x - i;
		float a = table[2 + i * chunk], b = table[2 + j * chunk];
		float lo = a + (b - a) * f;
		if (d.op() == 1) {
			return lo;
		}
		int v = d.values();
		float a2 = table[2 + i * chunk + v], b2 = table[2 + j * chunk + v];
		float hi = a2 + (b2 - a2) * f;
		if (d.op() == 3) {
			return rng.nextFloat() < 0.5f ? lo : hi;
		}
		return lo + (hi - lo) * rng.nextFloat();
	}

	private static void sample(Distribution d, float t, RandomGenerator rng, float[] out) {
		int n = d.entries(), v = d.values();
		if (n == 0) {
			Distribution.Param param = d.param();
			if (param != null) {
				System.arraycopy(param.constant(), 0, out, 0, param.constant().length);
			} else {
				for (int k = 0; k < Math.max(v, 1); k++) {
					out[k] = 0;
				}
			}
			return;
		}
		float[] table = d.table();
		int chunk = d.chunk();
		float x = d.timeScale() == 0 ? 0 : Math.max(0, (t - d.startTime()) * d.timeScale());
		int i = (int) Math.min(Math.floor(x), n - 1), j = Math.min(i + 1, n - 1);
		float f = x - i;
		if (d.op() == 1) {
			for (int k = 0; k < chunk; k++) {
				float a = table[2 + i * chunk + k], b = table[2 + j * chunk + k];
				out[k] = a + (b - a) * f;
			}
			return;
		}
		if (d.op() == 3) {
			int from = rng.nextFloat() < 0.5f ? 0 : v;
			for (int k = 0; k < v; k++) {
				float a = table[2 + i * chunk + from + k], b = table[2 + j * chunk + from + k];
				out[k] = a + (b - a) * f;
			}
			return;
		}
		for (int k = 0; k < v; k++) {
			float a = table[2 + i * chunk + k], b = table[2 + j * chunk + k];
			float a2 = table[2 + i * chunk + v + k], b2 = table[2 + j * chunk + v + k];
			float lo = a + (b - a) * f, hi = a2 + (b2 - a2) * f;
			out[k] = lo + (hi - lo) * rng.nextFloat();
		}
	}

	private static float map(Distribution.Param param, int k, float v) {
		String[] modes = param.modes();
		String mode = modes[Math.min(k, modes.length - 1)];
		if (mode.equals("DPM_Direct")) {
			return v;
		}
		if (mode.equals("DPM_Abs")) {
			v = Math.abs(v);
		}
		float lo = param.minInput()[k], hi = param.maxInput()[k];
		float gradient = hi <= lo ? 0 : (param.maxOutput()[k] - param.minOutput()[k]) / (hi - lo);
		float clamped = v < lo ? lo : v < hi ? v : hi;
		return param.minOutput()[k] + (clamped - lo) * gradient;
	}
}
