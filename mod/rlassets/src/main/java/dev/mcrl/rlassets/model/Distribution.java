package dev.mcrl.rlassets.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;

public record Distribution(int op, int chunk, int numElements, float timeScale, float startTime, float[] table,
		@Nullable Param param) {
	public record Param(String name, String[] modes, float[] minInput, float[] maxInput, float[] minOutput,
			float[] maxOutput, float[] constant) {
		public float[] map(float @Nullable [] p) {
			if (p == null) {
				return constant.clone();
			}
			float[] out = new float[constant.length];
			for (int k = 0; k < out.length; k++) {
				float v = p[Math.min(k, p.length - 1)];
				String mode = modes[Math.min(k, modes.length - 1)];
				if (mode.equals("DPM_Direct")) {
					out[k] = v;
					continue;
				}
				if (mode.equals("DPM_Abs")) {
					v = Math.abs(v);
				}
				float lo = minInput[k], hi = maxInput[k];
				float gradient = hi <= lo ? 0 : (maxOutput[k] - minOutput[k]) / (hi - lo);
				float clamped = v < lo ? lo : v < hi ? v : hi;
				out[k] = minOutput[k] + (clamped - lo) * gradient;
			}
			return out;
		}

		public static Param fromJson(JsonObject o, int components) {
			String name = o.has("ParameterName") && !o.get("ParameterName").isJsonNull()
					? o.get("ParameterName").getAsString() : "None";
			String[] modes = new String[components];
			Arrays.fill(modes, "DPM_Normal");
			JsonElement m = o.has("ParamModes") ? o.get("ParamModes") : o.get("ParamMode");
			if (m != null && m.isJsonArray()) {
				JsonArray a = m.getAsJsonArray();
				for (int k = 0; k < components && k < a.size(); k++) {
					modes[k] = a.get(k).getAsString();
				}
			} else if (m != null && m.isJsonPrimitive()) {
				Arrays.fill(modes, m.getAsString());
			}
			return new Param(name, modes, vector(o.get("MinInput"), components), vector(o.get("MaxInput"), components),
					vector(o.get("MinOutput"), components), vector(o.get("MaxOutput"), components),
					vector(o.get("Constant"), components));
		}

		private static float[] vector(@Nullable JsonElement e, int components) {
			float[] v = new float[components];
			if (e == null || e.isJsonNull()) {
				return v;
			}
			if (e.isJsonPrimitive()) {
				Arrays.fill(v, e.getAsFloat());
			} else if (e.isJsonObject()) {
				String[] axes = {"X", "Y", "Z"};
				for (int k = 0; k < components && k < 3; k++) {
					JsonElement c = e.getAsJsonObject().get(axes[k]);
					v[k] = c == null || c.isJsonNull() ? 0 : c.getAsFloat();
				}
			} else if (e.isJsonArray()) {
				JsonArray a = e.getAsJsonArray();
				for (int k = 0; k < components && k < a.size(); k++) {
					v[k] = a.get(k).getAsFloat();
				}
			}
			return v;
		}
	}

	public int entries() {
		return chunk <= 0 || table.length < 2 ? 0 : (table.length - 2) / chunk;
	}

	public int values() {
		return op == 1 ? chunk : chunk / 2;
	}

	public float[] entry(int i) {
		return Arrays.copyOfRange(table, 2 + i * chunk, 2 + (i + 1) * chunk);
	}

	public float[] sample(float t, RandomGenerator rng) {
		int n = entries(), v = values();
		if (n == 0) {
			return param != null ? param.constant().clone() : new float[Math.max(v, 1)];
		}
		float x = timeScale == 0 ? 0 : Math.max(0, (t - startTime) * timeScale);
		int i = (int) Math.min(Math.floor(x), n - 1), j = Math.min(i + 1, n - 1);
		float f = x - i;
		float[] e = new float[chunk];
		for (int k = 0; k < chunk; k++) {
			float a = table[2 + i * chunk + k], b = table[2 + j * chunk + k];
			e[k] = a + (b - a) * f;
		}
		if (op == 1) {
			return e;
		}
		float[] out = new float[v];
		if (op == 3) {
			int from = rng.nextFloat() < 0.5f ? 0 : v;
			System.arraycopy(e, from, out, 0, v);
			return out;
		}
		for (int k = 0; k < v; k++) {
			out[k] = e[k] + (e[v + k] - e[k]) * rng.nextFloat();
		}
		return out;
	}

	public static Distribution fromJson(JsonObject json) {
		JsonObject o = json.has("$dist") ? json.getAsJsonObject("$dist") : json;
		int op = o.get("op").getAsInt(), chunk = o.get("chunk").getAsInt();
		JsonArray t = o.getAsJsonArray("table");
		float[] table = new float[t.size()];
		for (int i = 0; i < table.length; i++) {
			table[i] = t.get(i).isJsonNull() ? Float.NaN : t.get(i).getAsFloat();
		}
		JsonElement p = o.get("param");
		int components = Math.max(1, op == 1 ? chunk : chunk / 2);
		return new Distribution(op, chunk, o.get("numElements").getAsInt(), o.get("timeScale").getAsFloat(),
				o.get("startTime").getAsFloat(), table,
				p == null || !p.isJsonObject() ? null : Param.fromJson(p.getAsJsonObject(), components));
	}
}
