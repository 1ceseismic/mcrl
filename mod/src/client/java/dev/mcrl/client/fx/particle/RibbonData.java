package dev.mcrl.client.fx.particle;

import java.util.Arrays;

import org.joml.Vector3d;
import org.joml.Vector3f;

import net.minecraft.world.phys.Vec3;

public final class RibbonData {
	private static final int STRIDE = 16;
	private static final double TELEPORT_BLOCKS = SystemInstance.TELEPORT_UU / 50;

	private float[] pts = new float[STRIDE * 64];
	private int[] stripStart = new int[4], stripCount = new int[4];
	private int points, strips;
	private final Vector3d d = new Vector3d();
	private final float[] rgba = new float[4];
	private final Vector3f t = new Vector3f(), axis = new Vector3f(), c = new Vector3f();

	public void build(EmitterInstance e, Vec3 camera) {
		points = 0;
		strips = 0;
		FxEmitterDef.Ribbon ribbon = e.def().typeData().ribbon();
		if (ribbon == null) {
			return;
		}
		for (int trail = 0; trail < e.trails(); trail++) {
			int open = points, newest = -1;
			for (int i = 0; i < e.live(); i++) {
				if (e.trail(i) != trail) {
					continue;
				}
				e.positionMc(i, d);
				if (points > open && gap(points - 1, d, camera) > TELEPORT_BLOCKS) {
					close(open);
					open = points;
				}
				e.color(i, rgba);
				add(d, camera, e.sizeX(i) / 50, rgba, i);
				newest = i;
			}
			if (!ribbon.clipSourceSegment() && newest >= 0 && e.active()) {
				e.trailSourceRl(trail, d);
				d.set(d.x / 50, d.z / 50, d.y / 50);
				if (gap(points - 1, d, camera) <= TELEPORT_BLOCKS) {
					e.color(newest, rgba);
					add(d, camera, e.sizeX(newest) / 50, rgba, newest);
				}
			}
			close(open);
		}
		for (int s = 0; s < strips; s++) {
			edges(stripStart[s], stripCount[s], ribbon);
		}
	}

	private double gap(int k, Vector3d at, Vec3 camera) {
		int o = STRIDE * k;
		double dx = at.x - camera.x - pts[o], dy = at.y - camera.y - pts[o + 1], dz = at.z - camera.z - pts[o + 2];
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private void add(Vector3d at, Vec3 camera, float width, float[] color, int particle) {
		if (pts.length < STRIDE * (points + 1)) {
			pts = Arrays.copyOf(pts, pts.length * 2);
		}
		int o = STRIDE * points++;
		pts[o] = (float) (at.x - camera.x);
		pts[o + 1] = (float) (at.y - camera.y);
		pts[o + 2] = (float) (at.z - camera.z);
		pts[o + 3] = width;
		System.arraycopy(color, 0, pts, o + 4, 4);
		pts[o + 15] = particle;
	}

	private void close(int from) {
		int n = points - from;
		if (n < 2) {
			points = from;
			return;
		}
		if (stripStart.length == strips) {
			stripStart = Arrays.copyOf(stripStart, strips * 2);
			stripCount = Arrays.copyOf(stripCount, strips * 2);
		}
		stripStart[strips] = from;
		stripCount[strips++] = n;
	}

	private void edges(int from, int n, FxEmitterDef.Ribbon ribbon) {
		float length = 0;
		for (int k = 1; k < n; k++) {
			length += dist(from + k - 1, from + k);
		}
		float tiling = ribbon.tilingDistance() > 0 ? ribbon.tilingDistance() / 50 : length;
		float along = 0;
		for (int k = 0; k < n; k++) {
			int o = STRIDE * (from + k);
			if (k > 0) {
				along += dist(from + k - 1, from + k);
			}
			pts[o + 8] = tiling > 0 ? along / tiling : 0;
			int a = from + Math.max(0, k - 1), b = from + Math.min(n - 1, k + 1);
			t.set(pts[STRIDE * b] - pts[STRIDE * a], pts[STRIDE * b + 1] - pts[STRIDE * a + 1],
					pts[STRIDE * b + 2] - pts[STRIDE * a + 2]);
			c.set(pts[o], pts[o + 1], pts[o + 2]);
			if (ribbon.worldUp()) {
				axis.set(0, 1, 0);
			} else {
				t.cross(c, axis);
				if (axis.lengthSquared() < 1e-12f) {
					axis.set(0, 1, 0);
				} else {
					axis.normalize();
				}
			}
			float half = pts[o + 3] / 2;
			pts[o + 9] = c.x - axis.x * half;
			pts[o + 10] = c.y - axis.y * half;
			pts[o + 11] = c.z - axis.z * half;
			pts[o + 12] = c.x + axis.x * half;
			pts[o + 13] = c.y + axis.y * half;
			pts[o + 14] = c.z + axis.z * half;
		}
	}

	private float dist(int a, int b) {
		float dx = pts[STRIDE * b] - pts[STRIDE * a], dy = pts[STRIDE * b + 1] - pts[STRIDE * a + 1],
				dz = pts[STRIDE * b + 2] - pts[STRIDE * a + 2];
		return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	public int strips() {
		return strips;
	}

	public int stripStart(int s) {
		return stripStart[s];
	}

	public int stripPoints(int s) {
		return stripCount[s];
	}

	public int points() {
		return points;
	}

	public int quads() {
		int q = 0;
		for (int s = 0; s < strips; s++) {
			q += stripCount[s] - 1;
		}
		return q;
	}

	public float u(int k) {
		return pts[STRIDE * k + 8];
	}

	public Vector3f edgeA(int k, Vector3f out) {
		int o = STRIDE * k + 9;
		return out.set(pts[o], pts[o + 1], pts[o + 2]);
	}

	public Vector3f edgeB(int k, Vector3f out) {
		int o = STRIDE * k + 12;
		return out.set(pts[o], pts[o + 1], pts[o + 2]);
	}

	public int particle(int k) {
		return (int) pts[STRIDE * k + 15];
	}

	public void color(int k, float[] out) {
		System.arraycopy(pts, STRIDE * k + 4, out, 0, 4);
	}
}
