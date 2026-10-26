package dev.mcrl.natives;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeLoaderExtractTest {
	@TempDir
	Path gameDir;

	@Test
	void neverOverwritesExistingFile() throws Exception {
		byte[] bytes = {9, 8, 7};
		Path p = NativeLoader.extract(new ByteArrayInputStream(bytes), gameDir);
		byte[] marker = {42};
		Files.write(p, marker);
		long before = Files.getLastModifiedTime(p).toMillis();
		Path again = NativeLoader.extract(new ByteArrayInputStream(bytes), gameDir);
		assertEquals(p, again);
		assertArrayEquals(marker, Files.readAllBytes(p));
		assertEquals(before, Files.getLastModifiedTime(p).toMillis());
		assertTrue(Files.isRegularFile(again));
	}
}
