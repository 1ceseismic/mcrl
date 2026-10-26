package dev.mcrl.rlassets;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Mcrm {
	public static final int VERSION = 1, VERSION_UV1 = 2;
	private static final int HEADER = 16;
	private static final byte[] MAGIC = {'M', 'C', 'R', 'M'};

	private Mcrm() {
	}

	public static void write(Path file, MeshData mesh) throws IOException {
		int nv = mesh.vertexCount(), ni = mesh.indices().length;
		ByteBuffer b = ByteBuffer.allocate(HEADER + 4 * (nv * MeshData.STRIDE + ni + mesh.uv1().length))
				.order(ByteOrder.LITTLE_ENDIAN);
		b.put(MAGIC).putInt(mesh.uv1().length == 0 ? VERSION : VERSION_UV1).putInt(nv).putInt(ni);
		for (float f : mesh.vertices()) {
			b.putFloat(f);
		}
		for (int i : mesh.indices()) {
			b.putInt(i);
		}
		for (float f : mesh.uv1()) {
			b.putFloat(f);
		}
		Files.write(file, b.array());
	}

	public static MeshData read(Path file) throws IOException {
		byte[] all = Files.readAllBytes(file);
		ByteBuffer b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
		if (all.length < HEADER || all[0] != MAGIC[0] || all[1] != MAGIC[1] || all[2] != MAGIC[2] || all[3] != MAGIC[3]) {
			throw new IOException(file + ": not an MCRM mesh");
		}
		int version = b.getInt(4);
		if (version != VERSION && version != VERSION_UV1) {
			throw new IOException(file + ": MCRM version " + version + ", expected " + VERSION + " or " + VERSION_UV1);
		}
		long nv = Integer.toUnsignedLong(b.getInt(8)), ni = Integer.toUnsignedLong(b.getInt(12));
		long nuv = version == VERSION_UV1 ? 2 * nv : 0;
		long want = HEADER + 4 * (nv * MeshData.STRIDE + ni + nuv);
		if (want != all.length) {
			throw new IOException(file + ": size " + all.length + " does not match its header (" + want + " bytes)");
		}
		float[] v = new float[(int) nv * MeshData.STRIDE];
		b.position(HEADER);
		b.asFloatBuffer().get(v);
		int[] ix = new int[(int) ni];
		b.position(HEADER + 4 * v.length);
		b.asIntBuffer().get(ix);
		float[] uv1 = new float[(int) nuv];
		b.position(HEADER + 4 * (v.length + ix.length));
		b.asFloatBuffer().get(uv1);
		try {
			return new MeshData(v, ix, uv1);
		} catch (IllegalArgumentException e) {
			throw new IOException(file + ": " + e.getMessage(), e);
		}
	}
}
