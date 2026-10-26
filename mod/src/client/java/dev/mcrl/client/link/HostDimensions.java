package dev.mcrl.client.link;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

final class HostDimensions {
	private final Map<Integer, String> dims = new HashMap<>();

	boolean accepts(int hostId, @Nullable String dim) {
		if (dim == null) {
			return false;
		}
		String d = dims.putIfAbsent(hostId, dim);
		return d == null || d.equals(dim);
	}

	void clear() {
		dims.clear();
	}
}
