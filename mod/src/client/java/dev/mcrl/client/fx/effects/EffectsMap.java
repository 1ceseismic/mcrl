package dev.mcrl.client.fx.effects;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class EffectsMap {
	private EffectsMap() {
	}

	public static @Nullable String system(JsonObject map, String name) {
		String path = parse(map).get(name);
		return path == null || path.isEmpty() ? null : path;
	}

	public static Map<String, String> parse(JsonObject map) {
		Map<String, String> out = new HashMap<>();
		JsonElement effects = map.get("Effects");
		if (effects == null || !effects.isJsonArray()) {
			return out;
		}
		for (JsonElement e : (JsonArray) effects) {
			if (!e.isJsonObject()) {
				continue;
			}
			JsonObject o = e.getAsJsonObject();
			JsonElement mat = o.get("PhysicalMaterial"), ps = o.get("Particle");
			if (mat == null || !mat.isJsonPrimitive()) {
				continue;
			}
			String name = mat.getAsString();
			out.put(name.substring(name.lastIndexOf('.') + 1), ps == null || ps.isJsonNull() ? "" : ps.getAsString());
		}
		return out;
	}
}
