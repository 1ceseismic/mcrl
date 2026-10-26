package dev.mcrl.testkit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import dev.mcrl.match.ArenaMeshes;

public final class TestArena {
	private static final float BT = 1f / 50;
	private static final float SIDE_X = 4096 * BT, CEILING_Z = 2048 * BT, END_Y = 5120 * BT,
			GOAL_HALF_W = 892.755f * BT, GOAL_H = 642.775f * BT, GOAL_DEPTH = 880 * BT;
	private static @Nullable Path dir;

	private TestArena() {
	}

	public static List<byte[]> files() {
		List<byte[]> files = new ArrayList<>(ArenaMeshes.SOCCAR_MESHES);
		for (int q = 0; q < 4; q++) {
			float sx = q % 2 == 1 ? 1f : -1f, sy = q < 2 ? -1f : 1f;
			float y0 = sy * END_Y, y1 = sy * (END_Y + GOAL_DEPTH), xm = sx * GOAL_HALF_W, xs = sx * SIDE_X;
			float[] toField = {0, -sy, 0};
			files.add(new Cmf()
					.quad(v(xm, y0, 0), v(xs, y0, 0), v(xs, y0, CEILING_Z), v(xm, y0, CEILING_Z), toField)
					.quad(v(0, y0, GOAL_H), v(xm, y0, GOAL_H), v(xm, y0, CEILING_Z), v(0, y0, CEILING_Z), toField)
					.bytes());
			files.add(new Cmf().quad(v(0, y1, 0), v(xm, y1, 0), v(xm, y1, GOAL_H), v(0, y1, GOAL_H), toField).bytes());
			files.add(new Cmf().quad(v(xm, y0, 0), v(xm, y1, 0), v(xm, y1, GOAL_H), v(xm, y0, GOAL_H),
					new float[] {-sx, 0, 0}).bytes());
			files.add(new Cmf().quad(v(0, y0, GOAL_H), v(xm, y0, GOAL_H), v(xm, y1, GOAL_H), v(0, y1, GOAL_H),
					new float[] {0, 0, -1}).bytes());
		}
		return files;
	}

	public static Path write(Path d) throws IOException {
		Files.createDirectories(d);
		List<byte[]> files = files();
		for (int i = 0; i < files.size(); i++) {
			Files.write(d.resolve("mesh_" + i + ".cmf"), files.get(i));
		}
		return d;
	}

	public static synchronized Path dir() {
		if (dir == null) {
			try {
				Path d = Files.createTempDirectory("mcrl-arena");
				d.toFile().deleteOnExit();
				write(d);
				try (Stream<Path> files = Files.list(d)) {
					files.forEach(f -> f.toFile().deleteOnExit());
				}
				dir = d;
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
		return dir;
	}

	public static synchronized ArenaMeshes.Set load() throws ArenaMeshes.Missing {
		String was = System.getProperty(ArenaMeshes.PROPERTY);
		System.setProperty(ArenaMeshes.PROPERTY, dir().toString());
		try {
			return ArenaMeshes.load();
		} finally {
			if (was == null) {
				System.clearProperty(ArenaMeshes.PROPERTY);
			} else {
				System.setProperty(ArenaMeshes.PROPERTY, was);
			}
		}
	}

	private static float[] v(float x, float y, float z) {
		return new float[] {x, y, z};
	}

	private static final class Cmf {
		private final List<Integer> tris = new ArrayList<>();
		private final List<float[]> verts = new ArrayList<>();

		Cmf quad(float[] a, float[] b, float[] c, float[] d, float[] n) {
			float[] ab = sub(b, a), ac = sub(c, a);
			float[] cr = {ab[1] * ac[2] - ab[2] * ac[1], ab[2] * ac[0] - ab[0] * ac[2], ab[0] * ac[1] - ab[1] * ac[0]};
			if (cr[0] * n[0] + cr[1] * n[1] + cr[2] * n[2] < 0) {
				float[] t = b;
				b = d;
				d = t;
			}
			int i = verts.size();
			verts.add(a);
			verts.add(b);
			verts.add(c);
			verts.add(d);
			tris.addAll(List.of(i, i + 1, i + 2, i, i + 2, i + 3));
			return this;
		}

		private static float[] sub(float[] p, float[] q) {
			return new float[] {p[0] - q[0], p[1] - q[1], p[2] - q[2]};
		}

		byte[] bytes() {
			ByteBuffer b = ByteBuffer.allocate(8 + 4 * tris.size() + 12 * verts.size()).order(ByteOrder.LITTLE_ENDIAN);
			b.putInt(tris.size() / 3).putInt(verts.size());
			for (int t : tris) {
				b.putInt(t);
			}
			for (float[] p : verts) {
				b.putFloat(p[0]).putFloat(p[1]).putFloat(p[2]);
			}
			return b.array();
		}
	}
}
