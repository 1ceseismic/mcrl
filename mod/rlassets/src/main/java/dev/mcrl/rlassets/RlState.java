package dev.mcrl.rlassets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class RlState {
	public record Failed(String fingerprint, String message) {
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private RlState() {
	}

	public static Path standardFile() {
		return McrlPaths.dataDir().resolve("rl-state.json");
	}

	public static @Nullable Path lastInstall(Path file) {
		String s = string(read(file), "lastInstall");
		try {
			return s == null || s.isBlank() ? null : Path.of(s);
		} catch (RuntimeException e) {
			return null;
		}
	}

	public static @Nullable Failed failed(Path file) {
		JsonObject o = read(file);
		String fp = string(o, "failedFingerprint"), msg = string(o, "failedMessage");
		return fp == null || fp.isEmpty() ? null : new Failed(fp, msg == null ? "" : msg);
	}

	public static void rememberInstall(Path file, Path install) throws IOException {
		rememberInstall(file, install, true);
	}

	static void rememberInstall(Path file, Path install, boolean volumeGuard) throws IOException {
		Path abs = install.toAbsolutePath().normalize();
		JsonObject o = read(file);
		if (abs.toString().equals(string(o, "lastInstall")) || (volumeGuard && onVolumeOf(file, abs))) {
			return;
		}
		o.addProperty("lastInstall", abs.toString());
		write(file, o);
	}

	public static void rememberFailed(Path file, @Nullable Failed f) throws IOException {
		JsonObject o = read(file);
		if (java.util.Objects.equals(failed(file), f)) {
			return;
		}
		o.remove("failedFingerprint");
		o.remove("failedMessage");
		if (f != null) {
			o.addProperty("failedFingerprint", f.fingerprint());
			o.addProperty("failedMessage", f.message());
		}
		write(file, o);
	}

	private static JsonObject read(Path file) {
		try {
			JsonElement e = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
			return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
		} catch (IOException | RuntimeException e) {
			return new JsonObject();
		}
	}

	private static @Nullable String string(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	private static void write(Path file, JsonObject o) throws IOException {
		Path dir = file.toAbsolutePath().getParent();
		Files.createDirectories(dir);
		Path tmp = dir.resolve(file.getFileName() + ".tmp-" + ProcessHandle.current().pid());
		Files.writeString(tmp, GSON.toJson(o) + "\n", StandardCharsets.UTF_8);
		try {
			Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	private static boolean onVolumeOf(Path file, Path install) {
		try {
			Path e = file.toAbsolutePath();
			while (e != null && !Files.exists(e)) {
				e = e.getParent();
			}
			return e != null && Files.exists(install) && Files.getFileStore(e).equals(Files.getFileStore(install));
		} catch (IOException | RuntimeException ex) {
			return true;
		}
	}
}
