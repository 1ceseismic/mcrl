package dev.mcrl.client.hud;

import static dev.mcrl.client.hud.MeterStyle.BG_ALPHA;
import static dev.mcrl.client.hud.MeterStyle.BG_FULL_TO;
import static dev.mcrl.client.hud.MeterStyle.BG_RGB;
import static dev.mcrl.client.hud.MeterStyle.BG_TINT_BAND_ALPHA;
import static dev.mcrl.client.hud.MeterStyle.BG_ZERO_AT;
import static dev.mcrl.client.hud.MeterStyle.DOT_EDGE;
import static dev.mcrl.client.hud.MeterStyle.DOT_RGB;
import static dev.mcrl.client.hud.MeterStyle.DOT_SIZE;
import static dev.mcrl.client.hud.MeterStyle.DOT_SOLID_R;
import static dev.mcrl.client.hud.MeterStyle.FADE_H;
import static dev.mcrl.client.hud.MeterStyle.FADE_W;
import static dev.mcrl.client.hud.MeterStyle.FILL_DEG_FROM;
import static dev.mcrl.client.hud.MeterStyle.FILL_DEG_TO;
import static dev.mcrl.client.hud.MeterStyle.FILL_RGB_FROM;
import static dev.mcrl.client.hud.MeterStyle.FILL_RGB_TO;
import static dev.mcrl.client.hud.MeterStyle.FILL_SECTOR_ALPHA;
import static dev.mcrl.client.hud.MeterStyle.GLOW_RADIUS;
import static dev.mcrl.client.hud.MeterStyle.GLOW_SIZE;
import static dev.mcrl.client.hud.MeterStyle.GLOW_TEXELS;
import static dev.mcrl.client.hud.MeterStyle.RING_INNER;
import static dev.mcrl.client.hud.MeterStyle.RING_OUTER;
import static dev.mcrl.client.hud.MeterStyle.RING_SIZE;
import static dev.mcrl.client.hud.MeterStyle.RING_TEXELS;
import static dev.mcrl.client.hud.MeterStyle.TINT_ALPHA;
import static dev.mcrl.client.hud.MeterStyle.TINT_DEG_FROM;
import static dev.mcrl.client.hud.MeterStyle.TINT_DEG_TO;
import static dev.mcrl.client.hud.MeterStyle.TINT_RGB;

import dev.mcrl.client.hud.MeterTextures.Layer;

final class ProceduralMeter {
	private ProceduralMeter() {
	}

	private static int fill(double x, double y) {
		double r = Math.hypot(x, y), th = theta(x, y);
		if (r < RING_INNER || r > RING_OUTER || th >= 180) {
			return 0;
		}
		double t = Math.max(0, Math.min(1, (th - FILL_DEG_FROM) / (FILL_DEG_TO - FILL_DEG_FROM)));
		return argb(FILL_SECTOR_ALPHA[(int) (th / 15)], lerpRgb(FILL_RGB_FROM, FILL_RGB_TO, t));
	}

	private static int tint(double x, double y) {
		double r = Math.hypot(x, y), th = theta(x, y);
		return r < RING_INNER || r > RING_OUTER || th < TINT_DEG_FROM || th > TINT_DEG_TO ? 0 : argb(TINT_ALPHA, TINT_RGB);
	}

	private static int background(double x, double y) {
		double r = Math.hypot(x, y), th = theta(x, y);
		double a = r <= BG_FULL_TO ? BG_ALPHA : r >= BG_ZERO_AT ? 0 : BG_ALPHA * (BG_ZERO_AT - r) / (BG_ZERO_AT - BG_FULL_TO);
		if (r >= RING_INNER && r <= RING_OUTER && th >= TINT_DEG_FROM && th <= TINT_DEG_TO) {
			a = Math.max(a, BG_TINT_BAND_ALPHA);
		}
		return argb(a, BG_RGB);
	}

	private static int glow(double x, double y) {
		return argb(Math.max(0, 1 - Math.hypot(x, y) / GLOW_RADIUS), 0);
	}

	static int[] image(Layer l) {
		int[] n = size(l);
		double size = l == Layer.GLOW ? GLOW_SIZE : l == Layer.DOT ? DOT_SIZE : RING_SIZE;
		int[] out = new int[n[0] * n[1]];
		if (l == Layer.FADE) {
			return out;
		}
		for (int j = 0; j < n[1]; j++) {
			double y = ((j + 0.5) / n[1] - 0.5) * size;
			for (int i = 0; i < n[0]; i++) {
				double x = ((i + 0.5) / n[0] - 0.5) * size;
				out[j * n[0] + i] = switch (l) {
					case BACKGROUND -> background(x, y);
					case GLOW -> glow(x, y);
					case TINT -> tint(x, y);
					case FILL -> fill(x, y);
					case DOT -> dot(x, y);
					case FADE -> 0;
				};
			}
		}
		return out;
	}

	static int[] size(Layer l) {
		return switch (l) {
			case GLOW -> new int[] { GLOW_TEXELS, GLOW_TEXELS };
			case DOT -> new int[] { (int) DOT_SIZE, (int) DOT_SIZE };
			case FADE -> new int[] { (int) FADE_W, (int) FADE_H };
			default -> new int[] { RING_TEXELS, RING_TEXELS };
		};
	}

	private static int dot(double x, double y) {
		return argb(Math.max(0, Math.min(1, 1 - (Math.hypot(x, y) - DOT_SOLID_R) / DOT_EDGE)), DOT_RGB);
	}

	private static double theta(double x, double y) {
		double d = Math.toDegrees(Math.atan2(-x, y));
		return d < 0 ? d + 360 : d;
	}

	private static int argb(double alpha, int rgb) {
		return (int) Math.floor(alpha * 255 + 0.5) << 24 | rgb;
	}

	private static int lerpRgb(int a, int b, double t) {
		int r = (int) Math.floor((a >> 16 & 0xFF) + ((b >> 16 & 0xFF) - (a >> 16 & 0xFF)) * t + 0.5);
		int g = (int) Math.floor((a >> 8 & 0xFF) + ((b >> 8 & 0xFF) - (a >> 8 & 0xFF)) * t + 0.5);
		int bl = (int) Math.floor((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t + 0.5);
		return r << 16 | g << 8 | bl;
	}
}
