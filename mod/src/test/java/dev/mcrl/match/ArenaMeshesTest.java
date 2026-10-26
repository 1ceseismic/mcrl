package dev.mcrl.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mcrl.match.ArenaMeshes.Missing;
import dev.mcrl.rlassets.CacheWriter;
import dev.mcrl.rlassets.arena.ArenaCache;
import dev.mcrl.rlassets.arena.CmfHash;
import dev.mcrl.testkit.TestArena;

class ArenaMeshesTest {
	@TempDir
	Path dir;

	@AfterEach
	void reset() {
		System.clearProperty(ArenaMeshes.PROPERTY);
	}

	private static void use(Path folder) {
		System.setProperty(ArenaMeshes.PROPERTY, folder.toString());
	}

	@Test
	void identity() throws IOException {
		use(TestArena.write(dir.resolve("a")));
		long id = ArenaMeshes.identity();
		assertNotEquals(0, id);
		assertEquals(id, ArenaMeshes.identity());
		assertEquals(id, ArenaMeshes.identityOf(TestArena.files()));
		use(TestArena.write(dir.resolve("b/soccar")).getParent());
		assertEquals(id, ArenaMeshes.identity(), "the same files in RocketSim's layout");
		List<byte[]> other = new ArrayList<>(TestArena.files());
		byte[] f = other.get(3).clone();
		f[f.length - 1] ^= 1;
		other.set(3, f);
		assertNotEquals(id, ArenaMeshes.identityOf(other), "one byte of one file");
		assertNotEquals(id, ArenaMeshes.identityOf(TestArena.files().subList(0, 15)), "a partial set");
		assertEquals(0, ArenaMeshes.identityOf(List.of()));
		use(dir.resolve("absent"));
		assertEquals(0, ArenaMeshes.identity(), "no files");
	}

	@Test
	void badFile() throws IOException {
		Path d = TestArena.write(dir.resolve("bad"));
		Files.write(d.resolve("mesh_3.cmf"), new byte[] {10, 0, 0, 0, 3, 0, 0, 0, 1, 2, 3});
		use(d);
		Missing m = assertThrows(Missing.class, ArenaMeshes::load);
		assertTrue(m.getMessage().contains("file 3"), m.getMessage());
		assertTrue(m.getMessage().contains("arenaMeshesDir"), m.getMessage());
	}

	@Test
	void javaHashMatchesNative() throws Missing, IOException {
		int[] natives = TestArena.load().hashes();
		List<byte[]> files = TestArena.files();
		assertEquals(files.size(), natives.length);
		for (int i = 0; i < natives.length; i++) {
			assertEquals(natives[i], CmfHash.of(files.get(i)), "file " + i);
		}
	}

	static Path convertedCache(Path dir, List<byte[]> cmf) throws IOException {
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("manifest.json"), "{\"format\": 2, \"fingerprint\": \"test\"}");
		CacheWriter.writeSubtree(dir, ArenaCache.DIR, CacheWriter.Guard.standard(null), d -> {
			Path soccar = d.resolve(ArenaCache.SOCCAR);
			Files.createDirectories(soccar);
			List<String> hashes = new ArrayList<>();
			for (int i = 0; i < cmf.size(); i++) {
				Files.write(soccar.resolve("mesh_" + i + ".cmf"), cmf.get(i));
				hashes.add(String.format("\"0x%08X\"", CmfHash.of(cmf.get(i))));
			}
			Files.writeString(d.resolve(ArenaCache.JSON), "{\"arenaVersion\": " + ArenaCache.ARENA_VERSION
					+ ", \"fingerprint\": \"test;arena=1\", \"hashes\": [" + String.join(", ", hashes)
					+ "], \"accepted\": true}");
		});
		return dir;
	}

	@Test
	void theCacheArenaMustMatchItsHashes() throws Exception {
		List<byte[]> set = TestArena.files();
		Path cache = convertedCache(dir.resolve("m7"), set);
		Path soccar = cache.resolve("arena/soccar");
		assertEquals(soccar, ArenaCache.accepted(cache));
		Path f = soccar.resolve("mesh_3.cmf");
		byte[] was = Files.readAllBytes(f);
		byte[] moved = was.clone();
		int vAt = 8 + 12 * java.nio.ByteBuffer.wrap(was).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(0);
		java.nio.ByteBuffer.wrap(moved).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(vAt,
				java.nio.ByteBuffer.wrap(was).order(java.nio.ByteOrder.LITTLE_ENDIAN).getFloat(vAt) + 7);
		assertNotEquals(CmfHash.of(was), CmfHash.of(moved));
		Files.write(f, moved);
		assertEquals(null, ArenaCache.accepted(cache), "a file whose hash is not arena.json's");
		Files.write(f, was);
		assertEquals(soccar, ArenaCache.accepted(cache));
		Files.delete(f);
		assertEquals(null, ArenaCache.accepted(cache), "a missing file");
		Files.write(f, was);
		Files.write(soccar.resolve("mesh_16.cmf"), was);
		assertEquals(null, ArenaCache.accepted(cache), "an extra file");
		Files.delete(soccar.resolve("mesh_16.cmf"));
		assertEquals(soccar, ArenaCache.accepted(cache));
		Path empty = dir.resolve("m7-refused");
		Files.createDirectories(empty);
		Files.writeString(empty.resolve("manifest.json"), "{\"format\": 2, \"fingerprint\": \"test\"}");
		assertThrows(IllegalArgumentException.class, () -> ArenaCache.write(empty, set, "t", CacheWriter.Guard.standard(null)));
		assertTrue(Files.notExists(empty.resolve("arena")), "nothing written");
	}
}
