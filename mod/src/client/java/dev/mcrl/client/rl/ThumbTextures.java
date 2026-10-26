package dev.mcrl.client.rl;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import dev.mcrl.Mcrl;
import dev.mcrl.rlassets.bodies.BodyIds;

public final class ThumbTextures {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static final Map<String, Identifier> READY = new HashMap<>();
	private static final Map<String, NativeImage> PENDING = new ConcurrentHashMap<>();
	private static final Set<String> ASKED = ConcurrentHashMap.newKeySet();
	private static @Nullable RlCache source;

	private ThumbTextures() {
	}

	public static @Nullable Identifier get(String id) {
		RlCache rl = RlCache.loaded();
		if (rl != source) {
			release();
			source = rl;
		}
		if (rl == null || !BodyIds.valid(id)) {
			return null;
		}
		Identifier ready = READY.get(id);
		if (ready != null) {
			return ready;
		}
		NativeImage img = PENDING.remove(id);
		if (img != null) {
			Identifier tid = Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "rl/thumb/" + id.toLowerCase(Locale.ROOT));
			Minecraft.getInstance().getTextureManager().register(tid, new DynamicTexture(() -> "mcrl thumb " + id, img));
			READY.put(id, tid);
			return tid;
		}
		if (ASKED.add(id)) {
			RlCache asked = rl;
			BodyJobs.game().thumbnail(id, p -> read(asked, id, p));
		}
		return null;
	}

	private static void read(RlCache rl, String id, Path png) {
		try {
			BufferedImage b = ImageIO.read(png.toFile());
			if (b == null || rl != RlCache.loaded()) {
				return;
			}
			NativeImage img = new NativeImage(b.getWidth(), b.getHeight(), false);
			for (int y = 0; y < b.getHeight(); y++) {
				for (int x = 0; x < b.getWidth(); x++) {
					img.setPixel(x, y, b.getRGB(x, y));
				}
			}
			NativeImage old = PENDING.put(id, img);
			if (old != null) {
				old.close();
			}
		} catch (Exception e) {
			LOG.warn("mcrl: RL body {} thumbnail unreadable: {}", id, e.toString());
		}
	}

	private static void release() {
		for (Identifier id : READY.values()) {
			Minecraft.getInstance().getTextureManager().release(id);
		}
		READY.clear();
		PENDING.values().forEach(NativeImage::close);
		PENDING.clear();
		ASKED.clear();
		BodyJobs.game().forgetThumbnails();
	}
}
