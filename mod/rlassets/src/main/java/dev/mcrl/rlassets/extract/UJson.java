package dev.mcrl.rlassets.extract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.mcrl.rlassets.upk.UpkRef;
import dev.mcrl.rlassets.upk.UpkUnknown;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class UJson {
	private static final List<String> PARAM_VALUES = List.of("MinInput", "MaxInput", "MinOutput", "MaxOutput", "Constant");

	private UJson() {
	}

	public static JsonElement encode(@Nullable Object v) {
		if (v == null) {
			return JsonNull.INSTANCE;
		}
		if (v instanceof Float f) {
			return Float.isFinite(f) ? new JsonPrimitive(f) : JsonNull.INSTANCE;
		}
		if (v instanceof Double d) {
			return Double.isFinite(d) ? new JsonPrimitive(d) : JsonNull.INSTANCE;
		}
		if (v instanceof Number n) {
			return new JsonPrimitive(n);
		}
		if (v instanceof Boolean b) {
			return new JsonPrimitive(b);
		}
		if (v instanceof String s) {
			return new JsonPrimitive(s);
		}
		if (v instanceof UpkRef r) {
			return new JsonPrimitive(r.path());
		}
		if (v instanceof UpkUnknown u) {
			JsonObject o = new JsonObject();
			o.addProperty("$unknown", u.type());
			o.addProperty("hex", u.hex());
			return o;
		}
		if (v instanceof List<?> l) {
			JsonArray a = new JsonArray(l.size());
			for (Object x : l) {
				a.add(encode(x));
			}
			return a;
		}
		if (v instanceof Map<?, ?> m) {
			if (isRawDistribution(m)) {
				JsonObject o = new JsonObject();
				o.add("$dist", dist(m));
				return o;
			}
			JsonObject o = new JsonObject();
			for (Map.Entry<?, ?> e : m.entrySet()) {
				o.add(String.valueOf(e.getKey()), encode(e.getValue()));
			}
			return o;
		}
		throw new IllegalArgumentException("not a decoded value: " + v.getClass().getName());
	}

	public static JsonObject object(Map<?, ?> m) {
		JsonElement e = encode(m);
		if (!e.isJsonObject()) {
			throw new IllegalArgumentException("a raw distribution is not an object of its own");
		}
		return e.getAsJsonObject();
	}

	public static boolean isRawDistribution(Map<?, ?> m) {
		return m.containsKey("LookupTable") && m.containsKey("Op");
	}

	private static JsonObject dist(Map<?, ?> m) {
		JsonObject d = new JsonObject();
		d.addProperty("op", intOf(m.get("Op")));
		d.addProperty("chunk", intOf(m.get("LookupTableChunkSize")));
		d.addProperty("numElements", intOf(m.get("LookupTableNumElements")));
		d.add("timeScale", encode(floatOf(m.get("LookupTableTimeScale"))));
		d.add("startTime", encode(floatOf(m.get("LookupTableStartTime"))));
		d.add("table", m.get("LookupTable") instanceof List<?> l ? encode(l) : new JsonArray());
		d.add("param", m.get("Distribution") instanceof Map<?, ?> p ? param(p) : JsonNull.INSTANCE);
		return d;
	}

	private static JsonObject param(Map<?, ?> p) {
		JsonObject o = new JsonObject();
		if (p.get("$class") instanceof String c) {
			o.addProperty("class", c);
		}
		o.add("ParameterName", encode(p.get("ParameterName")));
		if (p.containsKey("ParamMode")) {
			o.add("ParamMode", encode(p.get("ParamMode")));
		}
		if (p.containsKey("ParamModes") || p.containsKey("ParamModes[1]") || p.containsKey("ParamModes[2]")) {
			JsonArray modes = new JsonArray();
			for (String k : new String[] {"ParamModes", "ParamModes[1]", "ParamModes[2]"}) {
				modes.add(p.get(k) instanceof String s ? s : "DPM_Normal");
			}
			o.add("ParamModes", modes);
		}
		for (String k : PARAM_VALUES) {
			if (p.containsKey(k)) {
				o.add(k, encode(p.get(k)));
			}
		}
		return o;
	}

	private static int intOf(@Nullable Object v) {
		return v instanceof Number n ? n.intValue() : 0;
	}

	private static float floatOf(@Nullable Object v) {
		return v instanceof Number n ? n.floatValue() : 0f;
	}
}
