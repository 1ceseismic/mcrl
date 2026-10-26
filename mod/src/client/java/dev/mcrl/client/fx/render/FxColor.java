package dev.mcrl.client.fx.render;

import dev.mcrl.client.render.look.ArenaLight;
import dev.mcrl.client.render.look.RlDisplay;

public final class FxColor {
	private FxColor() {
	}

	public static int argb(float r, float g, float b, float a) {
		return byteOf(clamp01(a)) << 24 | channel(r) << 16 | channel(g) << 8 | channel(b);
	}

	public static int additive(float r, float g, float b, float a) {
		return argb(r * a, g * a, b * a, 1);
	}

	public static int alpha(float a) {
		return byteOf(clamp01(a));
	}

	private static int channel(float linear) {
		return byteOf(RlDisplay.srgbEncode(clamp01((double) linear * ArenaLight.EXPOSURE)));
	}

	private static int byteOf(double v) {
		return (int) Math.floor(v * 255 + 0.5);
	}

	private static float clamp01(float v) {
		return v < 0 ? 0 : v > 1 ? 1 : v;
	}

	private static double clamp01(double v) {
		return v < 0 ? 0 : v > 1 ? 1 : v;
	}
}
