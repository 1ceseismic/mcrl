package dev.mcrl.client.rl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.renderpearl.api.textures.AddressMode;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

import dev.mcrl.Mcrl;
import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.config.McrlConfig.TextureQuality;
import dev.mcrl.client.rl.MipChain.Kind;
import dev.mcrl.rlassets.TextureBaker;
import dev.mcrl.rlassets.model.TextureInfo;

public final class RlTextures {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static final String LOOKUP_TABLE = "TEXTUREGROUP_ColorLookupTable";
	private static final Set<String> MIRROR_WARNED = ConcurrentHashMap.newKeySet();
	private static final RlTextures GAME = new RlTextures();

	public record Upload(int width, int height, List<int[]> abgr, AddressMode addressU, AddressMode addressV) {
		public Upload {
			abgr = List.copyOf(abgr);
			if (abgr.isEmpty()) {
				throw new IllegalArgumentException("no levels");
			}
			for (int k = 0; k < abgr.size(); k++) {
				if (abgr.get(k).length != Math.max(1, width >> k) * Math.max(1, height >> k)) {
					throw new IllegalArgumentException("level " + k + " of a " + width + "x" + height + " texture has "
							+ abgr.get(k).length + " texels");
				}
			}
		}

		public int levels() {
			return abgr.size();
		}

		public boolean mipmapped() {
			return levels() > 1;
		}

		public int width(int level) {
			return Math.max(1, width >> level);
		}

		public int height(int level) {
			return Math.max(1, height >> level);
		}
	}

	private static final class Entry {
		final String name;
		final Identifier id;
		final TextureInfo info;
		final Path file;
		CompletableFuture<Identifier> ready = new CompletableFuture<>();
		int generation;
		@Nullable MipTexture texture;
		boolean pinned;
		final Set<Object> holders = Collections.newSetFromMap(new IdentityHashMap<>());
		final boolean body;

		Entry(String name, Identifier id, TextureInfo info, Path file, boolean body) {
			this.name = name;
			this.id = id;
			this.info = info;
			this.file = file;
			this.body = body;
		}
	}

