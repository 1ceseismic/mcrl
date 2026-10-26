package dev.mcrl.rlassets;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MeshAssemblerTest {
	@TempDir
	Path tmp;

	static float[] v(MeshData m, int i) {
		float[] d = m.vertices();
		return new float[] {d[8 * i], d[8 * i + 1], d[8 * i + 2]};
	}

	static float[] n(MeshData m, int i) {
		float[] d = m.vertices();
		return new float[] {d[8 * i + 3], d[8 * i + 4], d[8 * i + 5]};
	}

	static double[] triNormal(MeshData m, int t) {
		int[] ix = m.indices();
		float[] a = v(m, ix[3 * t]), b = v(m, ix[3 * t + 1]), c = v(m, ix[3 * t + 2]);
		double[] nn = PskFixtures.cross(new double[] {b[0] - a[0], b[1] - a[1], b[2] - a[2]},
				new double[] {c[0] - a[0], c[1] - a[1], c[2] - a[2]});
		double l = Math.sqrt(PskFixtures.dot(nn, nn));
		return new double[] {nn[0] / l, nn[1] / l, nn[2] / l};
	}

	@Test
	void axisMapAndWinding() throws IOException {
		PskFixtures.Builder b = new PskFixtures.Builder();
		b.materials.add("M");
		int w0 = b.wedge(b.point(0, 0, 1), 0.25, 0.5, 0);
		int w1 = b.wedge(b.point(0, 0, 0), 0, 0, 0);
		int w2 = b.wedge(b.point(1, 0, 0), 1, 0, 0);
		b.face(w0, w1, w2, 0, 1);
		PskMesh mesh = PskReader.read(b.write(tmp.resolve("tri.psk")));
		MeshData m = MeshAssembler.mesh(mesh, f -> true, MeshAssembler.Placement.IDENTITY);
		assertEquals(3, m.vertexCount());
		assertEquals(3, m.indices().length);
		assertArrayEquals(new double[] {0, 0, -1}, triNormal(m, 0), 1e-6);
		for (int i = 0; i < 3; i++) {
			assertArrayEquals(new float[] {0, 0, -1}, n(m, i), 1e-6f);
		}
		assertArrayEquals(new float[] {0, 0.02f, 0}, v(m, 0), 1e-7f);
		assertArrayEquals(new float[] {0.02f, 0, 0}, v(m, 2), 1e-7f);
		assertEquals(0.25f, m.vertices()[6]);
		assertEquals(0.5f, m.vertices()[7]);
		PskFixtures.Builder c = new PskFixtures.Builder();
		c.materials.add("M");
		int q = c.wedge(c.point(1, 2, 3), 0, 0, 0);
		c.face(q, q, q, 0, 1);
		MeshData mm = MeshAssembler.mesh(PskReader.read(c.write(tmp.resolve("pt.psk"))), f -> true,
				MeshAssembler.Placement.IDENTITY);
		assertArrayEquals(new float[] {0.02f, 0.06f, -0.04f}, v(mm, 0), 1e-7f);
		assertEquals(0, m.uv1().length, "no second UV set");
	}

	static double[] u(float[] mc) {
		return new double[] {mc[0] * 50.0, mc[2] * 50.0, mc[1] * 50.0};
	}

	@Test
	void wheelMeshesAreRlScaledAndCentredOnTheirJoint() throws IOException {
		PskFixtures.Builder b = new PskFixtures.Builder();
		b.materials.add("Wheel_MIC");
		PskFixtures.ring(b, 10, 5, 12, 0);
		PskFixtures.ring(b, 16.313, 7.26439, 64, 0);
		MeshAssembler.Wheels w = MeshAssembler.wheels(PskReader.read(b.write(tmp.resolve("star.pskx"))));
		assertTrue(w.warnings().isEmpty(), w.warnings().toString());
		for (String axle : new String[] {"front", "back"}) {
			for (String side : new String[] {"right", "left"}) {
				double[] lo = {1e9, 1e9, 1e9}, hi = {-1e9, -1e9, -1e9};
				for (String part : new String[] {"rim", "tyre"}) {
					MeshData m = w.meshes().get("wheel_" + part + "_" + axle + "_" + side);
					for (int i = 0; i < m.vertexCount(); i++) {
						double[] p = u(v(m, i));
						for (int k = 0; k < 3; k++) {
							lo[k] = Math.min(lo[k], p[k]);
							hi[k] = Math.max(hi[k], p[k]);
						}
					}
				}
				String what = axle + " " + side;
				double[] centre = {(lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2};
				assertArrayEquals(new double[] {0, side.equals("right") ? 0.25 : -0.25, 0}, centre, 1e-4, what + " centre");
				assertEquals(axle.equals("front") ? 12.7446 : 15.2935, (hi[0] - lo[0]) / 2, 1e-3, what + " radius");
				assertEquals(axle.equals("front") ? 11.1387 : 12.5916, hi[1] - lo[1], 1e-3, what + " width");
			}
		}
	}
}
