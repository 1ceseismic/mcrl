package dev.mcrl.client.render.look;

import static dev.mcrl.math.Coords.UU_PER_BLOCK;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.world.phys.AABB;

import dev.mcrl.client.render.RlModels;
import dev.mcrl.client.render.SimpleModels;
import dev.mcrl.client.rl.BodyLook;
import dev.mcrl.client.rl.RlCache;
import dev.mcrl.math.Pose;
import dev.mcrl.sim.HitboxPreset;
import dev.mcrl.rlassets.MeshData;
import dev.mcrl.rlassets.WheelRig;

public final class MeshBounds {
	// RocketSim ball radius.
	private static final double MIN_BALL_RADIUS_UU = 91.25;

	private MeshBounds() {
	}

	public static AABB of(float[] vertices) {
		return of(vertices, MeshData.STRIDE);
	}

	private static AABB of(float[] vertices, int stride) {
		double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE,
			-Double.MAX_VALUE};
		for (int i = 0; i + 2 < vertices.length; i += stride) {
			grow(b, vertices[i], vertices[i + 1], vertices[i + 2]);
		}
		return box(b);
	}

	public static AABB carLocal(BodyLook look) {
		double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE,
			-Double.MAX_VALUE};
		for (String name : new String[] {"body_shell", "chassis"}) {
			RlCache.Mesh m = look.mesh(name);
			if (m != null) {
				union(b, of(m.vertices()));
			}
		}
		WheelRig rig = look.wheels();
		float[] steers = {-RlLook.MAX_STEER, 0, RlLook.MAX_STEER};
		float[] offsets = {0, -RlLook.MAX_DROOP_UU};
		for (int i = 0; i < 4; i++) {
			String suffix = RlCache.WHEEL_SUFFIXES.get(i);
			for (String part : new String[] {"wheel_rim_", "wheel_tyre_"}) {
				RlCache.Mesh m = look.mesh(part + suffix);
				if (m == null) {
					continue;
				}
				AABB disc = disc(m.vertices());
				for (float steer : WheelRig.front(i) ? steers : new float[] {0}) {
					for (float offset : offsets) {
						PoseStack ps = new PoseStack();
						RlModels.wheelTransform(ps, rig, i, offset, steer, 0);
						union(b, transform(ps.last().pose(), disc));
					}
				}
			}
		}
		return box(b);
	}

	public static AABB simpleLocal(HitboxPreset h) {
		double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE,
			-Double.MAX_VALUE};
		grow(b, h.offsetForward() - h.halfForward(), h.offsetUp() - h.halfUp(), -h.halfRight());
		grow(b, h.offsetForward() + h.halfForward(), h.offsetUp() + h.halfUp(), h.halfRight());
		union(b, of(SimpleModels.carBody(h), SimpleModels.FLOATS_PER_VERTEX));
		for (HitboxPreset.Axle a : new HitboxPreset.Axle[] {h.front(), h.back()}) {
			double r = a.wheelRadius();
			double reach = Math.sqrt(r * r + SimpleModels.WHEEL_WIDTH * SimpleModels.WHEEL_WIDTH / 4);
			double low = a.connUp() - a.suspensionRest(), high = low + 2 * HitboxPreset.MAX_SUSPENSION_TRAVEL;
			for (double side : new double[] {-1, 1}) {
				grow(b, a.connForward() - reach, low - reach, side * a.connRight() - reach);
				grow(b, a.connForward() + reach, high + reach, side * a.connRight() + reach);
			}
		}
		return box(b);
	}

	public static double ballRadius(RlCache rl) {
		RlCache.Mesh m = rl.mesh("ball");
		double max = 0;
		if (m != null) {
			float[] v = m.vertices();
			for (int i = 0; i + 2 < v.length; i += MeshData.STRIDE) {
				max = Math.max(max, Math.sqrt(v[i] * v[i] + v[i + 1] * v[i + 1] + v[i + 2] * v[i + 2]));
			}
		}
		return Math.max(max * UU_PER_BLOCK, MIN_BALL_RADIUS_UU);
	}

	public static AABB worldBox(Pose pose, AABB local) {
		Matrix4f m = new Matrix4f().rotation(new Quaternionf(pose.qx(), pose.qy(), pose.qz(), pose.qw()));
		AABB rotated = transform(m, local);
		return rotated.move(pose.x(), pose.y(), pose.z());
	}

	private static AABB disc(float[] v) {
		double r = 0, zMin = Double.MAX_VALUE, zMax = -Double.MAX_VALUE;
		for (int i = 0; i + 2 < v.length; i += MeshData.STRIDE) {
			r = Math.max(r, Math.sqrt(v[i] * v[i] + v[i + 1] * v[i + 1]));
			zMin = Math.min(zMin, v[i + 2]);
			zMax = Math.max(zMax, v[i + 2]);
		}
		return new AABB(-r, -r, zMin, r, r, zMax);
	}

	private static AABB transform(Matrix4f m, AABB box) {
		double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE,
			-Double.MAX_VALUE};
		Vector3f p = new Vector3f();
		for (int i = 0; i < 8; i++) {
			p.set((i & 1) != 0 ? box.maxX : box.minX, (i & 2) != 0 ? box.maxY : box.minY,
					(i & 4) != 0 ? box.maxZ : box.minZ);
			m.transformPosition(p);
			grow(b, p.x, p.y, p.z);
		}
		return box(b);
	}

	private static void grow(double[] b, double x, double y, double z) {
		b[0] = Math.min(b[0], x);
		b[1] = Math.min(b[1], y);
		b[2] = Math.min(b[2], z);
		b[3] = Math.max(b[3], x);
		b[4] = Math.max(b[4], y);
		b[5] = Math.max(b[5], z);
	}

	private static void union(double[] b, AABB a) {
		grow(b, a.minX, a.minY, a.minZ);
		grow(b, a.maxX, a.maxY, a.maxZ);
	}

	private static AABB box(double[] b) {
		return new AABB(b[0], b[1], b[2], b[3], b[4], b[5]);
	}
}
