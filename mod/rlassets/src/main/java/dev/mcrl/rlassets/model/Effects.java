package dev.mcrl.rlassets.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public record Effects(Map<String, ParticleSystemDef> systems, Map<String, String> meshes) {
	public static final Effects EMPTY = new Effects(Map.of(), Map.of());

	public Effects {
		systems = Collections.unmodifiableMap(new LinkedHashMap<>(systems));
		meshes = Collections.unmodifiableMap(new LinkedHashMap<>(meshes));
	}

	public record ParticleSystemDef(String path, float[] lodDistances, JsonObject props, List<EmitterDef> emitters) {
		public ParticleSystemDef {
			emitters = List.copyOf(emitters);
		}
	}

	public record EmitterDef(String name, String className, boolean cookedOut, JsonObject props, List<ModuleDef> modules) {
		public EmitterDef {
			modules = List.copyOf(modules);
		}
	}

	public record ModuleDef(String className, boolean enabled, JsonObject props) {
		public @Nullable Distribution dist(String key) {
			JsonElement e = props;
			for (String part : key.split("\\.")) {
				if (e == null || !e.isJsonObject()) {
					return null;
				}
				e = e.getAsJsonObject().get(part);
			}
			return e != null && e.isJsonObject() && e.getAsJsonObject().has("$dist")
					? Distribution.fromJson(e.getAsJsonObject()) : null;
		}
	}

	public static Effects fromJson(JsonObject json) {
		try {
			Map<String, ParticleSystemDef> systems = new LinkedHashMap<>();
			for (Map.Entry<String, JsonElement> e : object(json, "systems").entrySet()) {
				JsonObject s = e.getValue().getAsJsonObject();
				JsonArray lod = s.has("lodDistances") ? s.getAsJsonArray("lodDistances") : new JsonArray();
				float[] lodDistances = new float[lod.size()];
				for (int i = 0; i < lodDistances.length; i++) {
					lodDistances[i] = lod.get(i).getAsFloat();
				}
				List<EmitterDef> emitters = new ArrayList<>();
				for (JsonElement em : array(s, "emitters")) {
					JsonObject o = em.getAsJsonObject();
					List<ModuleDef> modules = new ArrayList<>();
					for (JsonElement m : array(o, "modules")) {
						JsonObject mo = m.getAsJsonObject();
						modules.add(new ModuleDef(str(mo, "class"), bool(mo, "enabled"), object(mo, "props")));
					}
					emitters.add(new EmitterDef(str(o, "name"), str(o, "class"), bool(o, "cookedOut"), object(o, "props"),
							modules));
				}
				systems.put(e.getKey(), new ParticleSystemDef(e.getKey(), lodDistances, object(s, "props"), emitters));
			}
			Map<String, String> meshes = new LinkedHashMap<>();
			for (Map.Entry<String, JsonElement> e : object(json, "meshes").entrySet()) {
				meshes.put(e.getKey(), e.getValue().getAsString());
			}
			return new Effects(systems, meshes);
		} catch (RuntimeException ex) {
			throw ex instanceof JsonParseException j ? j : new JsonParseException("effects.json: " + ex, ex);
		}
	}

	private static JsonObject object(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? new JsonObject() : e.getAsJsonObject();
	}

	private static JsonArray array(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? new JsonArray() : e.getAsJsonArray();
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? "" : e.getAsString();
	}

	private static boolean bool(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && !e.isJsonNull() && e.getAsBoolean();
	}
}
