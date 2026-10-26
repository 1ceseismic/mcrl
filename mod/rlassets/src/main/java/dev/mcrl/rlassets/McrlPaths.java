package dev.mcrl.rlassets;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

public final class McrlPaths {
	private McrlPaths() {
	}

	public static Path dataDir() {
		String xdg = System.getenv("XDG_DATA_HOME");
		Path base = (xdg != null && !xdg.isBlank())
				? Path.of(xdg)
				: Path.of(System.getProperty("user.home"), ".local", "share");
		return base.resolve("mcrl");
	}

	public static Path cacheDir() {
		String o = System.getProperty("mcrl.rlCacheDir");
		if (o != null && !o.isBlank()) {
			return Path.of(o);
		}
		return dataDir().resolve("rl-cache").resolve("v2");
	}

	public static Path cacheDir(String configured) {
		String o = System.getProperty("mcrl.rlCacheDir");
		if ((o == null || o.isBlank()) && !configured.isBlank()) {
			try {
				return configuredCacheDir(home(configured.trim()));
			} catch (InvalidPathException useStandardDir) {
			}
		}
		return cacheDir();
	}

	public static Path configuredCacheDir(Path dir) {
		Path name = dir.getFileName();
		if (name == null) {
			return dir;
		}
		String v = "v" + CacheWriter.FORMAT;
		Path sibling = dir.resolveSibling(name.toString().equals("v1") ? v : name + "-" + v);
		int format = CacheWriter.formatOf(dir);
		boolean older = format >= 1 && format < CacheWriter.FORMAT;
		boolean moved = format == -1 && CacheWriter.formatOf(sibling) == CacheWriter.FORMAT;
		return older || moved ? sibling : dir;
	}

	public static Path home(String dirText) {
		if (dirText.equals("~") || dirText.startsWith("~/")) {
			return Path.of(System.getProperty("user.home") + dirText.substring(1));
		}
		return Path.of(dirText);
	}

	public static Path workDir() {
		return dataDir().resolve("work");
	}

	public static Path toolsDir() {
		return dataDir().resolve("tools");
	}
}
