package dev.mcrl.rlassets.bodies;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;

import dev.mcrl.rlassets.CacheWriter;
import dev.mcrl.rlassets.Mcrm;
import dev.mcrl.rlassets.MeshData;
import dev.mcrl.rlassets.model.CacheJson;

public final class BodyCacheWriter {
	private BodyCacheWriter() {
	}

	public static void writeIndex(Path cacheDir, BodyIndex index, CacheWriter.Guard guard) throws IOException {
		CacheWriter.checkCache(cacheDir, guard);
		try (CacheWriter.CacheLock held = CacheWriter.lock(cacheDir)) {
			Path dir = Files.createDirectories(cacheDir.resolve("bodies"));
			Path target = dir.resolve("index.json"), tmp = dir.resolve("index.json.tmp-" + ProcessHandle.current().pid());
			try {
				Files.writeString(tmp, index.toJson().toString(), StandardCharsets.UTF_8);
				try {
					Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				} catch (AtomicMoveNotSupportedException e) {
					Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
				}
			} finally {
				Files.deleteIfExists(tmp);
			}
		}
	}

	public static List<String> write(Path cacheDir, BodyManifest m, Map<String, MeshData> meshes, Map<String, Path> files,
			CacheWriter.Guard guard) throws IOException, CacheWriter.ValidationException {
		try {
			return CacheWriter.writeNested(cacheDir, "bodies", m.id(), guard, dir -> {
				List<String> problems = new ArrayList<>();
				Files.createDirectories(dir.resolve("meshes"));
				for (Map.Entry<String, String> e : m.meshes().entrySet()) {
					MeshData d = meshes.get(e.getKey());
					if (d == null) {
						problems.add("no mesh data for " + e.getKey());
						continue;
					}
					Mcrm.write(inside(dir, e.getValue()), d);
				}
				for (Map.Entry<String, Path> e : files.entrySet()) {
					Path to = inside(dir, e.getKey());
					Files.createDirectories(to.getParent());
					Files.copy(e.getValue(), to);
				}
				Files.writeString(dir.resolve("manifest.json"), new GsonBuilder().setPrettyPrinting()
						.disableHtmlEscaping().create().toJson(m.toJson()) + "\n", StandardCharsets.UTF_8);
				problems.addAll(validate(dir, m, meshes, files));
				if (!problems.isEmpty()) {
					throw new Invalid(problems);
				}
			});
		} catch (Invalid e) {
			throw new CacheWriter.ValidationException(e.problems);
		}
	}

	private static List<String> validate(Path dir, BodyManifest m, Map<String, MeshData> meshes, Map<String, Path> files)
			throws IOException {
		List<String> p = new ArrayList<>();
		try {
			BodyManifest back = BodyManifest.fromJson(JsonParser.parseString(Files.readString(dir.resolve("manifest.json"),
					StandardCharsets.UTF_8)));
			if (!back.equals(m)) {
				p.add("the body manifest did not read back");
			}
		} catch (RuntimeException e) {
			p.add("the body manifest does not read: " + e.getMessage());
		}
		for (Map.Entry<String, String> e : m.meshes().entrySet()) {
			MeshData want = meshes.get(e.getKey());
			if (want == null) {
				continue;
			}
			if (want.triangleCount() == 0) {
				p.add("mesh " + e.getKey() + " has no triangles");
			}
			MeshData back = Mcrm.read(inside(dir, e.getValue()));
			if (back.vertexCount() != want.vertexCount() || back.indices().length != want.indices().length) {
				p.add("mesh " + e.getKey() + " did not read back");
			}
		}
		for (Map.Entry<String, Path> e : files.entrySet()) {
			Path copy = inside(dir, e.getKey());
			if (!Files.isRegularFile(copy) || Files.size(copy) != Files.size(e.getValue())) {
				p.add(e.getKey() + " was not copied");
			}
		}
		try {
			CacheJson.materials(JsonParser.parseString(Files.readString(inside(dir, m.materials()),
					StandardCharsets.UTF_8)).getAsJsonObject());
		} catch (IOException | RuntimeException e) {
			p.add(m.materials() + " does not read: " + e.getMessage());
		}
		return p;
	}

	private static Path inside(Path dir, String file) throws IOException {
		Path p = dir.resolve(file).normalize();
		if (!p.startsWith(dir) || p.equals(dir)) {
			throw new IOException("a body file outside the body dir: " + file);
		}
		return p;
	}

	private static final class Invalid extends IOException {
		final List<String> problems;

		Invalid(List<String> problems) {
			super(String.join("; ", problems));
			this.problems = List.copyOf(problems);
		}
	}
}
