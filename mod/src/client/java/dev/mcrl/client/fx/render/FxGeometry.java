package dev.mcrl.client.fx.render;

import java.util.Arrays;

import org.jspecify.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import dev.mcrl.client.fx.particle.BeamData;
import dev.mcrl.client.fx.particle.EmitterInstance;
import dev.mcrl.client.fx.particle.FxEmitterDef.Alignment;
import dev.mcrl.client.fx.particle.FxEmitterDef.Blend;
import dev.mcrl.client.fx.particle.FxModule;
import dev.mcrl.client.fx.particle.FxModule.Lock;
import dev.mcrl.client.fx.particle.MeshData;
import dev.mcrl.client.fx.particle.RibbonData;
import dev.mcrl.client.rl.RlCache;

public final class FxGeometry {
	public static final int FULL_BRIGHT = 0xF000F0;

	public static boolean lit(Blend blend) {
		return blend != Blend.ADDITIVE;
	}

	public interface Sink {
		void vertex(float x, float y, float z, float u, float v, int argb, int light, float nx, float ny, float nz);

		default void particle(float r, float g, float b, float a, float d0, float d1, float d2, float d3, float zUu) {
		}

		default void vertex(float x, float y, float z, float u, float v, float u2, float v2, float w, int argb,
				int light, float nx, float ny, float nz) {
			vertex(x, y, z, u, v, argb, light, nx, ny, nz);
		}
	}

	public interface Light {
		int at(double x, double y, double z);
	}

	private final Vector3f a = new Vector3f(), b = new Vector3f(), t = new Vector3f(), n = new Vector3f();
	private final Vector3f ex = new Vector3f(), ey = new Vector3f(), ez = new Vector3f();
	private final Vector3d d = new Vector3d();
	private final Matrix4f m = new Matrix4f();
	private final float[] rgba = new float[4], uv = new float[4], uv2 = new float[4];
	private boolean second;
	private float weight;
	private final RibbonData ribbon = new RibbonData();
	private final BeamData beam = new BeamData();
	private float[] dist = new float[64];
	private int[] order = new int[64];
	private long[] keys = new long[64];

	public void sprite(float x, float y, float z, float sizeX, float sizeY, float rollTurns, float vx, float vy, float vz,
			Alignment alignment, Lock lock, Vector3fc lockX, Vector3fc lockY, Vector3fc lockZ, FxView view, float u0,
			float v0, float u1, float v1, int argb, int light, Sink out) {
		float ha = sizeX / 2, hb = (alignment == Alignment.SQUARE ? sizeX : sizeY) / 2;
		boolean roll = rollTurns != 0;
		switch (lock) {
			case X, NEG_X -> {
				a.set(lockY);
				b.set(lockZ);
				if (lock == Lock.NEG_X) {
					a.negate();
				}
			}
			case Y, NEG_Y -> {
				a.set(lockX);
				b.set(lockZ);
				if (lock == Lock.NEG_Y) {
					a.negate();
				}
			}
			case Z, NEG_Z -> {
				a.set(lockX);
				b.set(lockY);
				if (lock == Lock.NEG_Z) {
					a.negate();
				}
			}
			case ROTATE_X, ROTATE_Y, ROTATE_Z -> {
				b.set(lock == Lock.ROTATE_X ? lockX : lock == Lock.ROTATE_Y ? lockY : lockZ);
				b.cross(t.set(-x, -y, -z), a);
				if (a.lengthSquared() < 1e-12f) {
					a.set(view.left()).negate();
				}
				a.normalize();
				roll = false;
			}
			case NONE -> {
				if (alignment == Alignment.VELOCITY && velocityAxes(vx, vy, vz, view)) {
					roll = false;
				} else {
					a.set(view.left()).negate();
					b.set(view.up());
				}
			}
		}
		if (roll) {
			double r = rollTurns * 2 * Math.PI;
			float c = (float) Math.cos(r), s = (float) Math.sin(r);
			t.set(a).mul(c).add(b.x * s, b.y * s, b.z * s);
			b.mul(c).sub(a.x * s, a.y * s, a.z * s);
			a.set(t);
		}
		a.cross(b, n).normalize();
		if (!second) {
			uv2[0] = u0;
			uv2[1] = v0;
			uv2[2] = u1;
			uv2[3] = v1;
			weight = 0;
		}
		second = false;
		corner(x, y, z, -ha, -hb, u0, v1, uv2[0], uv2[3], argb, light, out);
		corner(x, y, z, ha, -hb, u1, v1, uv2[2], uv2[3], argb, light, out);
		corner(x, y, z, ha, hb, u1, v0, uv2[2], uv2[1], argb, light, out);
		corner(x, y, z, -ha, hb, u0, v0, uv2[0], uv2[1], argb, light, out);
	}

