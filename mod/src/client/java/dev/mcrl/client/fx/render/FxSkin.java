package dev.mcrl.client.fx.render;

import net.minecraft.resources.Identifier;

public record FxSkin(Identifier texture, float u0, float v0, float u1, float v1, int rgb) {
	public float u(float t) {
		return u0 + (u1 - u0) * t;
	}

	public float v(float t) {
		return v0 + (v1 - v0) * t;
	}

	public float[] cell(int frame, int h, int v, float[] out) {
		float cu = (float) (frame % h) / h, cv = (float) (frame / h % v) / v;
		out[0] = u(cu);
		out[1] = v(cv);
		out[2] = u(cu + 1f / h);
		out[3] = v(cv + 1f / v);
		return out;
	}

	public int argb(float a, boolean additive) {
		if (!additive) {
			return FxColor.alpha(a) << 24 | rgb & 0xFFFFFF;
		}
		float s = a < 0 ? 0 : a > 1 ? 1 : a;
		int r = Math.round((rgb >> 16 & 0xFF) * s), g = Math.round((rgb >> 8 & 0xFF) * s), b = Math.round((rgb & 0xFF) * s);
		return 0xFF000000 | r << 16 | g << 8 | b;
	}
}
