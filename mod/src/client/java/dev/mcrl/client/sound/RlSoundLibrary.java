package dev.mcrl.client.sound;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import javax.sound.sampled.AudioFormat;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.audio.SoundBuffer;

import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

import dev.mcrl.rlassets.Pcm;
import dev.mcrl.rlassets.PcmOps;
import dev.mcrl.rlassets.WavIo;
import dev.mcrl.rlassets.wwise.HircModel;

public final class RlSoundLibrary {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static final String NAMESPACE = "mcrl_rl";
	private static final String PREFIX = "sounds/", OGG = ".ogg";

	private static volatile @Nullable RlSoundLibrary game;

	private final Executor io;
	private volatile @Nullable Path dir;
	private volatile Map<Long, HircModel.Media> media = Map.of();
	private volatile Map<String, Source> byStem = Map.of();
	private final Map<String, CompletableFuture<SoundBuffer>> cache = new ConcurrentHashMap<>();
	private final List<CompletableFuture<SoundBuffer>> retired = new ArrayList<>();
	private final Set<String> warned = ConcurrentHashMap.newKeySet();
	private @Nullable CompletableFuture<SoundBuffer> silence;

	private record Source(String file, boolean keepChannels) {
	}

	private RlSoundLibrary(Executor io) {
		this.io = io;
	}

	public static RlSoundLibrary game() {
		RlSoundLibrary g = game;
		if (g == null) {
			synchronized (RlSoundLibrary.class) {
				g = game;
				if (g == null) {
					g = new RlSoundLibrary(Util.nonCriticalIoPool());
					game = g;
				}
			}
		}
		return g;
	}

	public static @Nullable Identifier idOfStem(String stem) {
		return stem.isEmpty() || stem.indexOf('/') >= 0 ? null : Identifier.tryBuild(NAMESPACE, stem);
	}

	private static @Nullable String stem(Identifier soundPath) {
		String p = soundPath.getPath();
		if (!NAMESPACE.equals(soundPath.getNamespace()) || !p.startsWith(PREFIX) || !p.endsWith(OGG)) {
			return null;
		}
		String s = p.substring(PREFIX.length(), p.length() - OGG.length());
		return s.isEmpty() || s.indexOf('/') >= 0 ? null : s;
	}

	private static SoundBuffer decode(Path wav, boolean keepChannels) throws IOException {
		Pcm pcm = WavIo.read(wav);
		if (pcm.channels() != 1 && !keepChannels) {
			pcm = PcmOps.toMono(pcm);
		} else if (pcm.channels() > 2) {
			pcm = PcmOps.toStereo(pcm);
		}
		if (pcm.samples().length == 0) {
			throw new IOException(wav + " holds no samples");
		}
		return new SoundBuffer(pcmBytes(pcm), new AudioFormat(pcm.rate(), 16, pcm.channels(), true, false));
	}

	private static ByteBuffer pcmBytes(Pcm pcm) {
		ByteBuffer b = ByteBuffer.allocateDirect(pcm.samples().length * 2).order(ByteOrder.LITTLE_ENDIAN);
		b.asShortBuffer().put(pcm.samples());
		return b;
	}

	public synchronized void bind(@Nullable Path dir, Map<Long, HircModel.Media> media) {
		retired.addAll(cache.values());
		cache.clear();
		Map<String, Source> stems = new HashMap<>();
		media.values().forEach(m -> {
			stems.put("w" + m.id(), new Source(m.file(), false));
			stems.put("w" + m.id() + "_2d", new Source(m.file(), true));
		});
		this.byStem = Map.copyOf(stems);
		this.media = Map.copyOf(media);
		this.dir = dir;
	}

	public @Nullable CompletableFuture<SoundBuffer> buffer(Identifier soundPath) {
		if (!NAMESPACE.equals(soundPath.getNamespace())) {
			return null;
		}
		String s = stem(soundPath);
		Path d = dir;
		Source v = s == null ? null : byStem.get(s);
		if (v == null || d == null) {
			warnOnce(soundPath.toString(), "the RL cache lists no sound " + soundPath + "; playing silence");
			synchronized (this) {
				if (silence == null) {
					silence = CompletableFuture.completedFuture(silence());
				}
				return silence;
			}
		}
		return cache.computeIfAbsent(s, k -> CompletableFuture.supplyAsync(() -> load(d, v), io));
	}

	private SoundBuffer load(Path d, Source v) {
		Path p = d.resolve(v.file()).normalize();
		try {
			if (!p.startsWith(d.normalize())) {
				throw new IOException("outside the cache dir");
			}
			return decode(p, v.keepChannels());
		} catch (IOException | RuntimeException e) {
			warnOnce(p.toString(), "RL sound " + p + " does not decode (" + e.getMessage() + "); playing silence");
			return silence();
		}
	}

	public synchronized void clear() {
		List<CompletableFuture<SoundBuffer>> all = new ArrayList<>(cache.values());
		all.addAll(retired);
		if (silence != null) {
			all.add(silence);
		}
		cache.clear();
		retired.clear();
		silence = null;
		for (CompletableFuture<SoundBuffer> f : all) {
			SoundBuffer b = f.getNow(null);
			if (b != null) {
				b.discardAlBuffer();
			}
		}
	}

	private static SoundBuffer silence() {
		return new SoundBuffer(pcmBytes(new Pcm(8000, 1, new short[80])), new AudioFormat(8000, 16, 1, true, false));
	}

	private void warnOnce(String key, String message) {
		if (warned.add(key)) {
			LOG.warn("mcrl: {}", message);
		}
	}
}
