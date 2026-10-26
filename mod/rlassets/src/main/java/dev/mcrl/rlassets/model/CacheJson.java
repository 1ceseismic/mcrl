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
import java.util.Set;
import org.jspecify.annotations.Nullable;

public final class CacheJson {
	private static final Set<String> ACTOR_KEYS = Set.of("path", "class", "attachments", "props");
	private static final Set<String> ATTACHMENT_KEYS = Set.of("name", "socket", "attachAny", "attachAll", "detachAny",
			"attachDelay", "detachDelay", "lifeTime", "target", "componentClass", "component", "props");

	private CacheJson() {
	}

	public static Map<String, FxActorDef> fxActors(JsonObject json) {
		Map<String, FxActorDef> out = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> e : json.entrySet()) {
			try {
				out.put(e.getKey(), actor(e.getKey(), e.getValue().getAsJsonObject()));
			} catch (RuntimeException ex) {
				throw new JsonParseException("FX actor " + e.getKey() + ": " + ex.getMessage(), ex);
			}
		}
		return Collections.unmodifiableMap(out);
	}

	public static JsonObject fxActors(Map<String, FxActorDef> actors) {
		JsonObject out = new JsonObject();
		for (Map.Entry<String, FxActorDef> e : actors.entrySet()) {
			FxActorDef d = e.getValue();
			JsonObject o = new JsonObject();
			o.addProperty("path", d.path());
			o.addProperty("class", d.className());
			JsonArray atts = new JsonArray();
			for (AttachmentDef a : d.attachments()) {
				atts.add(attachment(a));
			}
			o.add("attachments", atts);
			o.add("props", d.props().deepCopy());
			out.add(e.getKey(), o);
		}
		return out;
	}

	public static Map<String, SoundCue> soundCues(JsonObject json) {
		Map<String, SoundCue> out = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> e : json.entrySet()) {
			try {
				JsonObject o = e.getValue().getAsJsonObject();
				JsonElement start = opt(o, "start"), stop = opt(o, "stop");
				if (start == null) {
					throw new JsonParseException("no start");
				}
				out.put(e.getKey(), new SoundCue(start.getAsString(), stop == null ? null : stop.getAsString()));
			} catch (RuntimeException ex) {
				throw new JsonParseException("sound cue " + e.getKey() + ": " + ex.getMessage(), ex);
			}
		}
		return Collections.unmodifiableMap(out);
	}

	public static JsonObject soundCues(Map<String, SoundCue> cues) {
		JsonObject out = new JsonObject();
		for (Map.Entry<String, SoundCue> e : cues.entrySet()) {
			JsonObject o = new JsonObject();
			o.addProperty("start", e.getValue().start());
			if (e.getValue().stop() != null) {
				o.addProperty("stop", e.getValue().stop());
			}
			out.add(e.getKey(), o);
		}
		return out;
	}

	public static Materials materials(JsonObject json) {
		try {
			Map<String, MaterialDef> mats = new LinkedHashMap<>();
			for (Map.Entry<String, JsonElement> e : obj(json, "materials").entrySet()) {
				JsonObject o = e.getValue().getAsJsonObject();
				JsonElement d = opt(o, "defaults"), ts = opt(o, "twoSided");
				MaterialDef.Defaults defaults = d == null ? null : new MaterialDef.Defaults(floats(obj(d.getAsJsonObject(),
						"scalars")), vectors(obj(d.getAsJsonObject(), "vectors")), strings(obj(d.getAsJsonObject(), "textures")),
						bools(obj(d.getAsJsonObject(), "switches")));
				mats.put(e.getKey(), new MaterialDef(e.getKey(), str(o, "path"), str(o, "class"), nullable(o, "parent"),
						floats(obj(o, "scalars")), vectors(obj(o, "vectors")), strings(obj(o, "textures")),
						bools(obj(o, "switches")), nullable(o, "blendMode"), ts == null ? null : ts.getAsBoolean(),
						nullable(o, "lightingModel"), defaults, names(o, "shaderTextures")));
			}
			Map<String, TextureInfo> tex = new LinkedHashMap<>();
			for (Map.Entry<String, JsonElement> e : obj(json, "textures").entrySet()) {
				JsonObject o = e.getValue().getAsJsonObject();
				JsonElement srgb = opt(o, "srgb");
				tex.put(e.getKey(), new TextureInfo(e.getKey(), str(o, "pkg"), str(o, "file"), (int) num(o, "width"),
						(int) num(o, "height"), (int) num(o, "exportedWidth"), (int) num(o, "exportedHeight"), str(o, "format"),
						str(o, "compression"), srgb == null || srgb.getAsBoolean(), str(o, "lodGroup"), str(o, "addressX"),
						str(o, "addressY")));
			}
			return new Materials(mats, tex);
		} catch (RuntimeException ex) {
			throw ex instanceof JsonParseException j ? j : new JsonParseException("materials.json: " + ex, ex);
		}
	}

	public static JsonObject materials(Materials m) {
		JsonObject mats = new JsonObject();
		for (MaterialDef d : m.materials().values()) {
			JsonObject o = new JsonObject();
			o.addProperty("path", d.path());
			o.addProperty("class", d.className());
			o.addProperty("parent", d.parent());
			o.add("scalars", floats(d.scalars()));
			o.add("vectors", vectors(d.vectors()));
			o.add("textures", strings(d.textures()));
			o.add("switches", bools(d.switches()));
			o.addProperty("blendMode", d.blendMode());
			o.addProperty("twoSided", d.twoSided());
			o.addProperty("lightingModel", d.lightingModel());
			if (d.defaults() != null) {
				JsonObject df = new JsonObject();
				df.add("scalars", floats(d.defaults().scalars()));
				df.add("vectors", vectors(d.defaults().vectors()));
				df.add("textures", strings(d.defaults().textures()));
				df.add("switches", bools(d.defaults().switches()));
				o.add("defaults", df);
			}
			o.add("shaderTextures", names(d.shaderTextures()));
			mats.add(d.name(), o);
		}
		JsonObject tex = new JsonObject();
		for (TextureInfo t : m.textures().values()) {
			JsonObject o = new JsonObject();
			o.addProperty("pkg", t.pkg());
			o.addProperty("file", t.file());
			o.addProperty("width", t.width());
			o.addProperty("height", t.height());
			o.addProperty("exportedWidth", t.exportedWidth());
			o.addProperty("exportedHeight", t.exportedHeight());
			o.addProperty("format", t.format());
			o.addProperty("compression", t.compression());
			o.addProperty("srgb", t.srgb());
			o.addProperty("lodGroup", t.lodGroup());
			o.addProperty("addressX", t.addressX());
			o.addProperty("addressY", t.addressY());
			tex.add(t.name(), o);
		}
		JsonObject out = new JsonObject();
		out.add("materials", mats);
		out.add("textures", tex);
		return out;
	}

	private static JsonObject obj(JsonObject o, String key) {
		JsonElement e = opt(o, key);
		return e == null ? new JsonObject() : e.getAsJsonObject();
	}

	private static @Nullable String nullable(JsonObject o, String key) {
		JsonElement e = opt(o, key);
		return e == null ? null : e.getAsString();
	}

	private static Map<String, Float> floats(JsonObject o) {
		Map<String, Float> m = new LinkedHashMap<>();
		o.entrySet().forEach(e -> m.put(e.getKey(), e.getValue().getAsFloat()));
		return m;
	}

	private static Map<String, float[]> vectors(JsonObject o) {
		Map<String, float[]> m = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> e : o.entrySet()) {
			JsonArray a = e.getValue().getAsJsonArray();
			float[] v = new float[a.size()];
			for (int i = 0; i < v.length; i++) {
				v[i] = a.get(i).getAsFloat();
			}
			m.put(e.getKey(), v);
		}
		return m;
	}

	private static Map<String, String> strings(JsonObject o) {
		Map<String, String> m = new LinkedHashMap<>();
		o.entrySet().forEach(e -> m.put(e.getKey(), e.getValue().getAsString()));
		return m;
	}

	private static Map<String, Boolean> bools(JsonObject o) {
		Map<String, Boolean> m = new LinkedHashMap<>();
		o.entrySet().forEach(e -> m.put(e.getKey(), e.getValue().getAsBoolean()));
		return m;
	}

	private static JsonObject floats(Map<String, Float> m) {
		JsonObject o = new JsonObject();
		m.forEach(o::addProperty);
		return o;
	}

	private static JsonObject vectors(Map<String, float[]> m) {
		JsonObject o = new JsonObject();
		m.forEach((k, v) -> {
			JsonArray a = new JsonArray();
			for (float f : v) {
				a.add(f);
			}
			o.add(k, a);
		});
		return o;
	}

	private static JsonObject strings(Map<String, String> m) {
		JsonObject o = new JsonObject();
		m.forEach(o::addProperty);
		return o;
	}

	private static JsonObject bools(Map<String, Boolean> m) {
		JsonObject o = new JsonObject();
		m.forEach(o::addProperty);
		return o;
	}

	private static FxActorDef actor(String key, JsonObject o) {
		List<AttachmentDef> atts = new ArrayList<>();
		JsonElement list = opt(o, "attachments");
		if (list != null) {
			for (JsonElement a : list.getAsJsonArray()) {
				atts.add(attachment(a.getAsJsonObject()));
			}
		}
		return new FxActorDef(key, str(o, "path"), str(o, "class"), atts, props(o, ACTOR_KEYS));
	}

	private static AttachmentDef attachment(JsonObject o) {
		JsonElement socket = opt(o, "socket"), target = opt(o, "target"), component = opt(o, "component");
		return new AttachmentDef(str(o, "name"), socket == null ? null : socket.getAsString(), names(o, "attachAny"),
				names(o, "attachAll"), names(o, "detachAny"), num(o, "attachDelay"), num(o, "detachDelay"),
				num(o, "lifeTime"),
				target == null ? AttachmentDef.Target.ALL : AttachmentDef.Target.valueOf(target.getAsString()),
				str(o, "componentClass"), component == null ? new JsonObject() : component.getAsJsonObject(),
				props(o, ATTACHMENT_KEYS));
	}

	private static JsonObject attachment(AttachmentDef a) {
		JsonObject o = new JsonObject();
		o.addProperty("name", a.name());
		if (a.socket() != null) {
			o.addProperty("socket", a.socket());
		}
		o.add("attachAny", names(a.attachAny()));
		o.add("attachAll", names(a.attachAll()));
		o.add("detachAny", names(a.detachAny()));
		o.addProperty("attachDelay", a.attachDelay());
		o.addProperty("detachDelay", a.detachDelay());
		o.addProperty("lifeTime", a.lifeTime());
		o.addProperty("target", a.target().name());
		o.addProperty("componentClass", a.componentClass());
		o.add("component", a.component().deepCopy());
		o.add("props", a.props().deepCopy());
		return o;
	}

	private static JsonObject props(JsonObject o, Set<String> known) {
		JsonObject p = new JsonObject();
		JsonElement given = opt(o, "props");
		if (given != null) {
			for (Map.Entry<String, JsonElement> e : given.getAsJsonObject().entrySet()) {
				p.add(e.getKey(), e.getValue());
			}
		}
		for (Map.Entry<String, JsonElement> e : o.entrySet()) {
			if (!known.contains(e.getKey()) && !p.has(e.getKey())) {
				p.add(e.getKey(), e.getValue());
			}
		}
		return p;
	}

	private static List<String> names(JsonObject o, String key) {
		JsonElement a = opt(o, key);
		if (a == null) {
			return List.of();
		}
		List<String> out = new ArrayList<>();
		for (JsonElement e : a.getAsJsonArray()) {
			out.add(e.getAsString());
		}
		return out;
	}

	private static JsonArray names(List<String> names) {
		JsonArray a = new JsonArray();
		for (String n : names) {
			a.add(n);
		}
		return a;
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = opt(o, key);
		return e == null ? "" : e.getAsString();
	}

	private static float num(JsonObject o, String key) {
		JsonElement e = opt(o, key);
		return e == null ? 0f : e.getAsFloat();
	}

	private static @Nullable JsonElement opt(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? null : e;
	}
}
