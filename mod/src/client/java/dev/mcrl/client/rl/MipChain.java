package dev.mcrl.client.rl;

import java.util.ArrayList;
import java.util.List;

public final class MipChain {
	public enum Kind {
		COLOR_SRGB,
		DATA,
		NORMAL
	}

	private static final double[] LINEAR = new double[256];

	static {
		for (int i = 0; i < 256; i++) {
			double c = i / 255.0;
			LINEAR[i] = c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
		}
	}

	private final List<int[]> levels;
	private final int[] widths, heights;

	private MipChain(List<int[]> levels, int[] widths, int[] heights) {
		this.levels = levels;
		this.widths = widths;
		this.heights = heights;
	}

	public static MipChain build(int w, int h, int[] argb, Kind kind) {
		if (w <= 0 || h <= 0 || argb.length != w * h) {
			throw new IllegalArgumentException("bad texels " + w + "x" + h + " / " + argb.length);
		}
		int count = 32 - Integer.numberOfLeadingZeros(Math.max(w, h));
		List<int[]> levels = new ArrayList<>(count);
		int[] widths = new int[count], heights = new int[count];
		levels.add(argb);
		widths[0] = w;
		heights[0] = h;
		for (int k = 1; k < count; k++) {
			int pw = widths[k - 1], ph = heights[k - 1];
			int nw = Math.max(1, pw >> 1), nh = Math.max(1, ph >> 1);
			int[] prev = levels.get(k - 1), next = new int[nw * nh];
			for (int y = 0; y < nh; y++) {
				int r0 = Math.min(2 * y, ph - 1) * pw, r1 = Math.min(2 * y + 1, ph - 1) * pw;
				for (int x = 0; x < nw; x++) {
					int c0 = Math.min(2 * x, pw - 1), c1 = Math.min(2 * x + 1, pw - 1);
					next[y * nw + x] = mean(kind, prev[r0 + c0], prev[r0 + c1], prev[r1 + c0], prev[r1 + c1]);
				}
			}
			levels.add(next);
			widths[k] = nw;
			heights[k] = nh;
		}
		return new MipChain(List.copyOf(levels), widths, heights);
	}

	public List<int[]> levels() {
		return levels;
	}

	public int width(int level) {
		return widths[level];
	}

	public int height(int level) {
		return heights[level];
	}

	private static int mean(Kind kind, int a, int b, int c, int d) {
		int alpha = mean8(a >>> 24, b >>> 24, c >>> 24, d >>> 24);
		return switch (kind) {
			case DATA -> alpha << 24 | mean8(a >> 16 & 0xFF, b >> 16 & 0xFF, c >> 16 & 0xFF, d >> 16 & 0xFF) << 16
					| mean8(a >> 8 & 0xFF, b >> 8 & 0xFF, c >> 8 & 0xFF, d >> 8 & 0xFF) << 8
					| mean8(a & 0xFF, b & 0xFF, c & 0xFF, d & 0xFF);
			case COLOR_SRGB -> alpha << 24 | srgbMean(a, b, c, d, 16) << 16 | srgbMean(a, b, c, d, 8) << 8
					| srgbMean(a, b, c, d, 0);
			case NORMAL -> alpha << 24 | normalMean(a, b, c, d);
		};
	}

	private static int mean8(int a, int b, int c, int d) {
		return (a + b + c + d + 2) >> 2;
	}

	private static int srgbMean(int a, int b, int c, int d, int shift) {
		double l = (LINEAR[a >> shift & 0xFF] + LINEAR[b >> shift & 0xFF] + LINEAR[c >> shift & 0xFF]
				+ LINEAR[d >> shift & 0xFF]) / 4;
		double s = l <= 0.0031308 ? l * 12.92 : 1.055 * Math.pow(l, 1 / 2.4) - 0.055;
		return to8(s);
	}

	private static int normalMean(int a, int b, int c, int d) {
		double x = unit(a >> 16) + unit(b >> 16) + unit(c >> 16) + unit(d >> 16);
		double y = unit(a >> 8) + unit(b >> 8) + unit(c >> 8) + unit(d >> 8);
		double z = unit(a) + unit(b) + unit(c) + unit(d);
		double len = Math.sqrt(x * x + y * y + z * z);
		if (len < 1e-6) {
			x = 0;
			y = 0;
			z = 1;
		} else {
			x /= len;
			y /= len;
			z /= len;
		}
		return to8((x + 1) / 2) << 16 | to8((y + 1) / 2) << 8 | to8((z + 1) / 2);
	}

	private static double unit(int v) {
		return (v & 0xFF) / 255.0 * 2 - 1;
	}

	private static int to8(double v) {
		return Math.max(0, Math.min(255, (int) Math.floor(v * 255 + 0.5)));
	}
}
