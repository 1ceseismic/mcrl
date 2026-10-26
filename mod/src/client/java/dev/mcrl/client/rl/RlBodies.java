package dev.mcrl.client.rl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.mcrl.rlassets.Mcrm;
import dev.mcrl.rlassets.MeshData;
import dev.mcrl.rlassets.bodies.BodyIds;
import dev.mcrl.rlassets.bodies.BodyIndex;
import dev.mcrl.rlassets.bodies.BodyManifest;
import dev.mcrl.rlassets.model.CacheJson;
import dev.mcrl.rlassets.model.Effects;
import dev.mcrl.rlassets.model.FxActorDef;
import dev.mcrl.rlassets.model.MaterialDef;
import dev.mcrl.rlassets.model.Materials;
import dev.mcrl.rlassets.model.TextureInfo;

public final class RlBodies {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");

	private record Body(BodyLook look, Materials materials, Effects effects) {
	}

	private record Snapshot(Map<String, Body> bodies, Materials all) {
	}

	private final RlCache base;
	private final BodyLook octane;
	private volatile Snapshot snapshot;
	private volatile @Nullable BodyIndex index;

	RlBodies(RlCache base) {
		this.base = base;
		this.octane = BodyLook.octane(base);
		this.snapshot = new Snapshot(Map.of(), base.materials());
	}

	public @Nullable BodyIndex index() {
		return index;
	}

	public BodyLook octane() {
		return octane;
	}

	public @Nullable BodyLook look(String id) {
		if (BodyIds.isDefault(id)) {
			return octane;
		}
		Body b = snapshot.bodies().get(id);
		return b == null ? null : b.look();
	}

	public Materials allMaterials() {
		return snapshot.all();
	}

