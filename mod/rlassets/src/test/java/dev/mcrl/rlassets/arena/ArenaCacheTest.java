package dev.mcrl.rlassets.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mcrl.rlassets.CacheWriter;

class ArenaCacheTest {
	@TempDir
	Path tmp;

	private Path cache() throws IOException {
		Path c = tmp.resolve("cache");
		Files.createDirectories(c);
		Files.writeString(c.resolve("manifest.json"), "{\"format\": 2, \"fingerprint\": \"base\"}");
		return c;
	}

	private static byte[] quad() {
		return ArenaExtractor.cmf(ArenaExtractor.cooked(ArenaExtractorTest.nxs(new float[] {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0},
				new int[] {0, 1, 2, 0, 2, 3}, 1)), new float[3], new int[3]);
	}

	@Test
	void writeRefusesASetThatFailsAcceptance() throws IOException {
		Path c = cache();
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> ArenaCache.write(c, Collections.nCopies(16, quad()), "fp", CacheWriter.Guard.standard(null)));
		assertTrue(e.getMessage().contains("not RocketSim's"), e.getMessage());
		assertFalse(Files.exists(c.resolve(ArenaCache.DIR)), "nothing written");
		assertNull(ArenaCache.accepted(c));
		assertFalse(ArenaCache.isCurrent(c, "fp"));
	}

	@Test
	void acceptedReadsTheJsonAndTheFiles() throws IOException {
		Path c = cache();
		assertNull(ArenaCache.accepted(c), "no arena");
		ArenaFixtures.acceptedArena(c, List.of(quad()), "fp");
		assertEquals(c.resolve("arena/soccar"), ArenaCache.accepted(c));
		assertTrue(ArenaCache.isCurrent(c, "fp"));
		assertFalse(ArenaCache.isCurrent(c, "fp2"));
		Path json = c.resolve("arena/arena.json");
		String good = Files.readString(json);
		Files.writeString(json, good.replace("\"arenaVersion\": 1", "\"arenaVersion\": 2"));
		assertNull(ArenaCache.accepted(c), "another version");
		Files.writeString(json, good.replace("\"accepted\": true", "\"accepted\": false"));
		assertNull(ArenaCache.accepted(c), "not accepted");
		assertFalse(ArenaCache.isCurrent(c, "fp"));
		Files.writeString(json, "{");
		assertNull(ArenaCache.accepted(c), "unreadable");
		Files.writeString(json, good);
		assertEquals(c.resolve("arena/soccar"), ArenaCache.accepted(c));
	}
}
