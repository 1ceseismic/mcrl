package dev.mcrl.rlassets;

import java.util.List;

public record PskMesh(float[] points, int[] wedgePoint, float[] wedgeUv, int[] wedgeMat, int[] faces, int[] faceMat,
		int[] faceSmoothing, List<String> materials, List<Bone> bones, float[] wedgeUv1) {
	public PskMesh(float[] points, int[] wedgePoint, float[] wedgeUv, int[] wedgeMat, int[] faces, int[] faceMat,
			int[] faceSmoothing, List<String> materials, List<Bone> bones) {
		this(points, wedgePoint, wedgeUv, wedgeMat, faces, faceMat, faceSmoothing, materials, bones, new float[0]);
	}

	public record Bone(String name, int flags, int numChildren, int parent, float[] rotation, float[] position) {
	}

	public int pointCount() {
		return points.length / 3;
	}

	public int wedgeCount() {
		return wedgePoint.length;
	}

	public int faceCount() {
		return faceMat.length;
	}

	public int boneIndex(String name) {
		for (int i = 0; i < bones.size(); i++) {
			if (bones.get(i).name().equals(name)) {
				return i;
			}
		}
		return -1;
	}

	public double[] boneBindPosition(int i) {
		return bindPose(i, 0)[0];
	}

	public double[] boneBindRotation(int i) {
		return bindPose(i, 0)[1];
	}

	private double[][] bindPose(int i, int depth) {
		if (depth > bones.size()) {
			throw new IllegalStateException("bone parent cycle at " + bones.get(i).name());
		}
		Bone b = bones.get(i);
		double[] p = {b.position()[0], b.position()[1], b.position()[2]};
		double[] q = {b.rotation()[0], b.rotation()[1], b.rotation()[2], b.rotation()[3]};
		if (i == 0 || b.parent() == i || b.parent() < 0 || b.parent() >= bones.size()) {
			return new double[][] {p, q};
		}
		double[][] parent = bindPose(b.parent(), depth + 1);
		double[] pq = parent[1];
		double[] pos = rotate(pq, p);
		pos[0] += parent[0][0];
		pos[1] += parent[0][1];
		pos[2] += parent[0][2];
		double[] conj = {-q[0], -q[1], -q[2], q[3]};
		return new double[][] {pos, mul(pq, conj)};
	}

	static double[] mul(double[] a, double[] b) {
		return new double[] {
				a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
				a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
				a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
				a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]};
	}

	static double[] rotate(double[] q, double[] v) {
		double n = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
		if (n == 0) {
			return v.clone();
		}
		double x = q[0] / n, y = q[1] / n, z = q[2] / n, w = q[3] / n;
		double tx = 2 * (y * v[2] - z * v[1]), ty = 2 * (z * v[0] - x * v[2]), tz = 2 * (x * v[1] - y * v[0]);
		return new double[] {
				v[0] + w * tx + (y * tz - z * ty),
				v[1] + w * ty + (z * tx - x * tz),
				v[2] + w * tz + (x * ty - y * tx)};
	}
}
