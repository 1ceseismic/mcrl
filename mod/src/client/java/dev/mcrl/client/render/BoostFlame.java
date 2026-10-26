package dev.mcrl.client.render;

import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;

import dev.mcrl.client.fx.EffectsSettings;
import dev.mcrl.client.fx.FxActors;
import dev.mcrl.client.rl.BodyLook;
import dev.mcrl.client.rl.RlCache;

final class BoostFlame {
	static final String SOCKET = "RocketBoost";
	static final float[] DEFAULT_SOCKET = {-0.970f, 0.180f, 0.0f};
	static final float OUTER_LENGTH = 2.5f;
	static final float OUTER_RADIUS = 0.34f;
	static final float CORE_SCALE = 0.5f;
	static final int SEGMENTS = 12;
	static final int RINGS = 3;
	static final float WIDEST_AT = 0.18f;
	static final float NOZZLE = 0.55f;
	static final float[] OUTER_RGB = {1.0f, 0.21f, 0.07f};
	static final float[] CORE_RGB = {1.0f, 0.8f, 0.4f};
	static final double PULSE_HZ = 20;
	static final double PULSE_AMPLITUDE = 0.10;
	static final double EASE_IN_SECONDS = 0.040;
	static final double EASE_OUT_SECONDS = 0.080;
	static final int GRADIENT_WIDTH = 64;
	static final float GRADIENT_PEAK = 0.6f;

	static final float[] OUTER = cone(OUTER_LENGTH, OUTER_RADIUS, SEGMENTS, RINGS);
	static final float[] CORE = cone(OUTER_LENGTH * CORE_SCALE, OUTER_RADIUS * CORE_SCALE, SEGMENTS, RINGS);

	private BoostFlame() {
	}

	static boolean shows(@Nullable RlCache rl, EffectsSettings settings) {
		return (rl == null || rl.fxActors().get(FxActors.BOOST) == null) && settings.on(EffectsSettings.Group.BOOST);
	}

	static float[] cone(float length, float maxRadius, int segments, int rings) {
		if (segments < 3 || rings < 1 || !(length > 0) || !(maxRadius > 0)) {
			throw new IllegalArgumentException("cone(" + length + ", " + maxRadius + ", " + segments + ", " + rings + ")");
		}
		double[] x = new double[rings + 1], r = new double[rings + 1];
		x[0] = 0;
		r[0] = NOZZLE * maxRadius;
		for (int k = 1; k <= rings; k++) {
			double u = (double) (k - 1) / rings;
			x[k] = -(WIDEST_AT + (1 - WIDEST_AT) * u) * length;
			r[k] = maxRadius * (1 - u * u);
		}
		float[] out = new float[(segments * rings * 2 + segments) * 3 * SimpleModels.FLOATS_PER_VERTEX];
		int[] n = {0};
		double[] tip = {-length, 0, 0};
		for (int j = 0; j < segments; j++) {
			double a0 = 2 * Math.PI * j / segments, a1 = 2 * Math.PI * (j + 1) / segments;
			for (int k = 0; k < rings; k++) {
				double[] p00 = ring(x[k], r[k], a0), p01 = ring(x[k], r[k], a1);
				double[] p10 = ring(x[k + 1], r[k + 1], a0), p11 = ring(x[k + 1], r[k + 1], a1);
				triangle(out, n, p00, p11, p01);
				triangle(out, n, p00, p10, p11);
			}
			triangle(out, n, ring(x[rings], r[rings], a0), tip, ring(x[rings], r[rings], a1));
		}
		return out;
	}

	static float pulse(double nowSeconds) {
		return (float) (1 + PULSE_AMPLITUDE * Math.sin(2 * Math.PI * PULSE_HZ * nowSeconds));
	}

	static int gradientArgb(int i, int width) {
		double u = (double) i / (width - 1), f = 1 - u;
		int a = (int) Math.round(255 * f);
		int c = (int) Math.round(255 * GRADIENT_PEAK * Math.pow(f, 1.5));
		return a << 24 | c << 16 | c << 8 | c;
	}

	static float[] socket(@Nullable BodyLook look) {
		Vector3f v = look == null ? null : look.socket(SOCKET);
		return v == null ? DEFAULT_SOCKET : new float[] {v.x, v.y, v.z};
	}

	static void submit(CarRenderer.CarRenderState s, PoseStack ps, SubmitNodeCollector collector) {
		float k = s.boost;
		if (!(k > 0f)) {
			return;
		}
		float lengthScale = s.boostPulse;
		int outer = argb(OUTER_RGB, k), core = argb(CORE_RGB, k);
		float[] at = s.boostSocket;
		ps.pushPose();
		ps.translate(at[0], at[1], at[2]);
		collector.submitCustomGeometry(ps, MeshEmitter.flameRenderType(), (pose, vc) -> {
			MeshEmitter.emitFlame(pose, vc, CORE, OUTER_LENGTH * CORE_SCALE, lengthScale, core);
			MeshEmitter.emitFlame(pose, vc, OUTER, OUTER_LENGTH, lengthScale, outer);
		});
		ps.popPose();
	}

	private static int argb(float[] rgb, float k) {
		return channel(k) << 24 | channel(rgb[0] * k) << 16 | channel(rgb[1] * k) << 8 | channel(rgb[2] * k);
	}

	private static int channel(float c) {
		return Math.round(Math.max(0f, Math.min(1f, c)) * 255f);
	}

	private static double[] ring(double x, double r, double a) {
		return new double[] {x, r * Math.cos(a), r * Math.sin(a)};
	}

	private static void triangle(float[] out, int[] n, double[] a, double[] b, double[] c) {
		double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
		double wx = c[0] - a[0], wy = c[1] - a[1], wz = c[2] - a[2];
		double nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
		double my = a[1] + b[1] + c[1], mz = a[2] + b[2] + c[2];
		if (ny * my + nz * mz < 0) {
			double[] t = b;
			b = c;
			c = t;
			nx = -nx;
			ny = -ny;
			nz = -nz;
		}
		double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
		for (double[] p : new double[][] {a, b, c}) {
			int i = n[0];
			out[i] = (float) p[0];
			out[i + 1] = (float) p[1];
			out[i + 2] = (float) p[2];
			out[i + 3] = (float) (nx / len);
			out[i + 4] = (float) (ny / len);
			out[i + 5] = (float) (nz / len);
			out[i + 6] = 1f;
			out[i + 7] = 1f;
			out[i + 8] = 1f;
			n[0] = i + SimpleModels.FLOATS_PER_VERTEX;
		}
	}

	static final class FlameState {
		private double level;
		private double last = Double.NaN;

		float update(boolean boosting, double nowSeconds) {
			double dt = Double.isNaN(last) ? 0 : nowSeconds - last;
			last = nowSeconds;
			if (dt > 0) {
				level += boosting ? dt / EASE_IN_SECONDS : -dt / EASE_OUT_SECONDS;
				level = level > 1 - 1e-9 ? 1 : level < 1e-9 ? 0 : level;
			}
			return (float) (level * level * (3 - 2 * level));
		}
	}
}
