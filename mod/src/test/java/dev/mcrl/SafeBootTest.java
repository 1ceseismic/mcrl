package dev.mcrl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeBootTest {
	@TempDir
	Path dir;

	private Path config(String json) throws IOException {
		Path f = dir.resolve("mcrl.json");
		Files.writeString(f, json);
		return f;
	}

	@Test
	void false_is_off() throws IOException {
		assertFalse(SafeBoot.configured(config("{\"visuals\": \"RL\", \"nativeEnabled\": false}"), null));
	}

	@Test
	void corrupt_config_is_on() throws IOException {
		assertTrue(SafeBoot.configured(config("{\"nativeEnabled\": fal"), null), "not JSON");
		assertTrue(SafeBoot.configured(config("[false]"), null), "not an object");
		assertTrue(SafeBoot.configured(config("{\"nativeEnabled\": \"false\"}"), null), "a string");
		assertTrue(SafeBoot.configured(config("{\"nativeEnabled\": 0}"), null), "a number");
		assertTrue(SafeBoot.configured(config("{\"nativeEnabled\": null}"), null), "null");
		assertTrue(SafeBoot.configured(config(""), null), "empty file");
		Path unreadable = dir.resolve("adir.json");
		Files.createDirectories(unreadable);
		assertTrue(SafeBoot.configured(unreadable, null), "a directory");
	}
}
