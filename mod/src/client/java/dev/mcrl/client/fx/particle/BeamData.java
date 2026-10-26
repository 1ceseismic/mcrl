package dev.mcrl.client.fx.particle;

import java.util.Arrays;

import org.jspecify.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3f;

import net.minecraft.world.phys.Vec3;

import dev.mcrl.rlassets.model.Distribution;

public final class BeamData {
	private float[] points = new float[3 * 64];
	private int[] start = new int[4], count = new int[4];
	private float[] width = new float[4], rgba = new float[16];
	private int beams;
	private final float[] src = new float[4], tgt = new float[4], tmp4 = new float[4];
	private final Vector3d d = new Vector3d();

	static final class Noise {
		final FxModule.BeamNoise def;
		final int points;
		final float[][] offsets;
		private long period = Long.MIN_VALUE;

		Noise(FxModule.BeamNoise def, int beams) {
			this.def = def;
			points = Math.max(1, def.frequency()) + 1;
			offsets = new float[beams][3 * points];
		}

		void update(EmitterInstance e, double time, float tau) {
			long k = def.lockTime() > 0 ? (long) Math.floor(time / def.lockTime()) : period + 1;
			if (k == period) {
				return;
			}
			period = k;
			for (float[] slot : offsets) {
				for (int j = 1; j + 1 < points; j++) {
					FxModules.vec(def.range(), tau, e, e.tmp);
					slot[3 * j] = e.tmp[0];
					slot[3 * j + 1] = e.tmp[1];
					slot[3 * j + 2] = e.tmp[2];
				}
			}
		}
	}

	public void build(EmitterInstance e, Vec3 camera) {
		beams = 0;
		FxEmitterDef.Beam beam = e.def().typeData().beam();
		if (beam == null) {
			return;
		}
		FxModule.BeamSource source = null;
		FxModule.BeamTarget target = null;
		for (FxModule m : e.def().modules()) {
			if (m instanceof FxModule.BeamSource s) {
				source = s;
			} else if (m instanceof FxModule.BeamTarget t) {
				target = t;
			}
		}
		Noise noise = e.noise;
		int n = noise != null ? noise.points : 2;
		float tau = e.loopTime();
		int live = Math.min(e.live(), beam.maxBeams());
		int used = 0;
		for (int b = 0; b < live; b++) {
			sample(source != null ? source.source() : null, tau, e, src);
			sample(target != null ? target.target() : null, tau, e, tgt);
			float scale = 0;
			if (noise != null) {
				float life = e.life(b);
				float t = noise.def.rangeScaleEmitterTime() ? tau : life > 0 ? e.age(b) / life : 0;
				scale = FxDistributions.scalar(noise.def.rangeScale(), t, e.rng, e.params());
			}
			ensure(b + 1, used + n);
			start[b] = used;
			count[b] = n;
			width[b] = e.sizeX(b) / 50;
			e.color(b, tmp4);
			System.arraycopy(tmp4, 0, rgba, 4 * b, 4);
			for (int j = 0; j < n; j++) {
				float f = j / (float) (n - 1);
				float x = src[0] + (tgt[0] - src[0]) * f, y = src[1] + (tgt[1] - src[1]) * f,
						z = src[2] + (tgt[2] - src[2]) * f;
				if (noise != null && j > 0 && j < n - 1 && b < noise.offsets.length) {
					float[] o = noise.offsets[b];
					x += o[3 * j] * scale;
					y += o[3 * j + 1] * scale;
					z += o[3 * j + 2] * scale;
				}
				e.emitterToWorldRl(x, y, z, d);
				int k = 3 * (used + j);
				points[k] = (float) (d.x / 50 - camera.x);
				points[k + 1] = (float) (d.z / 50 - camera.y);
				points[k + 2] = (float) (d.y / 50 - camera.z);
			}
			used += n;
			beams = b + 1;
		}
	}

	private static void sample(@Nullable Distribution dist, float tau, EmitterInstance e, float[] out) {
		if (dist == null) {
			out[0] = out[1] = out[2] = 0;
			return;
		}
		FxModules.vec(dist, tau, e, out);
	}

	private void ensure(int nBeams, int nPoints) {
		if (start.length < nBeams) {
			int c = Math.max(nBeams, start.length * 2);
			start = Arrays.copyOf(start, c);
			count = Arrays.copyOf(count, c);
			width = Arrays.copyOf(width, c);
			rgba = Arrays.copyOf(rgba, 4 * c);
		}
		if (points.length < 3 * nPoints) {
			points = Arrays.copyOf(points, Math.max(3 * nPoints, points.length * 2));
		}
	}

	public int beams() {
		return beams;
	}

	public int points(int b) {
		return count[b];
	}

	public Vector3f point(int b, int j, Vector3f out) {
		int k = 3 * (start[b] + j);
		return out.set(points[k], points[k + 1], points[k + 2]);
	}

	public float width(int b) {
		return width[b];
	}

	public void color(int b, float[] out) {
		System.arraycopy(rgba, 4 * b, out, 0, 4);
	}
}
