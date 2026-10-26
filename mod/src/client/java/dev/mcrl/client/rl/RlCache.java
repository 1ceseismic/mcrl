package dev.mcrl.client.rl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.mcrl.client.render.stadium.StadiumScene;
import dev.mcrl.client.sound.ak.SoundModel;
import dev.mcrl.rlassets.CacheWriter;
import dev.mcrl.rlassets.Mcrm;
import dev.mcrl.rlassets.MeshData;
import dev.mcrl.rlassets.WheelRig;
import dev.mcrl.rlassets.model.CacheJson;
import dev.mcrl.rlassets.model.Effects;
import dev.mcrl.rlassets.model.FxActorDef;
import dev.mcrl.rlassets.model.Materials;
import dev.mcrl.rlassets.model.TextureInfo;
import dev.mcrl.rlassets.model.SoundCue;

public final class RlCache {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	public static final List<String> WHEEL_SUFFIXES = List.of("front_right", "front_left", "back_right", "back_left");
	public static final List<String> REQUIRED_MESHES;
	public static final String PREDATES_SOUND_MODEL = "the RL cache predates RL's sound model";

	static {
		List<String> m = new ArrayList<>(List.of("body_shell", "chassis"));
		for (String part : List.of("rim", "tyre")) {
			for (String w : WHEEL_SUFFIXES) {
				m.add("wheel_" + part + "_" + w);
			}
		}
		m.add("ball");
		REQUIRED_MESHES = List.copyOf(m);
	}

	public record Mesh(float[] vertices, int[] indices, float[] uv1) {
		public int triangleCount() {
			return indices.length / 3;
		}
	}

	private final Path dir;
	private volatile @Nullable Optional<StadiumScene> stadium;
	private int stadiumGeneration;
	private boolean stadiumReading;
	private final String fingerprint;
	private final Map<String, Mesh> meshes;
	private final Map<String, List<String>> meshMaterials;
	private final Map<String, Vector3f> sockets;
	private final WheelRig wheels;
	private final @Nullable String soundsMissing;
	private final CacheWriter.@Nullable SoundsMissingKind soundsMissingKind;
	private final boolean soundsPredateModel;
	private final @Nullable SoundModel soundModel;
	private final Map<String, SoundCue> soundCues;
	private final Fx fx;
	private final Materials materials;
	private final Map<String, String> hud;

	record BaseBody(String shellMaterial, String chassisMaterial, String fxActor, int preset) {
	}

	private final @Nullable BaseBody baseBody;
	private final RlBodies bodies;

	private record Fx(Effects effects, Map<String, FxActorDef> actors, Map<String, JsonObject> objects) {
	}

	private RlCache(Path dir, String fingerprint, Map<String, Mesh> meshes, Map<String, List<String>> meshMaterials,
			Map<String, Vector3f> sockets, WheelRig wheels, @Nullable String soundsMissing,
			CacheWriter.@Nullable SoundsMissingKind soundsMissingKind, boolean soundsPredateModel,
			@Nullable SoundModel soundModel, Map<String, SoundCue> soundCues, Fx fx, Materials materials,
			Map<String, String> hud, @Nullable BaseBody baseBody) {
		this.dir = dir;
		this.fingerprint = fingerprint;
		this.meshes = meshes;
		this.meshMaterials = meshMaterials;
		this.sockets = sockets;
		this.wheels = wheels;
		this.soundsMissing = soundsMissing;
		this.soundsMissingKind = soundsMissingKind;
		this.soundsPredateModel = soundsPredateModel;
		this.soundModel = soundModel;
		this.soundCues = soundCues;
		this.fx = fx;
		this.materials = materials;
		this.hud = hud;
		this.baseBody = baseBody;
		this.bodies = new RlBodies(this);
	}

	public static @Nullable RlCache loaded() {
		return RlConversion.game().cache();
	}

	public Path dir() {
		return dir;
	}

	public @Nullable String soundsMissing() {
		return soundsMissing;
	}

	public CacheWriter.@Nullable SoundsMissingKind soundsMissingKind() {
		return soundsMissingKind;
	}

