package dev.mcrl.rlassets;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PskReaderTest {
	@TempDir
	Path tmp;

	static PskFixtures.Builder tetra(boolean face32) {
		PskFixtures.Builder b = new PskFixtures.Builder();
		b.extraChunks = true;
		b.face32 = face32;
		b.materials.addAll(List.of("Chassis_MIC", "Body_MIC"));
		int p0 = b.point(0, 0, 0), p1 = b.point(10, 0, 0), p2 = b.point(0, 10, 0), p3 = b.point(0, 0, 10);
		int w0 = b.wedge(p0, 0.0, 0.0, 0), w1 = b.wedge(p1, 1.0, 0.0, 0), w2 = b.wedge(p2, 0.0, 1.0, 1);
		int w3 = b.wedge(p3, 0.5, 0.25, 1), w4 = b.wedge(p0, 0.75, 0.5, 1);
		b.face(w0, w2, w1, 0, 1);
		b.face(w0, w1, w3, 0, 1);
		b.face(w4, w3, w2, 1, 2);
		b.face(w1, w2, w3, 1, 1);
		b.bone("root_jnt", 0, 0, 0, 0);
		b.bone("frame_jnt", 0, 1, 2, 3);
		b.bones.add(new PskFixtures.Bone("FL_Disc_jnt", 1, new float[] {0, 0, 0.7071068f, -0.7071068f},
				new float[] {51.25f, 25.9f, -6f}));
		return b;
	}

	void assertTetra(PskMesh m) {
		assertEquals(4, m.pointCount());
		assertArrayEquals(new float[] {0, 0, 0, 10, 0, 0, 0, 10, 0, 0, 0, 10}, m.points());
		assertEquals(5, m.wedgeCount());
		assertArrayEquals(new int[] {0, 1, 2, 3, 0}, m.wedgePoint());
		assertArrayEquals(new float[] {0, 0, 1, 0, 0, 1, 0.5f, 0.25f, 0.75f, 0.5f}, m.wedgeUv());
		assertArrayEquals(new int[] {0, 0, 1, 1, 1}, m.wedgeMat());
		assertEquals(4, m.faceCount());
		assertArrayEquals(new int[] {0, 2, 1, 0, 1, 3, 4, 3, 2, 1, 2, 3}, m.faces());
		assertArrayEquals(new int[] {0, 0, 1, 1}, m.faceMat());
		assertArrayEquals(new int[] {1, 1, 2, 1}, m.faceSmoothing());
		assertEquals(List.of("Chassis_MIC", "Body_MIC"), m.materials());
		assertEquals(3, m.bones().size());
		PskMesh.Bone fl = m.bones().get(2);
		assertEquals("FL_Disc_jnt", fl.name());
		assertEquals(1, fl.parent());
		assertArrayEquals(new float[] {0, 0, 0.7071068f, -0.7071068f}, fl.rotation());
		assertArrayEquals(new float[] {51.25f, 25.9f, -6f}, fl.position());
		assertEquals(1, m.bones().get(1).numChildren());
		assertEquals(2, m.boneIndex("FL_Disc_jnt"));
		assertEquals(-1, m.boneIndex("nope"));
	}

	@Test
	void pskxReadsBackExactly() throws IOException {
		assertTetra(PskReader.read(tetra(false).write(tmp.resolve("t.pskx"))));
	}

	@Test
	void notAPskIsRefused() throws IOException {
		Path f = Files.writeString(tmp.resolve("x.psk"), "hello, this is not a mesh at all, nope");
		assertThrows(IOException.class, () -> PskReader.read(f));
		byte[] good = tetra(false).bytes();
		Path cut = tmp.resolve("cut.psk");
		Files.write(cut, java.util.Arrays.copyOf(good, good.length - 7));
		assertThrows(IOException.class, () -> PskReader.read(cut));
	}
}