	public void reloadIndex() {
		Path f = base.dir().resolve("bodies").resolve("index.json");
		try {
			index = BodyIndex.fromJson(JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)));
		} catch (NoSuchFileException e) {
			index = null;
		} catch (IOException | RuntimeException e) {
			LOG.warn("mcrl: RL body index {} does not read: {}", f, e.toString());
			index = null;
		}
	}

	void loadAll() {
		reloadIndex();
		Path dir = base.dir().resolve("bodies");
		if (!Files.isDirectory(dir)) {
			return;
		}
		try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
			for (Path p : s) {
				String id = p.getFileName().toString();
				if (BodyIds.valid(id) && !BodyIds.isDefault(id) && Files.isRegularFile(p.resolve("manifest.json"))) {
					try {
						load(id);
					} catch (IOException e) {
						LOG.warn("mcrl: RL body {} skipped: {}", id, e.getMessage());
					}
				}
			}
		} catch (IOException e) {
			LOG.warn("mcrl: RL bodies in {} not listed: {}", dir, e.toString());
		}
	}

	public synchronized void load(String id) throws IOException {
		Path dir = BodyIds.dir(base.dir(), id);
		Body b;
		try {
			b = read(dir, id);
		} catch (RuntimeException e) {
			throw new IOException(e.toString(), e);
		}
		Map<String, Body> bodies = new TreeMap<>(snapshot.bodies());
		bodies.put(id, b);
		snapshot = new Snapshot(Collections.unmodifiableMap(bodies), merge(base.materials(), bodies));
	}

	@Nullable TextureInfo textureInfo(String name) {
		for (Body b : snapshot.bodies().values()) {
			TextureInfo t = b.materials().textures().get(name);
			if (t != null) {
				return t;
			}
		}
		return null;
	}

	@Nullable Path rawTexture(String name) {
		for (Body b : snapshot.bodies().values()) {
			TextureInfo t = b.materials().textures().get(name);
			if (t != null) {
				Path dir = b.look().dir().normalize(), p = dir.resolve(t.file()).normalize();
				return p.startsWith(dir) ? p : null;
			}
		}
		return null;
	}

	RlCache.@Nullable Mesh extraMesh(String name) {
		if (BodyManifest.MESHES.contains(name)) {
			return null;
		}
		for (Body b : snapshot.bodies().values()) {
			RlCache.Mesh m = b.look().mesh(name);
			if (m != null) {
				return m;
			}
		}
		return null;
	}

	@Nullable Effects effectsWith(String path) {
		for (Body b : snapshot.bodies().values()) {
			if (b.effects().systems().containsKey(path)) {
				return b.effects();
			}
		}
		return null;
	}

	private static Body read(Path dir, String id) throws IOException {
		BodyManifest m;
		try {
			m = BodyManifest.fromJson(json(dir.resolve("manifest.json")));
		} catch (IllegalArgumentException e) {
			throw new IOException(e.getMessage(), e);
		}
		if (!m.id().equals(id)) {
			throw new IOException("the manifest in bodies/" + id + " is body " + m.id());
		}
		Map<String, RlCache.Mesh> meshes = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : m.meshes().entrySet()) {
			MeshData d;
			try {
				d = Mcrm.read(inside(dir, e.getValue()));
			} catch (IOException x) {
				throw new IOException("unreadable mesh " + e.getKey() + ": " + x.getMessage(), x);
			}
			meshes.put(e.getKey(), new RlCache.Mesh(d.vertices(), d.indices(), d.uv1()));
		}
		Materials materials;
		try {
			materials = CacheJson.materials(json(inside(dir, m.materials())));
		} catch (IOException | RuntimeException e) {
			throw new IOException("unreadable materials.json: " + e.getMessage(), e);
		}
		Effects effects = Effects.EMPTY;
		if (m.effects() != null) {
			try {
				effects = Effects.fromJson(json(inside(dir, m.effects())));
			} catch (IOException | RuntimeException e) {
				LOG.warn("mcrl: RL body {}: {} does not read, so it has no particle systems of its own: {}", id,
						m.effects(), e.toString());
			}
		}
		FxActorDef bodyFx = null;
		if (m.fxActors() != null) {
			try {
				JsonObject o = json(inside(dir, m.fxActors()));
				bodyFx = CacheJson.fxActors(o.has("actors") ? o.getAsJsonObject("actors") : new JsonObject()).get("body");
			} catch (IOException | RuntimeException e) {
				LOG.warn("mcrl: RL body {}: {} does not read, so it has no FX actor: {}", id, m.fxActors(), e.toString());
			}
		}
		Map<String, Vector3f> sockets = new LinkedHashMap<>();
		m.sockets().forEach((k, v) -> sockets.put(k, new Vector3f(v[0], v[1], v[2])));
		BodyLook look = new BodyLook(id, m.preset(), meshes, m.shellMaterial(), m.chassisMaterial(), m.wheels(), sockets,
				m.fxActor(), bodyFx, dir);
		return new Body(look, materials, effects);
	}

	private static Materials merge(Materials base, Map<String, Body> bodies) {
		Map<String, MaterialDef> mats = new LinkedHashMap<>(base.materials());
		Map<String, TextureInfo> textures = new LinkedHashMap<>(base.textures());
		for (Body b : bodies.values()) {
			b.materials().materials().forEach(mats::putIfAbsent);
			b.materials().textures().forEach(textures::putIfAbsent);
		}
		return new Materials(mats, textures);
	}

	private static JsonObject json(Path f) throws IOException {
		JsonElement e;
		try {
			e = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
		} catch (RuntimeException x) {
			throw new IOException(f.getFileName() + " does not parse: " + x.getMessage(), x);
		}
		if (!e.isJsonObject()) {
			throw new IOException(f.getFileName() + " is not a JSON object");
		}
		return e.getAsJsonObject();
	}

	private static Path inside(Path dir, String file) throws IOException {
		Path p = dir.resolve(file).normalize();
		if (!p.startsWith(dir.normalize())) {
			throw new IOException("a body file lies outside its dir: " + file);
		}
		return p;
	}
}