	private boolean velocityAxes(float vx, float vy, float vz, FxView view) {
		Vector3fc f = view.forward();
		float dot = vx * f.x() + vy * f.y() + vz * f.z();
		b.set(vx - dot * f.x(), vy - dot * f.y(), vz - dot * f.z());
		if (b.lengthSquared() < 1e-12f) {
			return false;
		}
		b.normalize();
		b.cross(f, a).normalize();
		return true;
	}

	private void corner(float x, float y, float z, float sa, float sb, float u, float v, float u2, float v2, int argb,
			int light, Sink out) {
		out.vertex(x + a.x * sa + b.x * sb, y + a.y * sa + b.y * sb, z + a.z * sa + b.z * sb, u, v, u2, v2, weight, argb,
				light, n.x, n.y,
				n.z);
	}

	public static void backToFront(float[] dist, int n, int[] order, long[] keys) {
		for (int i = 0; i < n; i++) {
			int bits = Float.floatToRawIntBits(Math.max(0, dist[i]));
			keys[i] = (long) (Integer.MAX_VALUE - bits) << 32 | i;
		}
		Arrays.sort(keys, 0, n);
		for (int i = 0; i < n; i++) {
			order[i] = (int) keys[i];
		}
	}

	public void emitter(EmitterInstance e, FxView view, RlCache.@Nullable Mesh mesh, Light light, Sink out) {
		emitter(e, view, mesh, null, light, out);
	}

	public void emitter(EmitterInstance e, FxView view, RlCache.@Nullable Mesh mesh, @Nullable FxSkin skin, Light light,
			Sink out) {
		Blend blend = e.def().required().blend();
		switch (e.def().typeData().kind()) {
			case SPRITE -> sprites(e, view, blend, skin, lit(blend) ? light : null, out);
			case RIBBON -> ribbon(e, view, blend, lit(blend) ? light : null, out);
			case BEAM -> beams(e, view, blend, lit(blend) ? light : null, out);
			case MESH -> {
				if (mesh != null) {
					meshes(e, view, mesh, blend, light, out);
				}
			}
		}
	}

	private int color(Blend blend) {
		return blend == Blend.ADDITIVE ? FxColor.additive(rgba[0], rgba[1], rgba[2], rgba[3])
				: FxColor.argb(rgba[0], rgba[1], rgba[2], rgba[3]);
	}

	private static int light(@Nullable Light light, FxView view, double x, double y, double z) {
		return light == null ? FULL_BRIGHT
				: light.at(x + view.position().x, y + view.position().y, z + view.position().z);
	}

