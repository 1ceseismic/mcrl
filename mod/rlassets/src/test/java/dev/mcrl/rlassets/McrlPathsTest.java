package dev.mcrl.rlassets;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McrlPathsTest {
	@TempDir
	Path tmp;

	@Test
	void configuredCacheDir() throws Exception {
		String before = System.getProperty("mcrl.rlCacheDir");
		System.clearProperty("mcrl.rlCacheDir");
		try {
			Path home = Path.of(System.getProperty("user.home"));
			assertEquals(home.resolve("mcrl-no-such-cache"), McrlPaths.cacheDir("~/mcrl-no-such-cache"));
			assertEquals(home.resolve("mcrl-no-such-cache"), McrlPaths.cacheDir("  ~/mcrl-no-such-cache "));
			assertEquals(McrlPaths.cacheDir(), McrlPaths.cacheDir(""), "none configured: the standard dir");

			Path mine = tmp.resolve("mine");
			assertEquals(mine, McrlPaths.cacheDir(mine.toString()), "nothing there: the dir itself");
			Files.createDirectories(mine);
			Files.writeString(mine.resolve("manifest.json"), "{\"format\": 1, \"fingerprint\": \"old\"}");
			assertEquals(tmp.resolve("mine-v2"), McrlPaths.cacheDir(mine.toString()), "an older format: the sibling");
			Files.writeString(mine.resolve("manifest.json"), "{\"format\": 2, \"fingerprint\": \"now\"}");
			assertEquals(mine, McrlPaths.cacheDir(mine.toString()), "a current cache: the dir itself");

			System.setProperty("mcrl.rlCacheDir", tmp.resolve("override").toString());
			assertEquals(tmp.resolve("override"), McrlPaths.cacheDir(mine.toString()), "the property wins");
		} finally {
			if (before == null) {
				System.clearProperty("mcrl.rlCacheDir");
			} else {
				System.setProperty("mcrl.rlCacheDir", before);
			}
		}
	}
}
