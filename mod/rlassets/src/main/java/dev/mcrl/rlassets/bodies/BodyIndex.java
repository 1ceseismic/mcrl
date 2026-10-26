package dev.mcrl.rlassets.bodies;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public record BodyIndex(String fingerprint, List<Entry> bodies) {
	public static final int INDEX_VERSION = 2;

	public record Entry(String id, String label, String pkg, int preset, @Nullable String handlingPreset,
			boolean supported, @Nullable String why) {
	}

	public BodyIndex {
		bodies = List.copyOf(bodies);
	}

	public @Nullable Entry get(String id) {
		for (Entry e : bodies) {
			if (e.id().equals(id)) {
				return e;
			}
		}
		return null;
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("indexVersion", INDEX_VERSION);
		o.addProperty("fingerprint", fingerprint);
		JsonArray a = new JsonArray();
		for (Entry e : bodies) {
			JsonObject j = new JsonObject();
			j.addProperty("id", e.id());
			j.addProperty("label", e.label());
			j.addProperty("pkg", e.pkg());
			j.addProperty("preset", e.preset());
			if (e.handlingPreset() != null) {
				j.addProperty("handlingPreset", e.handlingPreset());
			}
			j.addProperty("supported", e.supported());
			if (e.why() != null) {
				j.addProperty("why", e.why());
			}
			a.add(j);
		}
		o.add("bodies", a);
		return o;
	}

	public static BodyIndex fromJson(JsonElement e) {
		if (e == null || !e.isJsonObject()) {
			throw new IllegalArgumentException("a body index is not a JSON object");
		}
		JsonObject o = e.getAsJsonObject();
		try {
			int version = o.has("indexVersion") ? o.get("indexVersion").getAsInt() : -1;
			if (version != INDEX_VERSION) {
				throw new IllegalArgumentException("index version " + version + ", this mcrl reads " + INDEX_VERSION);
			}
			List<Entry> bodies = new ArrayList<>();
			for (JsonElement b : o.getAsJsonArray("bodies")) {
				JsonObject j = b.getAsJsonObject();
				String id = j.get("id").getAsString();
				if (!BodyIds.valid(id)) {
					throw new IllegalArgumentException("not a body id: " + id);
				}
				int preset = j.get("preset").getAsInt();
				if (preset < 0 || preset > 6) {
					throw new IllegalArgumentException(id + ": preset " + preset + " (0..6)");
				}
				bodies.add(new Entry(id, j.get("label").getAsString(), j.get("pkg").getAsString(), preset,
						optional(j, "handlingPreset"), j.get("supported").getAsBoolean(), optional(j, "why")));
			}
			return new BodyIndex(o.get("fingerprint").getAsString(), bodies);
		} catch (IllegalArgumentException x) {
			throw x;
		} catch (RuntimeException x) {
			throw new IllegalArgumentException("bad body index: " + x, x);
		}
	}

	private static @Nullable String optional(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}
}