	public boolean soundsPredateModel() {
		return soundsPredateModel;
	}

	public @Nullable SoundModel soundModel() {
		return soundModel;
	}

	public Map<String, SoundCue> soundCues() {
		return soundCues;
	}

	public String fingerprint() {
		return fingerprint;
	}

	public List<String> meshMaterials(String mesh) {
		return meshMaterials.getOrDefault(mesh, List.of());
	}

	public @Nullable StadiumScene stadium() {
		Optional<StadiumScene> s = stadium;
		return (s != null ? s : readStadium()).orElse(null);
	}

	public @Nullable Optional<StadiumScene> stadiumOr(Executor executor) {
		Optional<StadiumScene> s = stadium;
		if (s != null) {
			return s;
		}
		synchronized (this) {
			if (!stadiumReading) {
				stadiumReading = true;
				executor.execute(() -> {
					try {
						readStadium();
					} finally {
						synchronized (this) {
							stadiumReading = false;
						}
					}
				});
			}
		}
		return null;
	}

	private Optional<StadiumScene> readStadium() {
		int generation;
		synchronized (this) {
			generation = stadiumGeneration;
		}
		Optional<StadiumScene> s = Optional.ofNullable(StadiumScene.load(dir.resolve("stadium")));
		synchronized (this) {
			if (generation == stadiumGeneration) {
				stadium = s;
			}
		}
		return s;
	}

	public synchronized void resetStadium() {
		stadiumGeneration++;
		stadium = null;
	}

	public @Nullable Mesh mesh(String name) {
		Mesh m = meshes.get(name);
		RlBodies b = bodies;
		return m != null || b == null ? m : b.extraMesh(name);
	}

	public RlBodies bodies() {
		return bodies;
	}

	@Nullable BaseBody baseBody() {
		return baseBody;
	}

	public WheelRig wheels() {
		return wheels;
	}

	public Effects effects() {
		return fx.effects();
	}

	public Map<String, FxActorDef> fxActors() {
		return fx.actors();
	}

	public Map<String, JsonObject> fxObjects() {
		return fx.objects();
	}

	public Materials materials() {
		return materials;
	}

	public Materials allMaterials() {
		return bodies.allMaterials();
	}

	public @Nullable TextureInfo textureInfo(String name) {
		if (name.startsWith(StadiumScene.TEXTURE_PREFIX)) {
			Optional<StadiumScene> s = stadium;
			return s == null || s.isEmpty() ? null : s.get().textures().get(name.substring(StadiumScene.TEXTURE_PREFIX
					.length()));
		}
		TextureInfo t = materials.textures().get(name);
		return t != null ? t : bodies.textureInfo(name);
	}

	public @Nullable Path rawTexture(String name) {
		if (name.startsWith(StadiumScene.TEXTURE_PREFIX)) {
			TextureInfo t = textureInfo(name);
			return t == null ? null : within(t.file());
		}
		TextureInfo t = materials.textures().get(name);
		return t != null ? within(t.file()) : bodies.rawTexture(name);
	}

	public @Nullable Effects effectsWith(String systemPath) {
		return fx.effects().systems().containsKey(systemPath) ? fx.effects() : bodies.effectsWith(systemPath);
	}

	public @Nullable Path hud(String name) {
		String f = hud.get(name);
		return f == null ? null : within(f);
	}

	private @Nullable Path within(String file) {
		Path p = dir.resolve(file).normalize();
		return p.startsWith(dir.normalize()) ? p : null;
	}

	Map<String, Vector3f> sockets() {
		return sockets;
	}

	// Slow: never on the render thread.
	public static RlCache read(Path dir) throws IOException {
		JsonObject m;
		try {
			JsonElement e = JsonParser.parseString(Files.readString(dir.resolve("manifest.json"), StandardCharsets.UTF_8));
			if (!e.isJsonObject()) {
				throw new IOException("manifest.json is not a JSON object");
			}
			m = e.getAsJsonObject();
		} catch (RuntimeException e) {
			throw new IOException("unreadable manifest.json: " + e.getMessage(), e);
		}
		try {
			return read(dir, m);
		} catch (RuntimeException e) {
			throw new IOException("bad manifest.json: " + e, e);
		}
	}

