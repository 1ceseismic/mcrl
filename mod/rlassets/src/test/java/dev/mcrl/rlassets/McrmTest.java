package dev.mcrl.rlassets;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class McrmTest {
	@TempDir
	Path tmp;

	public static MeshData sample() {
		return new MeshData(new float[] {
				0, 0, 0, 0, 1, 0, 0, 0,
				1, 0, 0, 0, 1, 0, 1, 0,
				0, 0, -1.5f, 0, 1, 0, 0, 1,
				0.25f, 2, 3, 0.6f, 0.8f, 0, 0.5f, 0.5f}, new int[] {0, 2, 1, 1, 2, 3});
	}

	@Test
	void roundTrip() throws IOException {
		MeshData m = sample();
		Path f = tmp.resolve("m.mcrm");
		Mcrm.write(f, m);
		assertEquals(16 + 4 * 32 + 6 * 4, Files.size(f));
		ByteBuffer h = ByteBuffer.wrap(Files.readAllBytes(f)).order(ByteOrder.LITTLE_ENDIAN);
		assertEquals('M', h.get(0));
		assertEquals('C', h.get(1));
		assertEquals('R', h.get(2));
		assertEquals('M', h.get(3));
		assertEquals(1, h.getInt(4));
		assertEquals(4, h.getInt(8));
		assertEquals(6, h.getInt(12));
		MeshData back = Mcrm.read(f);
		assertArrayEquals(m.vertices(), back.vertices());
		assertArrayEquals(m.indices(), back.indices());
		assertEquals(4, back.vertexCount());
	}

	@Test
	void secondUvSetRoundTrips() throws IOException {
		MeshData s = sample();
		MeshData m = new MeshData(s.vertices(), s.indices(), new float[] {0, 0.25f, 1, 0.75f, 0.5f, 0, 0.125f, 1});
		Path f = tmp.resolve("uv1.mcrm");
		Mcrm.write(f, m);
		assertEquals(16 + 4 * 32 + 6 * 4 + 4 * 8, Files.size(f));
		assertEquals(Mcrm.VERSION_UV1, ByteBuffer.wrap(Files.readAllBytes(f)).order(ByteOrder.LITTLE_ENDIAN).getInt(4));
		MeshData back = Mcrm.read(f);
		assertArrayEquals(m.vertices(), back.vertices());
		assertArrayEquals(m.indices(), back.indices());
		assertArrayEquals(m.uv1(), back.uv1());
		assertEquals(0, Mcrm.read(write(sample(), "plain.mcrm")).uv1().length, "version 1: none");
		assertThrows(IllegalArgumentException.class, () -> new MeshData(s.vertices(), s.indices(), new float[6]));
	}

	private Path write(MeshData m, String name) throws IOException {
		Path f = tmp.resolve(name);
		Mcrm.write(f, m);
		return f;
	}

	@Test
	void corruptFilesAreRefused() throws IOException {
		Path f = tmp.resolve("m.mcrm");
		Mcrm.write(f, sample());
		byte[] good = Files.readAllBytes(f);
		byte[] bad = good.clone();
		bad[0] = 'X';
		Files.write(f, bad);
		assertThrows(IOException.class, () -> Mcrm.read(f));
		Files.write(f, java.util.Arrays.copyOf(good, good.length - 4));
		assertThrows(IOException.class, () -> Mcrm.read(f));
		bad = good.clone();
		ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(16 + 4 * 32, 99);
		Files.write(f, bad);
		assertThrows(IOException.class, () -> Mcrm.read(f));
	}

	@Test
	void meshDataRejectsBadShapes() {
		assertThrows(IllegalArgumentException.class, () -> new MeshData(new float[7], new int[0]));
		assertThrows(IllegalArgumentException.class, () -> new MeshData(new float[8], new int[] {0, 0}));
		assertThrows(IllegalArgumentException.class, () -> new MeshData(new float[8], new int[] {0, 0, 1}));
	}
}
