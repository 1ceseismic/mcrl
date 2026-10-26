package dev.mcrl.rlassets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.List;
import java.util.Locale;

public record WheelRig(float[][] disc, float[][] pivot, double[] presetRestZ, double tz, double travelMin, double travelMax,
		double blendRate) {
	public static final List<String> ORDER = List.of("FR", "FL", "BR", "BL");
	public static final String FRAME = "disc, pivot: car-local MC blocks (x forward, y up, z right), body offset applied; "
			+ "presetRestZ, Tz, travel: UU";

	public WheelRig {
		if (disc.length != 4 || pivot.length != 4 || presetRestZ.length != 4) {
			throw new IllegalArgumentException("a wheel rig has 4 wheels");
		}
		for (int i = 0; i < 4; i++) {
			check(disc[i], "disc[" + i + "]");
			if (pivot[i] != null) {
				check(pivot[i], "pivot[" + i + "]");
			}
			finite(presetRestZ[i], "presetRestZ[" + i + "]");
		}
		finite(tz, "Tz");
		finite(travelMin, "travel min");
		finite(travelMax, "travel max");
		if (travelMin > travelMax) {
			throw new IllegalArgumentException("travel min " + travelMin + " > max " + travelMax);
		}
		if (!(blendRate > 0) || !Double.isFinite(blendRate)) {
			throw new IllegalArgumentException("blendRate " + blendRate + " is not a positive number");
		}
	}

	public static boolean front(int i) {
		return i < 2;
	}

	public static boolean right(int i) {
		return i % 2 == 0;
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("frame", FRAME);
		JsonArray order = new JsonArray();
		ORDER.forEach(order::add);
		o.add("order", order);
		JsonArray d = new JsonArray(), p = new JsonArray(), z = new JsonArray();
		for (int i = 0; i < 4; i++) {
			d.add(vec(disc[i]));
			p.add(pivot[i] == null ? JsonNull.INSTANCE : vec(pivot[i]));
			z.add(presetRestZ[i]);
		}
		o.add("disc", d);
		o.add("pivot", p);
		o.add("presetRestZ", z);
		o.addProperty("Tz", tz);
		JsonArray t = new JsonArray();
		t.add(travelMin);
		t.add(travelMax);
		o.add("travel", t);
		o.addProperty("blendRate", blendRate);
		return o;
	}

	public static WheelRig fromJson(JsonElement e) {
		if (e == null || !e.isJsonObject()) {
			throw new IllegalArgumentException("no wheels object");
		}
		JsonObject o = e.getAsJsonObject();
		try {
			JsonArray order = o.getAsJsonArray("order");
			if (order == null || order.size() != 4) {
				throw new IllegalArgumentException("wheels.order is not 4 names");
			}
			for (int i = 0; i < 4; i++) {
				if (!ORDER.get(i).equals(order.get(i).getAsString())) {
					throw new IllegalArgumentException("wheels.order is " + order + ", expected " + ORDER);
				}
			}
			JsonArray d = array(o, "disc"), p = array(o, "pivot"), z = array(o, "presetRestZ"), t = array(o, "travel");
			if (t.size() != 2) {
				throw new IllegalArgumentException("wheels.travel is not [min, max]");
			}
			float[][] disc = new float[4][], pivot = new float[4][];
			double[] rest = new double[4];
			for (int i = 0; i < 4; i++) {
				disc[i] = vec(d.get(i), "disc[" + i + "]");
				pivot[i] = p.get(i).isJsonNull() ? null : vec(p.get(i), "pivot[" + i + "]");
				rest[i] = z.get(i).getAsDouble();
			}
			if (pivot[0] == null || pivot[1] == null || pivot[2] != null || pivot[3] != null) {
				throw new IllegalArgumentException("wheels.pivot must be set for the front wheels only");
			}
			return new WheelRig(disc, pivot, rest, number(o, "Tz"), t.get(0).getAsDouble(), t.get(1).getAsDouble(),
					number(o, "blendRate"));
		} catch (IllegalArgumentException x) {
			throw x;
		} catch (RuntimeException x) {
			throw new IllegalArgumentException("bad wheels object: " + x, x);
		}
	}

	private static JsonArray array(JsonObject o, String key) {
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonArray()) {
			throw new IllegalArgumentException("wheels." + key + " missing");
		}
		JsonArray a = e.getAsJsonArray();
		if (!key.equals("travel") && a.size() != 4) {
			throw new IllegalArgumentException("wheels." + key + " has " + a.size() + " entries, expected 4");
		}
		return a;
	}

	private static double number(JsonObject o, String key) {
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive()) {
			throw new IllegalArgumentException("wheels." + key + " missing");
		}
		return e.getAsDouble();
	}

	private static float[] vec(JsonElement e, String what) {
		JsonArray a = e.getAsJsonArray();
		if (a.size() != 3) {
			throw new IllegalArgumentException("wheels." + what + " is not 3 numbers");
		}
		return new float[] {a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
	}

	private static JsonArray vec(float[] v) {
		JsonArray a = new JsonArray();
		for (float f : v) {
			a.add(new JsonPrimitive(f));
		}
		return a;
	}

	private static void check(float[] v, String what) {
		if (v == null || v.length != 3) {
			throw new IllegalArgumentException(what + " is not 3 numbers");
		}
		for (float f : v) {
			finite(f, what);
		}
	}

	private static void finite(double v, String what) {
		if (!Double.isFinite(v)) {
			throw new IllegalArgumentException(String.format(Locale.ROOT, "%s is not finite (%s)", what, v));
		}
	}
}