	private static RlCache read(Path dir, JsonObject m) throws IOException {
		long start = System.nanoTime();
		int format = m.has("format") ? m.get("format").getAsInt() : -1;
		if (format != CacheWriter.FORMAT) {
			throw new IOException("cache format " + format + ", this mcrl reads format " + CacheWriter.FORMAT + ": convert again");
		}
		String fingerprint = m.has("fingerprint") ? m.get("fingerprint").getAsString() : "";
		JsonObject meshList = object(m, "meshes");

		Map<String, Mesh> meshes = new HashMap<>();
		for (String name : REQUIRED_MESHES) {
			if (!meshList.has(name)) {
				throw new IOException("incomplete cache: no mesh " + name);
			}
			meshes.put(name, mesh(dir, name, meshList.getAsJsonObject(name).get("file").getAsString()));
		}
		Map<String, List<String>> meshMaterials = new HashMap<>();
		for (Map.Entry<String, JsonElement> e : object(m, "extras").entrySet()) {
			try {
				JsonObject o = e.getValue().getAsJsonObject();
				meshes.put(e.getKey(), mesh(dir, e.getKey(), o.get("file").getAsString()));
				if (o.has("materials") && o.get("materials").isJsonArray()) {
					List<String> mats = new ArrayList<>();
					o.getAsJsonArray("materials").forEach(x -> mats.add(x.getAsString()));
					meshMaterials.put(e.getKey(), List.copyOf(mats));
				}
			} catch (IOException | RuntimeException ex) {
				LOG.warn("mcrl: RL cache extra mesh {} skipped: {}", e.getKey(), ex.toString());
			}
		}

		if (!m.has("materials") || !m.get("materials").isJsonPrimitive()) {
			throw new IOException("incomplete cache: no materials.json");
		}
		Materials materials;
		try {
			materials = CacheJson.materials(jsonFile(dir, m.get("materials").getAsString()));
		} catch (IOException | RuntimeException e) {
			throw new IOException("unreadable materials.json: " + e.getMessage(), e);
		}
		Map<String, String> hud = new HashMap<>();
		for (Map.Entry<String, JsonElement> e : object(m, "hud").entrySet()) {
			hud.put(e.getKey(), e.getValue().getAsString());
		}

		Map<String, Vector3f> sockets = new HashMap<>();
		for (Map.Entry<String, JsonElement> e : object(m, "sockets").entrySet()) {
			JsonArray a = e.getValue().getAsJsonArray();
			if (a.size() == 3) {
				sockets.put(e.getKey(), new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()));
			}
		}
		WheelRig wheels;
		try {
			wheels = WheelRig.fromJson(m.get("wheels"));
		} catch (IllegalArgumentException e) {
			throw new IOException("no usable wheel placement (" + e.getMessage() + "): the cache predates converter version 3"
					+ " or is damaged; it must be converted again", e);
		}
		SoundModel soundModel = null;
		JsonElement model = object(m, "sounds").get("model");
		if (model != null && model.isJsonPrimitive()) {
			try {
				soundModel = SoundModel.load(inside(dir, model.getAsString(), "sound model"));
			} catch (IOException | RuntimeException e) {
				LOG.warn("mcrl: RL cache sound model unreadable, the cache has no sound model: {}", e.toString());
			}
		}
		Map<String, SoundCue> soundCues = Map.of();
		JsonElement cues = object(m, "sounds").get("cues");
		if (cues != null && cues.isJsonPrimitive()) {
			try {
				soundCues = CacheJson.soundCues(jsonFile(dir, cues.getAsString()));
			} catch (IOException | RuntimeException e) {
				LOG.warn("mcrl: RL cache sound cues unreadable, so the FX actors play no sounds: {}", e.toString());
			}
		}
		String soundsMissing = m.has("soundsMissing") && m.get("soundsMissing").isJsonPrimitive()
				? m.get("soundsMissing").getAsString() : null;
		boolean predates = soundModel == null && object(m, "sounds").has("roles") && !object(m, "sounds").has("model");
		if (predates) {
			soundsMissing = PREDATES_SOUND_MODEL;
		} else if (soundModel == null && soundsMissing == null) {
			soundsMissing = "the RL cache has no sounds";
		}
		CacheWriter.SoundsMissingKind kind = null;
		if (m.has("soundsMissingKind") && m.get("soundsMissingKind").isJsonPrimitive()) {
			try {
				kind = CacheWriter.SoundsMissingKind.valueOf(m.get("soundsMissingKind").getAsString());
			} catch (IllegalArgumentException unknown) {
			}
		}
		RlCache cache = new RlCache(dir, fingerprint, Map.copyOf(meshes), Map.copyOf(meshMaterials), Map.copyOf(sockets),
				wheels, soundsMissing, kind, predates, soundModel, soundCues, readFx(dir, m), materials, Map.copyOf(hud),
				baseBody(m));
		cache.bodies.loadAll();
		LOG.info("mcrl: RL cache {} read in {} ms", dir, (System.nanoTime() - start) / 1_000_000);
		return cache;
	}

	private static Fx readFx(Path dir, JsonObject m) {
		Effects effects = Effects.EMPTY;
		Map<String, FxActorDef> actors = Map.of();
		Map<String, JsonObject> objects = Map.of();
		String file = string(m, "effects");
		if (file != null) {
			try {
				effects = Effects.fromJson(jsonFile(dir, file));
			} catch (IOException | RuntimeException e) {
				LOG.warn("mcrl: RL cache {} does not read, so there are no RL particle effects: {}", file, e.toString());
			}
		}
		file = string(m, "fxactors");
		if (file != null) {
			try {
				JsonObject o = jsonFile(dir, file);
				Map<String, FxActorDef> a = CacheJson.fxActors(object(o, "actors"));
				Map<String, JsonObject> obj = new LinkedHashMap<>();
				for (Map.Entry<String, JsonElement> e : object(o, "objects").entrySet()) {
					obj.put(e.getKey(), e.getValue().getAsJsonObject());
				}
				actors = a;
				objects = Collections.unmodifiableMap(obj);
			} catch (IOException | RuntimeException e) {
				LOG.warn("mcrl: RL cache {} does not read, so there are no RL FX actors: {}", file, e.toString());
			}
		}
		return new Fx(effects, actors, objects);
	}

	private static @Nullable BaseBody baseBody(JsonObject m) {
		JsonObject b = object(m, "body");
		String shell = string(b, "shellMaterial"), chassis = string(b, "chassisMaterial"), fx = string(b, "fxActor");
		if (shell == null || chassis == null || fx == null) {
			return null;
		}
		int preset = b.has("preset") && b.get("preset").isJsonPrimitive() ? b.get("preset").getAsInt() : 0;
		return new BaseBody(shell, chassis, fx, preset >= 0 && preset <= 6 ? preset : 0);
	}

	private static JsonObject jsonFile(Path dir, String file) throws IOException {
		JsonElement e = JsonParser.parseString(Files.readString(inside(dir, file, "FX file " + file), StandardCharsets.UTF_8));
		if (!e.isJsonObject()) {
			throw new IOException(file + " is not a JSON object");
		}
		return e.getAsJsonObject();
	}

	private static @Nullable String string(JsonObject m, String key) {
		JsonElement e = m.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	private static Mesh mesh(Path dir, String name, String file) throws IOException {
		MeshData d;
		try {
			d = Mcrm.read(inside(dir, file, "mesh " + name));
		} catch (IOException e) {
			throw new IOException("unreadable mesh " + name + ": " + e.getMessage(), e);
		}
		return new Mesh(d.vertices(), d.indices(), d.uv1());
	}

	private static Path inside(Path dir, String file, String what) throws IOException {
		Path p = dir.resolve(file).normalize();
		if (!p.startsWith(dir.normalize())) {
			throw new IOException(what + " lies outside the cache dir: " + file);
		}
		return p;
	}

	private static JsonObject object(JsonObject m, String key) {
		return m.has(key) && m.get(key).isJsonObject() ? m.getAsJsonObject(key) : new JsonObject();
	}
}
