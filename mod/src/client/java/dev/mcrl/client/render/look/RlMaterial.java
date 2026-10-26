package dev.mcrl.client.render.look;

import java.util.Map;

import org.jspecify.annotations.Nullable;

public record RlMaterial(String name, Map<String, Float> scalars, Map<String, float[]> vectors,
		Map<String, String> textures, Map<String, Boolean> switches) {
	public float scalar(String param, float fallback) {
		Float v = scalars.get(param);
		return v != null ? v : fallback;
	}

	public float[] vector(String param, float[] fallback) {
		float[] v = vectors.get(param);
		return v != null ? v : fallback;
	}

	public @Nullable String texture(String param) {
		return textures.get(param);
	}
}
