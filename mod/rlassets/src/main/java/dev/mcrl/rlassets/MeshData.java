package dev.mcrl.rlassets;

public record MeshData(float[] vertices, int[] indices, float[] uv1) {
	public static final int STRIDE = 8;

	public MeshData(float[] vertices, int[] indices) {
		this(vertices, indices, new float[0]);
	}

	public MeshData {
		if (vertices.length % STRIDE != 0) {
			throw new IllegalArgumentException("vertex data is not a multiple of " + STRIDE + " floats");
		}
		if (uv1.length != 0 && uv1.length != vertices.length / STRIDE * 2) {
			throw new IllegalArgumentException(uv1.length / 2 + " second UVs for " + vertices.length / STRIDE
					+ " vertices");
		}
		if (indices.length % 3 != 0) {
			throw new IllegalArgumentException("index count " + indices.length + " is not a multiple of 3");
		}
		int n = vertices.length / STRIDE;
		for (int i : indices) {
			if (i < 0 || i >= n) {
				throw new IllegalArgumentException("index " + i + " out of range (" + n + " vertices)");
			}
		}
	}

	public int vertexCount() {
		return vertices.length / STRIDE;
	}

	public int triangleCount() {
		return indices.length / 3;
	}
}
