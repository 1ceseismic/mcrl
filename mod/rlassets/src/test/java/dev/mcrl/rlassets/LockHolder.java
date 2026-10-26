package dev.mcrl.rlassets;

import java.nio.file.Files;
import java.nio.file.Path;

public final class LockHolder {
	private LockHolder() {
	}

	public static void main(String[] args) throws Exception {
		Path ready = Path.of(args[1]), release = Path.of(args[2]);
		Files.writeString(ready.resolveSibling(ready.getFileName() + ".start"), "start");
		try (CacheWriter.CacheLock held = CacheWriter.lock(Path.of(args[0]))) {
			Files.writeString(ready, "locked");
			long until = System.nanoTime() + 60_000_000_000L;
			while (!Files.exists(release) && System.nanoTime() < until) {
				Thread.sleep(10);
			}
		}
	}
}
