package dev.mcrl.rlassets.bodies;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.mcrl.rlassets.WheelRig;

public record BodyManifest(String id, String label, int preset, String fingerprint, Map<String, String> meshes,
		String materials, String shellMaterial, String chassisMaterial, @Nullable String fxActor,
		@Nullable String fxActors, @Nullable String effects, Map<String, float[]> sockets, WheelRig wheels,
		JsonObject report) {
	public static final int BODY_VERSION = 1;
	public static final List<String> MESHES;

	static {
		List<String> m = new ArrayList<>(List.of("body_shell", "chassis"));
		for (String part : List.of("rim", "tyre")) {
			for (String w : List.of("front_right", "front_left", "back_right", "back_left")) {
				m.add("wheel_" + part + "_" + w);
			}
		}
		MESHES = List.copyOf(m);
	}

	public BodyManifest {
		meshes = Collections.unmodifiableMap(new LinkedHashMap<>(meshes));
		Map<String, float[]> s = new LinkedHashMap<>();
		sockets.forEach((k, v) -> s.put(k, v.clone()));
		sockets = Collections.unmodifiableMap(s);
		report = report.deepCopy();
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("bodyVersion", BODY_VERSION);
		o.addProperty("id", id);
		o.addProperty("label", label);
		o.addProperty("preset", preset);
		o.addProperty("fingerprint", fingerprint);
		JsonObject m = new JsonObject();
		meshes.forEach(m::addProperty);
		o.add("meshes", m);
		o.addProperty("materials", materials);
		o.addProperty("shellMaterial", shellMaterial);
		o.addProperty("chassisMaterial", chassisMaterial);
		if (fxActor != null) {
			o.addProperty("fxActor", fxActor);
		}
		if (fxActors != null) {
			o.addProperty("fxActors", fxActors);
		}
		if (effects != null) {
			o.addProperty("effects", effects);
		}
		JsonObject so = new JsonObject();
		sockets.forEach((k, v) -> {
			JsonArray a = new JsonArray();
			for (float f : v) {
				a.add(f);
			}
			so.add(k, a);
		});
		o.add("sockets", so);
		o.add("wheels", wheels.toJson());
		o.add("report", report.deepCopy());
		return o;
	}

	public static BodyManifest fromJson(JsonElement e) {
		if (e == null || !e.isJsonObject()) {
			throw new IllegalArgumentException("a body manifest is not a JSON object");
		}
		JsonObject o = e.getAsJsonObject();
		try {
			int version = o.has("bodyVersion") ? o.get("bodyVersion").getAsInt() : -1;
			if (version != BODY_VERSION) {
				throw new IllegalArgumentException("body version " + version + ", this mcrl reads " + BODY_VERSION);
			}
			String id = string(o, "id");
			if (!BodyIds.valid(id)) {
				throw new IllegalArgumentException("not a body id: " + id);
			}
			int preset = o.get("preset").getAsInt();
			if (preset < 0 || preset > 6) {
				throw new IllegalArgumentException("preset " + preset + " (0..6)");
			}
			Map<String, String> meshes = new LinkedHashMap<>();
			JsonObject m = o.has("meshes") && o.get("meshes").isJsonObject() ? o.getAsJsonObject("meshes") : new JsonObject();
			for (Map.Entry<String, JsonElement> me : m.entrySet()) {
				meshes.put(me.getKey(), inside(me.getValue().getAsString(), "mesh " + me.getKey()));
			}
			for (String name : MESHES) {
				if (!meshes.containsKey(name)) {
					throw new IllegalArgumentException("no mesh " + name);
				}
			}
			Map<String, float[]> sockets = new LinkedHashMap<>();
			if (o.has("sockets") && o.get("sockets").isJsonObject()) {
				for (Map.Entry<String, JsonElement> se : o.getAsJsonObject("sockets").entrySet()) {
					JsonArray a = se.getValue().getAsJsonArray();
					if (a.size() != 3) {
						throw new IllegalArgumentException("socket " + se.getKey() + " is not 3 numbers");
					}
					sockets.put(se.getKey(), new float[] {a.get(0).getAsFloat(), a.get(1).getAsFloat(),
						a.get(2).getAsFloat()});
				}
			}
			String fxActors = optional(o, "fxActors"), effects = optional(o, "effects");
			return new BodyManifest(id, string(o, "label"), preset, string(o, "fingerprint"), meshes,
					inside(string(o, "materials"), "materials"), string(o, "shellMaterial"),
					string(o, "chassisMaterial"), optional(o, "fxActor"),
					fxActors == null ? null : inside(fxActors, "fxActors"),
					effects == null ? null : inside(effects, "effects"), sockets, WheelRig.fromJson(o.get("wheels")),
					o.has("report") && o.get("report").isJsonObject() ? o.getAsJsonObject("report") : new JsonObject());
		} catch (IllegalArgumentException x) {
			throw x;
		} catch (RuntimeException x) {
			throw new IllegalArgumentException("bad body manifest: " + x, x);
		}
	}

	private static String inside(String file, String what) {
		Path p;
		try {
			p = Path.of(file);
		} catch (InvalidPathException x) {
			throw new IllegalArgumentException(what + ": not a path: " + file, x);
		}
		Path n = p.normalize();
		if (file.isEmpty() || p.isAbsolute() || n.toString().isEmpty() || n.startsWith("..")) {
			throw new IllegalArgumentException(what + " leaves the body dir: " + file);
		}
		return file;
	}

	private static String string(JsonObject o, String key) {
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive()) {
			throw new IllegalArgumentException("no " + key);
		}
		return e.getAsString();
	}

	private static @Nullable String optional(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}

	@Override
	public boolean equals(Object other) {
		if (!(other instanceof BodyManifest b)) {
			return false;
		}
		if (sockets.size() != b.sockets.size()) {
			return false;
		}
		for (Map.Entry<String, float[]> s : sockets.entrySet()) {
			if (!Arrays.equals(s.getValue(), b.sockets.get(s.getKey()))) {
				return false;
			}
		}
		return id.equals(b.id) && label.equals(b.label) && preset == b.preset && fingerprint.equals(b.fingerprint)
				&& meshes.equals(b.meshes) && materials.equals(b.materials) && shellMaterial.equals(b.shellMaterial)
				&& chassisMaterial.equals(b.chassisMaterial) && Objects.equals(fxActor, b.fxActor)
				&& Objects.equals(fxActors, b.fxActors) && Objects.equals(effects, b.effects)
				&& wheels.toJson().equals(b.wheels.toJson()) && report.equals(b.report);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, preset, fingerprint, meshes);
	}
}
