package dev.mcrl.rlassets.bodies;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.jspecify.annotations.Nullable;

import dev.mcrl.rlassets.CacheWriter;
import dev.mcrl.rlassets.Converter;
import dev.mcrl.rlassets.UmodelRunner;

public final class BodyThumbs {
	public static final int PX = 128;

	private BodyThumbs() {
	}

	public static @Nullable Path export(UmodelRunner umodel, Path cookedDir, String id, Path cacheDir,
			CacheWriter.Guard guard) throws IOException, InterruptedException {
		Path target = BodyIds.thumb(cacheDir, id);
		String pkg = id + "_T_SF.upk";
		if (!Files.isRegularFile(cookedDir.resolve(pkg))) {
			return null;
		}
		CacheWriter.checkCache(cacheDir, guard);
		Converter.removeStaleWork(umodel.workDir());
		Path out = umodel.workDir().resolve("bodythumb-" + ProcessHandle.current().pid() + "-" + id);
		try {
			CacheWriter.deleteTree(out);
			Files.createDirectories(out);
			umodel.exportObjects(pkg, List.of(id + "_TThumbnail"), out);
			Path png = find(out, id + "_TThumbnail.png");
			BufferedImage src = png == null ? null : ImageIO.read(png.toFile());
			if (src == null) {
				return null;
			}
			BufferedImage thumb = downscale(src, PX);
			try (CacheWriter.CacheLock held = CacheWriter.lock(cacheDir)) {
				Files.createDirectories(target.getParent());
				Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + ProcessHandle.current().pid());
				try {
					if (!ImageIO.write(thumb, "png", tmp.toFile())) {
						throw new IOException("no PNG writer");
					}
					try {
						Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
					} catch (AtomicMoveNotSupportedException e) {
						Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
					}
				} finally {
					Files.deleteIfExists(tmp);
				}
			}
			return target;
		} finally {
			CacheWriter.deleteTree(out);
		}
	}

	public static BufferedImage downscale(BufferedImage src, int px) {
		int w = src.getWidth(), h = src.getHeight();
		BufferedImage out = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
		double sx = (double) w / px, sy = (double) h / px;
		for (int oy = 0; oy < px; oy++) {
			double y0 = oy * sy, y1 = y0 + sy;
			for (int ox = 0; ox < px; ox++) {
				double x0 = ox * sx, x1 = x0 + sx;
				double a = 0, r = 0, g = 0, b = 0, area = 0;
				for (int y = (int) Math.floor(y0); y < Math.min(h, Math.ceil(y1)); y++) {
					double cy = Math.min(y1, y + 1) - Math.max(y0, y);
					for (int x = (int) Math.floor(x0); x < Math.min(w, Math.ceil(x1)); x++) {
						double c = cy * (Math.min(x1, x + 1) - Math.max(x0, x));
						int argb = src.getRGB(x, y);
						double al = (argb >>> 24) / 255.0;
						a += c * al;
						r += c * al * (argb >> 16 & 0xFF);
						g += c * al * (argb >> 8 & 0xFF);
						b += c * al * (argb & 0xFF);
						area += c;
					}
				}
				int oa = (int) Math.round(255 * a / area);
				int or = a > 0 ? (int) Math.round(r / a) : 0, og = a > 0 ? (int) Math.round(g / a) : 0,
						ob = a > 0 ? (int) Math.round(b / a) : 0;
				out.setRGB(ox, oy, oa << 24 | or << 16 | og << 8 | ob);
			}
		}
		return out;
	}

	private static @Nullable Path find(Path root, String file) throws IOException {
		try (Stream<Path> s = Files.walk(root)) {
			return s.filter(p -> p.getFileName().toString().equalsIgnoreCase(file)).findFirst().orElse(null);
		}
	}
}
