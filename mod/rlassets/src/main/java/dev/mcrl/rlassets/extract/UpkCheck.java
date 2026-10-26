package dev.mcrl.rlassets.extract;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcrl.rlassets.upk.UpkReader;
import dev.mcrl.rlassets.upk.UpkWorld;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class UpkCheck {
	public static final List<String> PACKAGES = List.of("Startup", "TAGame", "GameInfo_Soccar_SF", "Boost_Standard_SF",
			"ss_default_SF", "Body_Octane_SF", "Explosion_Default_SF");
	public static final List<String> ANOMALIES = List.of("stride", "numel", "chunk", "const_n", "rate20", "range");
	private static final int EXAMPLES = 10;

	private int systems, modules, tables, consts, curves, empty;
	private final Map<String, Integer> anomalies = new LinkedHashMap<>();
	private final List<String> examples = new ArrayList<>();

	private UpkCheck() {
		ANOMALIES.forEach(k -> anomalies.put(k, 0));
	}

	public static JsonObject run(UpkWorld w) {
		UpkCheck c = new UpkCheck();
		JsonArray found = new JsonArray(), missing = new JsonArray();
		for (String pk : PACKAGES) {
			UpkReader p = w.pkg(pk);
			if (p == null) {
				missing.add(pk);
				continue;
			}
			found.add(pk);
			for (int i = 1; i <= p.exports().size(); i++) {
				UpkReader.Export e = p.exports().get(i - 1);
				if (e.serialSize() == 0) {
					continue;
				}
				String cls = p.className(e.classIndex());
				if (cls.equals("ParticleSystem")) {
					c.systems++;
				}
				if (cls.startsWith("ParticleModule") || cls.equals("LensFlare")) {
					c.modules++;
					c.walk(w.obj(pk, i).props(), pk + ":" + p.objectPath(i));
				}
			}
		}
		JsonObject o = new JsonObject();
		o.add("packages", found);
		o.add("missing", missing);
		o.addProperty("systems", c.systems);
		o.addProperty("modules", c.modules);
		o.addProperty("tables", c.tables);
		o.addProperty("const", c.consts);
		o.addProperty("curves", c.curves);
		o.addProperty("empty", c.empty);
		JsonObject a = new JsonObject();
		c.anomalies.forEach(a::addProperty);
		o.add("anomalies", a);
		JsonArray ex = new JsonArray();
		c.examples.forEach(ex::add);
		o.add("anomalyExamples", ex);
		o.addProperty("warnings", w.warnings().size());
		JsonArray wx = new JsonArray();
		w.warnings().stream().limit(EXAMPLES).forEach(wx::add);
		o.add("warningExamples", wx);
		return o;
	}

	private void walk(Object x, String where) {
		if (x instanceof Map<?, ?> m) {
			if (UJson.isRawDistribution(m)) {
				table(m, where);
				return;
			}
			for (Map.Entry<?, ?> e : m.entrySet()) {
				walk(e.getValue(), where + "." + e.getKey());
			}
		} else if (x instanceof List<?> l) {
			for (int i = 0; i < l.size(); i++) {
				walk(l.get(i), where + "[" + i + "]");
			}
		}
	}

	private void table(Map<?, ?> m, String where) {
		List<?> table = m.get("LookupTable") instanceof List<?> l ? l : List.of();
		if (table.isEmpty()) {
			empty++;
			return;
		}
		tables++;
		int chunk = num(m.get("LookupTableChunkSize")).intValue(), op = num(m.get("Op")).intValue();
		int numElements = num(m.get("LookupTableNumElements")).intValue();
		double timeScale = num(m.get("LookupTableTimeScale")).doubleValue();
		if (chunk == 0 || (table.size() - 2) % chunk != 0) {
			anomaly("stride", where, "table " + table.size() + ", chunk " + chunk);
			return;
		}
		int n = (table.size() - 2) / chunk;
		if (numElements != (op == 1 ? 1 : 2)) {
			anomaly("numel", where, "op " + op + ", numElements " + numElements);
		}
		if (numElements == 0 || chunk % numElements != 0) {
			anomaly("chunk", where, "chunk " + chunk + ", numElements " + numElements);
		}
		if (timeScale == 0) {
			consts++;
			if (n != 2) {
				anomaly("const_n", where, n + " entries");
			}
			return;
		}
		curves++;
		double range = (n - 1) / timeScale;
		if ((int) (20 * range + 1e-3) + 1 != n) {
			anomaly("rate20", where, n + " entries over " + range + " s");
		}
		double lo = num(table.get(0)).doubleValue(), hi = num(table.get(1)).doubleValue();
		for (Object v : table.subList(2, table.size())) {
			double d = num(v).doubleValue();
			if (d < lo - 1e-4 || d > hi + 1e-4) {
				anomaly("range", where, d + " outside [" + lo + ", " + hi + "]");
				break;
			}
		}
	}

	private void anomaly(String kind, String where, String what) {
		anomalies.merge(kind, 1, Integer::sum);
		if (!kind.equals("rate20") && examples.size() < EXAMPLES) {
			examples.add(kind + " " + where + ": " + what);
		}
	}

	private static Number num(Object v) {
		return v instanceof Number n ? n : Float.NaN;
	}
}
