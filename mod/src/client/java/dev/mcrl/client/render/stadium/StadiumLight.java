package dev.mcrl.client.render.stadium;

import java.util.List;

public record StadiumLight(List<Key> key, float[] skyColor, float skyBrightness) {
	private static final int MAX_KEYS = 3;
	public static final StadiumLight FALLBACK = new StadiumLight(
			List.of(new Key(normalised(-0.3f, -0.8f, 0.5f), new float[] {1, 1, 1}, 1f)), new float[] {0.35f, 0.37f, 0.45f},
			1f);

	public record Key(float[] dir, float[] color, float brightness) {
	}

	public StadiumLight {
		key = List.copyOf(key.subList(0, Math.min(key.size(), MAX_KEYS)));
	}

	static float[] normalised(float x, float y, float z) {
		float n = (float) Math.sqrt(x * x + y * y + z * z);
		return n == 0 ? new float[] {0, -1, 0} : new float[] {x / n, y / n, z / n};
	}
}
