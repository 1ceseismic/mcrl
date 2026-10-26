package dev.mcrl.rlassets;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class PskReader {
	static final String MAGIC = "ACTR" + "HEAD";
	private static final int HEADER = 32;

	private PskReader() {
	}

	public static PskMesh read(Path file) throws IOException {
		try {
			return parse(ByteBuffer.wrap(Files.readAllBytes(file)));
		} catch (IOException e) {
			throw new IOException(file + ": " + e.getMessage(), e);
		}
	}

	static PskMesh parse(ByteBuffer in) throws IOException {
		ByteBuffer b = in.order(ByteOrder.LITTLE_ENDIAN);
		float[] points = null;
		int[] wedgePoint = null, wedgeMat = null, faces = null, faceMat = null, faceSmoothing = null;
		float[] wedgeUv = null;
		float[] wedgeUv1 = new float[0];
		List<String> materials = new ArrayList<>();
		List<PskMesh.Bone> bones = new ArrayList<>();
		boolean first = true;
		try {
			while (b.remaining() > 0) {
				if (b.remaining() < HEADER) {
					throw new IOException("truncated chunk header at byte " + b.position());
				}
				String id = cString(b, 20);
				b.getInt(); // type flag
				int size = b.getInt(), count = b.getInt();
				if (first && !MAGIC.equals(id)) {
					throw new IOException("not a PSK/PSKX file (first chunk '" + id + "')");
				}
				first = false;
				long len = (long) size * count;
				if (size < 0 || count < 0 || len > b.remaining()) {
					throw new IOException("chunk " + id + " (" + size + " x " + count + " bytes) runs past the end of the file");
				}
				ByteBuffer c = b.slice(b.position(), (int) len).order(ByteOrder.LITTLE_ENDIAN);
				b.position(b.position() + (int) len);
				switch (id) {
					case "PNTS0000" -> {
						need(id, size, 12);
						points = new float[3 * count];
						for (int i = 0; i < points.length; i++) {
							points[i] = c.getFloat(i / 3 * size + 4 * (i % 3));
						}
					}
					case "VTXW0000" -> {
						need(id, size, 16);
						wedgePoint = new int[count];
						wedgeUv = new float[2 * count];
						wedgeMat = new int[count];
						for (int i = 0; i < count; i++) {
							int o = i * size;
							wedgePoint[i] = c.getInt(o);
							wedgeUv[2 * i] = c.getFloat(o + 4);
							wedgeUv[2 * i + 1] = c.getFloat(o + 8);
							wedgeMat[i] = c.get(o + 12) & 0xFF;
						}
					}
					case "FACE0000", "FACE3200" -> {
						boolean wide = id.equals("FACE3200");
						need(id, size, wide ? 18 : 12);
						faces = new int[3 * count];
						faceMat = new int[count];
						faceSmoothing = new int[count];
						for (int i = 0; i < count; i++) {
							int o = i * size;
							for (int k = 0; k < 3; k++) {
								faces[3 * i + k] = wide ? c.getInt(o + 4 * k) : c.getShort(o + 2 * k) & 0xFFFF;
							}
							int m = o + (wide ? 12 : 6);
							faceMat[i] = c.get(m) & 0xFF;
							faceSmoothing[i] = c.getInt(m + 2);
						}
					}
					case "MATT0000" -> {
						need(id, size, 64);
						for (int i = 0; i < count; i++) {
							materials.add(cString(c.slice(i * size, 64), 64));
						}
					}
					case "REFSKELT" -> {
						need(id, size, 120);
						for (int i = 0; i < count; i++) {
							ByteBuffer o = c.slice(i * size, size).order(ByteOrder.LITTLE_ENDIAN);
							String name = cString(o, 64);
							int flags = o.getInt(), children = o.getInt(), parent = o.getInt();
							float[] q = {o.getFloat(), o.getFloat(), o.getFloat(), o.getFloat()};
							float[] p = {o.getFloat(), o.getFloat(), o.getFloat()};
							bones.add(new PskMesh.Bone(name, flags, children, parent, q, p));
						}
					}
					case "EXTRAUVS0" -> {
						need(id, size, 8);
						wedgeUv1 = new float[2 * count];
						for (int i = 0; i < count; i++) {
							wedgeUv1[2 * i] = c.getFloat(i * size);
							wedgeUv1[2 * i + 1] = c.getFloat(i * size + 4);
						}
					}
					default -> {
					}
				}
			}
		} catch (BufferUnderflowException | IndexOutOfBoundsException e) {
			throw new IOException("malformed PSK: " + e, e);
		}
		if (first) {
			throw new IOException("empty file");
		}
		if (points == null || wedgePoint == null || faces == null) {
			throw new IOException("missing " + (points == null ? "PNTS0000" : wedgePoint == null ? "VTXW0000" : "FACE0000/FACE3200"));
		}
		int np = points.length / 3;
		for (int i = 0; i < wedgePoint.length; i++) {
			int p = wedgePoint[i];
			// Old writers leave junk in 16-bit index padding.
			if ((p < 0 || p >= np) && (p & 0xFFFF) < np && np <= 65536) {
				wedgePoint[i] = p & 0xFFFF;
			} else if (p < 0 || p >= np) {
				throw new IOException("wedge " + i + " points at " + p + " of " + np + " points");
			}
		}
		for (int i = 0; i < faces.length; i++) {
			if (faces[i] < 0 || faces[i] >= wedgePoint.length) {
				throw new IOException("face " + i / 3 + " uses wedge " + faces[i] + " of " + wedgePoint.length);
			}
		}
		if (wedgeUv1.length != 0 && wedgeUv1.length != 2 * wedgePoint.length) {
			throw new IOException("EXTRAUVS0 has " + wedgeUv1.length / 2 + " UVs for " + wedgePoint.length + " wedges");
		}
		return new PskMesh(points, wedgePoint, wedgeUv, wedgeMat, faces, faceMat, faceSmoothing, List.copyOf(materials),
				List.copyOf(bones), wedgeUv1);
	}

	private static void need(String id, int size, int min) throws IOException {
		if (size < min) {
			throw new IOException("chunk " + id + " has " + size + "-byte records, expected at least " + min);
		}
	}

	private static String cString(ByteBuffer b, int n) {
		byte[] raw = new byte[n];
		b.get(raw);
		int len = 0;
		while (len < n && raw[len] != 0) {
			len++;
		}
		return new String(raw, 0, len, StandardCharsets.ISO_8859_1);
	}
}
