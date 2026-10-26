package dev.mcrl.client.rl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RlCacheTest {
	@TempDir
	Path tmp;

	@Test
	void rejects_wrong_format() throws Exception {
		Path dir = RlCacheFixtures.writeCache(tmp.resolve("cache"), "fp", tmp);
		Path manifest = dir.resolve("manifest.json");
		Files.writeString(manifest, Files.readString(manifest).replace("\"format\": 2", "\"format\": 1"));
		IOException e = assertThrows(IOException.class, () -> RlCache.read(dir));
		assertEquals("cache format 1, this mcrl reads format 2: convert again", e.getMessage());
	}

	@Test
	void rejects_incomplete_cache() throws Exception {
		Path dir = RlCacheFixtures.writeCache(tmp.resolve("cache"), "fp", tmp);
		Files.delete(dir.resolve("meshes/ball.mcrm"));
		IOException e = assertThrows(IOException.class, () -> RlCache.read(dir));
		assertTrue(e.getMessage().contains("ball"), e.getMessage());
	}
}