	private final Executor bodyIo = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "mcrl-bodytextures");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});
	private @Nullable RlCache cache;
	private final Map<String, Entry> entries = new HashMap<>();
	private final Queue<Runnable> bodyInstalls = new ConcurrentLinkedQueue<>();

	private RlTextures() {
	}

	public static CompletableFuture<Identifier> request(String rawName) {
		return GAME.requestTexture(rawName, null, false);
	}

	public static CompletableFuture<Identifier> hold(String rawName, Object holder) {
		return GAME.requestTexture(rawName, holder, false);
	}

	public static CompletableFuture<Identifier> holdBody(String rawName, Object holder) {
		return GAME.requestTexture(rawName, holder, true);
	}

	public static void frame() {
		Runnable r = GAME.bodyInstalls.poll();
		if (r != null) {
			r.run();
		}
	}

	public static void release(String rawName, Object holder) {
		GAME.releaseTexture(rawName, holder);
	}

	public static @Nullable MipTexture get(String rawName) {
		return GAME.texture(rawName);
	}

	public static void reloadAll() {
		GAME.reload();
	}

	public static void setQuality(TextureQuality q) {
		McrlConfig cfg = McrlConfig.get();
		boolean changed = cfg.textureQuality != q;
		cfg.textureQuality = q;
		cfg.save();
		if (changed) {
			reloadAll();
		}
	}

	public static Kind kindOf(TextureInfo t) {
		return switch (Objects.requireNonNullElse(t.compression(), "")) {
			case "TC_Normalmap", "TC_NormalmapAlpha" -> Kind.NORMAL;
			case "TC_NormalmapSwizzle" -> Kind.DATA;
			default -> "PF_G8".equals(t.format()) || !t.srgb() ? Kind.DATA : Kind.COLOR_SRGB;
		};
	}

	public static Upload prepare(TextureInfo info, TextureBaker.Texels texels, TextureQuality q) {
		return upload(texels, kindOf(info), !LOOKUP_TABLE.equals(info.lodGroup()), address(info, info.addressX()),
				address(info, info.addressY()), q);
	}

	private static Upload upload(TextureBaker.Texels t, Kind kind, boolean mips, AddressMode u, AddressMode v,
			TextureQuality q) {
		if (!mips) {
			return new Upload(t.width(), t.height(), List.of(abgr(t.argb())), u, v);
		}
		MipChain chain = MipChain.build(t.width(), t.height(), t.argb(), kind);
		int first = q == TextureQuality.HALF && chain.levels().size() > 1 ? 1 : 0;
		List<int[]> levels = new ArrayList<>();
		for (int k = first; k < chain.levels().size(); k++) {
			levels.add(abgr(chain.levels().get(k)));
		}
		return new Upload(chain.width(first), chain.height(first), levels, u, v);
	}

	private static AddressMode address(TextureInfo t, @Nullable String mode) {
		if ("TA_Clamp".equals(mode)) {
			return AddressMode.CLAMP_TO_EDGE;
		}
		if ("TA_Mirror".equals(mode) && MIRROR_WARNED.add(t.name())) {
			LOG.warn("mcrl: RL texture {} mirrors (TA_Mirror), which Minecraft's samplers cannot: it repeats instead", t.name());
		}
		return AddressMode.REPEAT;
	}

	private static int[] abgr(int[] argb) {
		int[] out = new int[argb.length];
		for (int i = 0; i < argb.length; i++) {
			int c = argb[i];
			out[i] = c & 0xFF00FF00 | c >> 16 & 0xFF | (c & 0xFF) << 16;
		}
		return out;
	}

	synchronized CompletableFuture<Identifier> requestTexture(String name, @Nullable Object holder, boolean body) {
		RlCache c = current();
		if (c == null) {
			return CompletableFuture.failedFuture(new IllegalStateException("no RL cache is loaded"));
		}
		Entry e = entries.get(name);
		if (e == null) {
			TextureInfo info = c.textureInfo(name);
			Path file = c.rawTexture(name);
			if (info == null || file == null) {
				return CompletableFuture.failedFuture(new IllegalArgumentException("the RL cache has no texture " + name));
			}
			e = new Entry(name, idOf(file), info, file, body && holder != null);
			entries.put(name, e);
			load(e);
		}
		if (holder == null) {
			e.pinned = true;
		} else {
			e.holders.add(holder);
		}
		return e.ready;
	}

	synchronized void releaseTexture(String name, Object holder) {
		if (current() == null) {
			return;
		}
		Entry e = entries.get(name);
		if (e == null || !e.holders.remove(holder) || !e.holders.isEmpty() || e.pinned) {
			return;
		}
		entries.remove(name);
		e.ready.completeExceptionally(new CancellationException("the RL texture was released"));
		if (e.texture != null) {
			try {
				render(() -> releaseGpu(e.id));
			} catch (RuntimeException x) {
				LOG.warn("mcrl: the RL texture {} was not released: {}", name, x.toString());
			}
		}
	}

	synchronized @Nullable MipTexture texture(String name) {
		if (current() == null) {
			return null;
		}
		Entry e = entries.get(name);
		return e == null ? null : e.texture;
	}

	synchronized void reload() {
		if (current() == null) {
			return;
		}
		for (Entry e : entries.values()) {
			load(e);
		}
	}

	private @Nullable RlCache current() {
		RlCache c = RlCache.loaded();
		if (c != cache) {
			List<Identifier> old = new ArrayList<>();
			for (Entry e : entries.values()) {
				old.add(e.id);
				e.ready.completeExceptionally(new CancellationException("the RL cache was replaced"));
			}
			entries.clear();
			cache = c;
			if (!old.isEmpty()) {
				try {
					render(() -> old.forEach(RlTextures::releaseGpu));
				} catch (RuntimeException x) {
					LOG.warn("mcrl: the replaced RL cache's textures were not released: {}", x.toString());
				}
			}
		}
		return c;
	}

	private void load(Entry e) {
		int gen = ++e.generation;
		if (e.ready.isCompletedExceptionally()) {
			e.ready = new CompletableFuture<>();
		}
		TextureQuality q = McrlConfig.get().textureQuality;
		try {
			CompletableFuture.supplyAsync(() -> {
				try {
					return prepare(e.info, TextureBaker.Texels.read(e.file), q);
				} catch (IOException x) {
					throw new UncheckedIOException(x);
				}
			}, e.body ? bodyIo : Util.ioPool())
					.thenAcceptAsync(u -> install(e, gen, u), e.body ? bodyInstalls::add : RlTextures::render)
					.exceptionally(t -> {
						failed(e, gen, t);
						return null;
					});
		} catch (RuntimeException x) {
			failed(e, gen, x);
		}
	}

	private synchronized void install(Entry e, int gen, Upload u) {
		if (entries.get(e.name) != e || e.generation != gen) {
			return;
		}
		MipTexture t = new MipTexture("mcrl " + e.id, u);
		try {
			Minecraft.getInstance().getTextureManager().register(e.id, t);
		} catch (RuntimeException x) {
			t.close();
			throw x;
		}
		e.texture = t;
		e.ready.complete(e.id);
	}

	private synchronized void failed(Entry e, int gen, Throwable t) {
		if (entries.get(e.name) != e || e.generation != gen) {
			return;
		}
		Throwable cause = unwrap(t);
		LOG.warn("mcrl: RL texture {} did not load: {}", e.name, cause.toString());
		e.ready.completeExceptionally(cause);
	}

	private static void render(Runnable r) {
		Minecraft.getInstance().schedule(r);
	}

	private static void releaseGpu(Identifier id) {
		Minecraft.getInstance().getTextureManager().release(id);
	}

	private static Identifier idOf(Path file) {
		String stem = file.getFileName().toString();
		if (stem.endsWith(".png")) {
			stem = stem.substring(0, stem.length() - 4);
		}
		return Identifier.fromNamespaceAndPath(Mcrl.MOD_ID,
				"rl/raw/" + stem.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_"));
	}

	private static Throwable unwrap(Throwable t) {
		Throwable c = t;
		while ((c instanceof CompletionException || c instanceof UncheckedIOException) && c.getCause() != null) {
			c = c.getCause();
		}
		return c;
	}
}
