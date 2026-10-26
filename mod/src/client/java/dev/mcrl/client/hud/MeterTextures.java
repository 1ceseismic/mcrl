package dev.mcrl.client.hud;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;

import javax.imageio.ImageIO;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;

import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.texture.DynamicTexture;

import dev.mcrl.client.rl.RlCache;

final class MeterTextures {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static final double MULT_STEP = 1.0 / 512, OFFSET_STEP = 0.5;

	enum Layer {
		BACKGROUND("boostmeter_background"), GLOW("boostmeter_glow"), TINT("boostmeter_filltintableportion"),
		FILL("boostmeter_fill"), DOT("glowingreddot"), FADE("glow_linearfade");

		final String cacheName;

		Layer(String cacheName) {
			this.cacheName = cacheName;
		}
	}

	private record Base(int[] argb, int width, int height, boolean fromCache) {
	}

	private final Map<Layer, Base> bases = new EnumMap<>(Layer.class);
	private final Map<Layer, LayerTexture> textures = new EnumMap<>(Layer.class);
	private final Map<Layer, ColorTransform> applied = new EnumMap<>(Layer.class);
	private @Nullable RlCache source;
	private boolean loaded;

	private static int[] retint(int[] base, ColorTransform ct) {
		int[] a = new int[256], r = new int[256], g = new int[256], b = new int[256];
		for (int v = 0; v < 256; v++) {
			int c = ct.apply(v << 24 | v << 16 | v << 8 | v);
			a[v] = c & 0xFF000000;
			r[v] = c & 0xFF0000;
			g[v] = c & 0xFF00;
			b[v] = c & 0xFF;
		}
		int[] out = new int[base.length];
		for (int i = 0; i < base.length; i++) {
			int p = base[i];
			out[i] = a[p >>> 24] | r[p >> 16 & 0xFF] | g[p >> 8 & 0xFF] | b[p & 0xFF];
		}
		return out;
	}

	private static boolean changed(@Nullable ColorTransform last, ColorTransform next) {
		return last == null || Math.abs(last.mr() - next.mr()) > MULT_STEP || Math.abs(last.mg() - next.mg()) > MULT_STEP
				|| Math.abs(last.mb() - next.mb()) > MULT_STEP || Math.abs(last.ma() - next.ma()) > MULT_STEP
				|| Math.abs(last.or() - next.or()) > OFFSET_STEP || Math.abs(last.og() - next.og()) > OFFSET_STEP
				|| Math.abs(last.ob() - next.ob()) > OFFSET_STEP || Math.abs(last.oa() - next.oa()) > OFFSET_STEP;
	}

	private static Map<Layer, Base> bases(Function<String, @Nullable Path> hud) {
		Map<Layer, Base> out = new EnumMap<>(Layer.class);
		for (Layer l : Layer.values()) {
			Base b = read(hud.apply(l.cacheName));
			if (b == null) {
				int[] size = ProceduralMeter.size(l);
				b = new Base(ProceduralMeter.image(l), size[0], size[1], false);
			}
			out.put(l, b);
		}
		return out;
	}

	private static @Nullable Base read(@Nullable Path file) {
		if (file == null || !Files.isRegularFile(file)) {
			return null;
		}
		try {
			BufferedImage img = ImageIO.read(file.toFile());
			if (img == null) {
				throw new IOException("not an image");
			}
			int w = img.getWidth(), h = img.getHeight();
			return new Base(img.getRGB(0, 0, w, h, null, 0, w), w, h, true);
		} catch (IOException | RuntimeException e) { // ImageIO throws unchecked on some broken PNGs
			LOG.warn("mcrl: HUD image {} unreadable ({}); drawing the procedural one", file, e.toString());
			return null;
		}
	}

	void refresh() {
		RlCache cache = RlCache.loaded();
		if (!loaded || cache != source) {
			reload(cache);
		}
	}

	private void reload(@Nullable RlCache cache) {
		source = cache;
		loaded = true;
		bases.clear();
		bases.putAll(bases(name -> cache == null ? null : cache.hud(name)));
		applied.clear();
		for (LayerTexture t : textures.values()) {
			t.close();
		}
		textures.clear();
	}

	boolean fromCache(Layer l) {
		Base b = bases.get(l);
		return b != null && b.fromCache();
	}

	TextureSetup setup(Layer l, ColorTransform ct) {
		refresh();
		Base b = bases.get(l);
		LayerTexture t = textures.computeIfAbsent(l, k -> new LayerTexture(k, b.width(), b.height()));
		if (changed(applied.get(l), ct)) {
			NativeImage img = t.getPixels();
			int[] px = retint(b.argb(), ct);
			for (int y = 0; y < b.height(); y++) {
				for (int x = 0; x < b.width(); x++) {
					img.setPixel(x, y, px[y * b.width() + x]);
				}
			}
			t.upload();
			applied.put(l, ct);
		}
		return TextureSetup.singleTexture(t.getTextureView(), t.getSampler());
	}

	private static final class LayerTexture extends DynamicTexture {
		LayerTexture(Layer l, int w, int h) {
			super(() -> "mcrl hud " + l.cacheName, new NativeImage(w, h, false));
			sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		}
	}
}