	private void sprites(EmitterInstance e, FxView view, Blend blend, @Nullable FxSkin skin, @Nullable Light light,
			Sink out) {
		int count = e.drawCount();
		if (count == 0) {
			return;
		}
		double cx = view.position().x, cy = view.position().y, cz = view.position().z;
		ensure(count);
		for (int i = 0; i < count; i++) {
			e.drawPositionRl(i, d);
			double x = d.x / 50 - cx, y = d.z / 50 - cy, z = d.y / 50 - cz;
			dist[i] = (float) Math.sqrt(x * x + y * y + z * z);
			order[i] = i;
		}
		if (blend == Blend.TRANSLUCENT) {
			backToFront(dist, count, order, keys);
		}
		Lock lock = Lock.NONE;
		for (FxModule mod : e.def().modules()) {
			if (mod instanceof FxModule.AxisLock l) {
				lock = l.lock();
			}
		}
		if (lock != Lock.NONE) {
			e.emitterAxisMc(0, ex);
			e.emitterAxisMc(1, ey);
			e.emitterAxisMc(2, ez);
		}
		int h = e.def().required().subImagesH(), v = e.def().required().subImagesV();
		Alignment alignment = e.def().required().alignment();
		for (int k = 0; k < count; k++) {
			int i = order[k];
			e.drawPositionRl(i, d);
			float zUu = (float) d.z;
			float x = (float) (d.x / 50 - cx), y = (float) (d.z / 50 - cy), z = (float) (d.y / 50 - cz);
			float off = e.cameraOffset(i) / 50;
			if (off != 0 && dist[i] > 1e-6f) {
				float s = off / dist[i];
				x -= x * s;
				y -= y * s;
				z -= z * s;
			}
			e.velocityRl(i, d);
			e.color(i, rgba);
			int frame = e.subImage(i);
			cell(frame, h, v, uv);
			int argb;
			if (skin == null) {
				cell(Math.min(frame + 1, h * v - 1), h, v, uv2);
				weight = e.subImageBlend(i);
				argb = color(blend);
			} else {
				skin.cell(frame, h, v, uv2);
				weight = 0;
				argb = skin.argb(rgba[3], blend == Blend.ADDITIVE);
			}
			second = true;
			out.particle(rgba[0], rgba[1], rgba[2], rgba[3], e.dynamic(i, 0), e.dynamic(i, 1), e.dynamic(i, 2),
					e.dynamic(i, 3), zUu);
			int lit = light(light, view, x, y, z);
			sprite(x, y, z, e.sizeX(i) / 50, e.sizeY(i) / 50, e.rotation(i), (float) d.x, (float) d.z, (float) d.y,
					alignment, lock, ex, ey, ez, view, uv[0], uv[1], uv[2], uv[3], argb, lit, out);
		}
	}

	private static void cell(int frame, int h, int v, float[] out) {
		out[0] = (float) (frame % h) / h;
		out[1] = (float) (frame / h % v) / v;
		out[2] = out[0] + 1f / h;
		out[3] = out[1] + 1f / v;
	}

	private void ribbon(EmitterInstance e, FxView view, Blend blend, @Nullable Light light, Sink out) {
		ribbon.build(e, view.position());
		Vector3f a0 = a, b0 = b, a1 = t, b1 = n;
		for (int s = 0; s < ribbon.strips(); s++) {
			int start = ribbon.stripStart(s), points = ribbon.stripPoints(s);
			for (int k = start; k + 1 < start + points; k++) {
				ribbon.edgeA(k, a0);
				ribbon.edgeB(k, b0);
				ribbon.edgeA(k + 1, a1);
				ribbon.edgeB(k + 1, b1);
				float head = ribbon.u(start + points - 1);
				float u0 = head - ribbon.u(k), u1 = head - ribbon.u(k + 1);
				float g0 = (start + points - 1 - k) / (float) (points - 1), g1 = (start + points - 2 - k)
						/ (float) (points - 1);
				int l0 = light(light, view, (a0.x + b0.x) / 2, (a0.y + b0.y) / 2, (a0.z + b0.z) / 2);
				int l1 = light(light, view, (a1.x + b1.x) / 2, (a1.y + b1.y) / 2, (a1.z + b1.z) / 2);
				ribbon.color(k, rgba);
				int c0 = color(blend);
				ribbonPoint(e, k, (float) ((view.position().y + (a0.y + b0.y) / 2) * 50), out);
				out.vertex(a0.x, a0.y, a0.z, g0, 0, u0, 0, 0, c0, l0, 0, 1, 0);
				out.vertex(b0.x, b0.y, b0.z, g0, 1, u0, 1, 0, c0, l0, 0, 1, 0);
				ribbon.color(k + 1, rgba);
				int c1 = color(blend);
				ribbonPoint(e, k + 1, (float) ((view.position().y + (a1.y + b1.y) / 2) * 50), out);
				out.vertex(b1.x, b1.y, b1.z, g1, 1, u1, 1, 0, c1, l1, 0, 1, 0);
				out.vertex(a1.x, a1.y, a1.z, g1, 0, u1, 0, 0, c1, l1, 0, 1, 0);
			}
		}
	}

	private void ribbonPoint(EmitterInstance e, int k, float zUu, Sink out) {
		int i = ribbon.particle(k);
		out.particle(rgba[0], rgba[1], rgba[2], rgba[3], e.dynamic(i, 0), e.dynamic(i, 1), e.dynamic(i, 2),
				e.dynamic(i, 3), zUu);
	}

