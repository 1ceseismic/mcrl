package dev.mcrl.rlassets;

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

public final class TextureBaker {
	public static final List<String> LEGACY = List.of("body_blue", "body_orange", "chassis", "wheel_rim", "wheel_tyre",
			"ball");

	private TextureBaker() {
	}

	public record Texels(int width, int height, int[] argb) {
		public Texels {
			if (width <= 0 || height <= 0 || argb.length != width * height) {
				throw new IllegalArgumentException("bad texel buffer " + width + "x" + height + " / " + argb.length);
			}
		}

		public static Texels read(Path file) throws IOException {
			BufferedImage img = ImageIO.read(file.toFile());
			if (img == null) {
				throw new IOException(file + ": not a readable image");
			}
			return of(img);
		}

		// Stored values: ImageIO's own conversion brightens 8-bit grey.
		public static Texels of(BufferedImage img) {
			int w = img.getWidth(), h = img.getHeight();
			int[] out = new int[w * h];
			ColorModel cm = img.getColorModel();
			if (cm instanceof IndexColorModel) {
				img.getRGB(0, 0, w, h, out, 0, w);
				return new Texels(w, h, out);
			}
			Raster r = img.getRaster();
			int bands = r.getNumBands();
			int colours = cm.getNumColorComponents();
			boolean alpha = cm.hasAlpha() && bands > colours;
			int[] max = new int[bands];
			for (int b = 0; b < bands; b++) {
				max[b] = (1 << cm.getComponentSize(Math.min(b, cm.getNumComponents() - 1))) - 1;
			}
			int[] row = new int[w * bands];
			for (int y = 0; y < h; y++) {
				r.getPixels(0, y, w, 1, row);
				for (int x = 0; x < w; x++) {
					int o = x * bands;
					int cr = to8(row[o], max[0]);
					int cg = colours >= 3 ? to8(row[o + 1], max[1]) : cr;
					int cb = colours >= 3 ? to8(row[o + 2], max[2]) : cr;
					int ca = alpha ? to8(row[o + colours], max[colours]) : 255;
					out[y * w + x] = ca << 24 | cr << 16 | cg << 8 | cb;
				}
			}
			return new Texels(w, h, out);
		}

		private static int to8(int v, int max) {
			return max == 255 ? v : (int) Math.round(v * 255.0 / max);
		}
	}
}
