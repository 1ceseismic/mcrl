package dev.mcrl.rlassets.arena;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.mcrl.rlassets.CacheWriter;

public final class ArenaCache {
	public static final int ARENA_VERSION = 1;
	public static final String DIR = "arena", JSON = "arena.json", SOCCAR = "soccar";

	private ArenaCache() {
	}

	public static List<String> write(Path cacheDir, List<byte[]> cmf, String fingerprint, CacheWriter.Guard guard)
			throws IOException {
		ArenaExtractor.Acceptance a = ArenaExtractor.accept(cmf);
		if (!a.accepted()) {
			throw new IllegalArgumentException("not an accepted arena: " + String.join("; ", a.problems()));
		}
		return CacheWriter.writeSubtree(cacheDir, DIR, guard, dir -> {
			Path soccar = dir.resolve(SOCCAR);
			Files.createDirectories(soccar);
			JsonArray hashes = new JsonArray();
			for (int i = 0; i < cmf.size(); i++) {
				Files.write(soccar.resolve("mesh_" + i + ".cmf"), cmf.get(i));
				hashes.add(String.format(Locale.ROOT, "0x%08X", CmfHash.of(cmf.get(i))));
			}
			JsonObject o = new JsonObject();
			o.addProperty("arenaVersion", ARENA_VERSION);
			o.addProperty("fingerprint", fingerprint);
			o.add("hashes", hashes);
			o.addProperty("accepted", true);
			Files.writeString(dir.resolve(JSON), new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
					.toJson(o) + "\n", StandardCharsets.UTF_8);
		});
	}

	public static @Nullable Path accepted(Path cacheDir) {
		JsonObject o = json(cacheDir);
		if (o == null || !(o.get("arenaVersion") instanceof JsonElement v) || !v.isJsonPrimitive()
				|| !(o.get("accepted") instanceof JsonElement a) || !a.isJsonPrimitive()
				|| !(o.get("hashes") instanceof JsonArray hashes)) {
			return null;
		}
		Path soccar = cacheDir.resolve(DIR).resolve(SOCCAR);
		try {
			if (v.getAsInt() != ARENA_VERSION || !a.getAsBoolean() || !Files.isDirectory(soccar, LinkOption.NOFOLLOW_LINKS)) {
				return null;
			}
			try (Stream<Path> files = Files.list(soccar)) {
				if (files.filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".cmf")).count()
						!= hashes.size()) {
					return null;
				}
			}
			for (int i = 0; i < hashes.size(); i++) {
				Path f = soccar.resolve("mesh_" + i + ".cmf");
				int want = Integer.parseUnsignedInt(hashes.get(i).getAsString().replaceFirst("^0[xX]", ""), 16);
				if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) || CmfHash.of(Files.readAllBytes(f)) != want) {
					return null;
				}
			}
		} catch (IOException | RuntimeException e) {
			return null;
		}
		return soccar;
	}

	public static boolean isCurrent(Path cacheDir, String fingerprint) {
		JsonObject o = json(cacheDir);
		return accepted(cacheDir) != null && o != null && o.get("fingerprint") instanceof JsonElement f && f.isJsonPrimitive()
				&& f.getAsString().equals(fingerprint);
	}

	private static @Nullable JsonObject json(Path cacheDir) {
		try {
			JsonElement e = JsonParser.parseString(Files.readString(cacheDir.resolve(DIR).resolve(JSON), StandardCharsets.UTF_8));
			return e.isJsonObject() ? e.getAsJsonObject() : null;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}
}