	private void beams(EmitterInstance e, FxView view, Blend blend, @Nullable Light light, Sink out) {
		beam.build(e, view.position());
		for (int bm = 0; bm < beam.beams(); bm++) {
			int points = beam.points(bm);
			float half = beam.width(bm) / 2;
			beam.color(bm, rgba);
			int c = color(blend);
			if (points > 0) {
				beam.point(bm, 0, ex);
				out.particle(rgba[0], rgba[1], rgba[2], rgba[3], 0, 0, 0, 0,
						(float) ((view.position().y + ex.y) * 50));
			}
			for (int j = 0; j + 1 < points; j++) {
				beam.point(bm, j, ex);
				beam.point(bm, j + 1, ey);
				t.set(ey).sub(ex);
				t.cross(ex, a);
				if (a.lengthSquared() < 1e-12f) {
					a.set(view.up());
				}
				a.normalize().mul(half);
				float u0 = j / (float) (points - 1), u1 = (j + 1) / (float) (points - 1);
				int l0 = light(light, view, ex.x, ex.y, ex.z), l1 = light(light, view, ey.x, ey.y, ey.z);
				out.vertex(ex.x - a.x, ex.y - a.y, ex.z - a.z, u0, 0, c, l0, 0, 1, 0);
				out.vertex(ex.x + a.x, ex.y + a.y, ex.z + a.z, u0, 1, c, l0, 0, 1, 0);
				out.vertex(ey.x + a.x, ey.y + a.y, ey.z + a.z, u1, 1, c, l1, 0, 1, 0);
				out.vertex(ey.x - a.x, ey.y - a.y, ey.z - a.z, u1, 0, c, l1, 0, 1, 0);
			}
		}
	}

	private void meshes(EmitterInstance e, FxView view, RlCache.Mesh mesh, Blend blend, Light light, Sink out) {
		float[] vx = mesh.vertices(), uv1 = mesh.uv1();
		int[] ix = mesh.indices();
		for (int i = 0; i < e.drawCount(); i++) {
			MeshData.transform(e, i, view.position(), m);
			e.color(i, rgba);
			int c = color(blend);
			e.positionMc(i, d);
			int lit = lit(blend) ? light.at(d.x, d.y, d.z) : FULL_BRIGHT;
			out.particle(rgba[0], rgba[1], rgba[2], rgba[3], e.dynamic(i, 0), e.dynamic(i, 1), e.dynamic(i, 2),
					e.dynamic(i, 3), (float) (d.y * 50));
			for (int k = 0; k + 2 < ix.length; k += 3) {
				meshVertex(vx, uv1, ix[k], c, lit, out);
				meshVertex(vx, uv1, ix[k + 1], c, lit, out);
				meshVertex(vx, uv1, ix[k + 2], c, lit, out);
				meshVertex(vx, uv1, ix[k + 2], c, lit, out);
			}
		}
	}

	public void staticMesh(RlCache.Mesh mesh, Matrix4f transform, int argb, int light, Sink out) {
		m.set(transform);
		float[] vx = mesh.vertices(), uv1 = mesh.uv1();
		int[] ix = mesh.indices();
		for (int k = 0; k + 2 < ix.length; k += 3) {
			meshVertex(vx, uv1, ix[k], argb, light, out);
			meshVertex(vx, uv1, ix[k + 1], argb, light, out);
			meshVertex(vx, uv1, ix[k + 2], argb, light, out);
			meshVertex(vx, uv1, ix[k + 2], argb, light, out);
		}
	}

	private void meshVertex(float[] v, float[] uv1, int i, int argb, int light, Sink out) {
		int o = i * dev.mcrl.rlassets.MeshData.STRIDE;
		m.transformPosition(t.set(v[o], v[o + 1], v[o + 2]));
		m.transformDirection(n.set(v[o + 3], v[o + 4], v[o + 5]));
		if (n.lengthSquared() > 0) {
			n.normalize();
		}
		boolean two = uv1.length > 0;
		out.vertex(t.x, t.y, t.z, v[o + 6], v[o + 7], two ? uv1[2 * i] : 0, two ? uv1[2 * i + 1] : 0, 0, argb, light,
				n.x, n.y, n.z);
	}

	private void ensure(int count) {
		if (dist.length < count) {
			int c = Math.max(count, dist.length * 2);
			dist = new float[c];
			order = new int[c];
			keys = new long[c];
		}
	}
}
