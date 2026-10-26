package dev.mcrl.rlassets.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class CmfHashTest {
	private static Path rocketSimCpp() {
		for (Path d = Path.of("").toAbsolutePath(); d != null; d = d.getParent()) {
			Path p = d.resolve("native/third_party/RocketSim/src/RocketSim.cpp");
			if (Files.isRegularFile(p)) {
				return p;
			}
		}
		return null;
	}

	@Test
	void knownMatchesRocketSim() throws IOException {
		Path cpp = rocketSimCpp();
		assertNotNull(cpp, "RocketSim.cpp not found above " + Path.of("").toAbsolutePath());
		String src = Files.readString(cpp);
		int at = src.indexOf("gameMode == GameMode::SOCCAR");
		int end = src.indexOf("GameMode::HOOPS", at);
		Matcher m = Pattern.compile("0[xX]([0-9A-Fa-f]{8})").matcher(src.substring(at, end));
		List<Integer> parsed = new ArrayList<>();
		while (m.find()) {
			parsed.add(Integer.parseUnsignedInt(m.group(1), 16));
		}
		assertEquals(CmfHash.SOCCAR_KNOWN, parsed);
	}

	static byte[] cmf(int[] tris, float[] verts) {
		ByteBuffer b = ByteBuffer.allocate(8 + 4 * tris.length + 4 * verts.length).order(ByteOrder.LITTLE_ENDIAN);
		b.putInt(tris.length / 3).putInt(verts.length / 3);
		for (int t : tris) {
			b.putInt(t);
		}
		for (float v : verts) {
			b.putFloat(v);
		}
		return b.array();
	}

	@Test
	void ofMatchesThePythonPort() {
		byte[] f = cmf(new int[] {0, 1, 2, 2, 1, 0}, new float[] {-81.9f, 0.5f, 41.4f, 1, 2, 3, 100000.5f, -0.5f, 7});
		assertEquals(0xB4DEDD44, CmfHash.of(f));
		assertThrows(IllegalArgumentException.class, () -> CmfHash.of(new byte[7]));
		assertThrows(IllegalArgumentException.class, () -> CmfHash.of(java.util.Arrays.copyOf(f, f.length - 1)));
		assertThrows(IllegalArgumentException.class, () -> CmfHash.of(cmf(new int[] {0, 1, 3}, new float[9])));
	}
}
