package dev.mcrl.client.fx.particle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.random.RandomGenerator;

import org.jspecify.annotations.Nullable;

public final class FxParams {
	private enum Kind { SCALAR, SCALAR_RAND, VECTOR, VECTOR_RAND, COLOR }

	private record Entry(Kind kind, float[] lo, float[] hi) {
	}

	private final Map<String, Entry> entries = new LinkedHashMap<>();
	private final @Nullable FxParams over;

	public FxParams() {
		this(null);
	}

	public FxParams(@Nullable FxParams over) {
		this.over = over;
	}

	public void setScalar(String name, float v) {
		entries.put(name, new Entry(Kind.SCALAR, new float[] {v}, new float[] {v}));
	}

	public void setScalarRand(String name, float lo, float hi) {
		entries.put(name, new Entry(Kind.SCALAR_RAND, new float[] {lo}, new float[] {hi}));
	}

	public void setVector(String name, float x, float y, float z) {
		float[] v = {x, y, z};
		entries.put(name, new Entry(Kind.VECTOR, v, v));
	}

	public void setVectorRand(String name, float[] lo, float[] hi) {
		entries.put(name, new Entry(Kind.VECTOR_RAND, new float[] {lo[0], lo[1], lo[2]}, new float[] {hi[0], hi[1],
				hi[2]}));
	}

	public void setColor(String name, float r, float g, float b, float a) {
		float[] v = {r, g, b, a};
		entries.put(name, new Entry(Kind.COLOR, v, v));
	}

	public void setAll(FxParams other) {
		entries.putAll(other.entries);
	}

	public void remove(String name) {
		entries.remove(name);
	}

	public void clear() {
		entries.clear();
	}

	public boolean has(String name) {
		return entries.containsKey(name) || over != null && over.has(name);
	}

	public int size() {
		return entries.size();
	}

	public float @Nullable [] read(String name, RandomGenerator rng) {
		if (over != null && over.has(name)) {
			return over.read(name, rng);
		}
		Entry e = entries.get(name);
		if (e == null) {
			return null;
		}
		float[] out = new float[e.lo.length];
		fill(e, rng, out);
		return out;
	}

	public int read(String name, RandomGenerator rng, float[] out) {
		if (over != null && over.has(name)) {
			return over.read(name, rng, out);
		}
		Entry e = entries.get(name);
		if (e == null) {
			return 0;
		}
		return fill(e, rng, out);
	}

	public float readFirst(String name, RandomGenerator rng) {
		if (over != null && over.has(name)) {
			return over.readFirst(name, rng);
		}
		Entry e = entries.get(name);
		if (e == null) {
			return Float.NaN;
		}
		boolean rand = e.kind == Kind.SCALAR_RAND || e.kind == Kind.VECTOR_RAND;
		return rand ? e.lo[0] + (e.hi[0] - e.lo[0]) * rng.nextFloat() : e.lo[0];
	}

	private static int fill(Entry e, RandomGenerator rng, float[] out) {
		int n = Math.min(e.lo.length, out.length);
		boolean rand = e.kind == Kind.SCALAR_RAND || e.kind == Kind.VECTOR_RAND;
		for (int k = 0; k < n; k++) {
			out[k] = rand ? e.lo[k] + (e.hi[k] - e.lo[k]) * rng.nextFloat() : e.lo[k];
		}
		return n;
	}
}
