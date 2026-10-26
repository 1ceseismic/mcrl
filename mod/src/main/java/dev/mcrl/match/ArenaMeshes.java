package dev.mcrl.match;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.mcrl.natives.Breadcrumb;
import dev.mcrl.natives.McrlNative;
import dev.mcrl.natives.McrlNativeException;
import dev.mcrl.natives.NativeBoot;
import dev.mcrl.rlassets.McrlPaths;
import dev.mcrl.rlassets.arena.ArenaCache;
import dev.mcrl.rlassets.arena.CmfHash;

public final class ArenaMeshes {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	public static final String PROPERTY = "mcrl.arenaMeshesDir";
	public static final int SOCCAR_MESHES = 16;

	public enum Source { PROPERTY, CACHE, SETTING }

	public record Set(MemorySegment handle, Source source, Path dir, int[] hashes, int knownMask) {
		public Set {
			hashes = hashes.clone();
		}

		@Override
		public int[] hashes() {
			return hashes.clone();
		}

		public int known() {
			return Integer.bitCount(knownMask);
		}
	}

	public static final class Missing extends Exception {
		Missing(String why) {
			super(why + ". Set the arena meshes folder (arenaMeshesDir) on the Match tab of the mcrl settings (M) to "
					+ "RocketSim's collision_meshes folder (soccar/mesh_0..15.cmf)");
		}
	}

	private record Key(Source source, Path dir) {
	}

	private static final Map<Key, Set> LOADED = new HashMap<>();
	private static final Map<Key, Long> IDENTITY = new HashMap<>();

	private ArenaMeshes() {
	}

	public static synchronized Set load() throws Missing {
		Choice c = choose();
		return loadDir(c.source(), c.dirText());
	}

	private record Choice(Source source, String dirText) {
	}

	private static Choice choose(Supplier<String> setting) {
		String chosen = System.getProperty(PROPERTY);
		if (chosen != null && !chosen.isBlank()) {
			return new Choice(Source.PROPERTY, chosen.trim());
		}
		Path cached = ArenaCache.accepted(cacheDir());
		if (cached != null) {
			return new Choice(Source.CACHE, cached.toString());
		}
		return new Choice(Source.SETTING, setting.get().trim());
	}

	private static Choice choose() {
		return choose(() -> MatchConfig.read(MatchConfig.file()).arenaMeshesDir());
	}

	public static Path cacheDir() {
		boolean property = !System.getProperty("mcrl.rlCacheDir", "").isBlank();
		return McrlPaths.cacheDir(property ? "" : MatchConfig.rlCacheDir(MatchConfig.file()));
	}

	public record Status(@Nullable Source source, int files, int known) {
	}

	public static Status status(String settingDir) {
		Choice c = choose(() -> settingDir);
		return c.dirText().isBlank() ? new Status(null, 0, 0) : status(c.source(), c.dirText());
	}

	private static Status status(Source source, String dirText) {
		List<Path> files;
		try {
			files = files(folder(dirText));
		} catch (InvalidPathException e) {
			files = List.of();
		}
		if (files.isEmpty()) {
			return new Status(null, 0, 0);
		}
		int[] hashes = new int[files.size()];
		for (int i = 0; i < hashes.length; i++) {
			try {
				hashes[i] = CmfHash.of(Files.readAllBytes(files.get(i)));
			} catch (IOException | IllegalArgumentException e) {
				hashes[i] = 0;
			}
		}
		return new Status(source, files.size(), Integer.bitCount(CmfHash.knownMask(hashes)));
	}

	private static Set loadDir(Source source, String dirText) throws Missing {
		if (dirText.isBlank()) {
			throw new Missing("No arena meshes folder is set");
		}
		Path dir;
		try {
			dir = folder(dirText);
		} catch (InvalidPathException e) {
			throw new Missing("The arena meshes folder '" + dirText + "' is not a path (" + e.getReason() + ")");
		}
		Key key = new Key(source, dir.toAbsolutePath().normalize());
		Set have = LOADED.get(key);
		if (have != null) {
			return have;
		}
		List<Path> files = files(dir);
		if (files.isEmpty()) {
			throw new Missing("No .cmf files in " + dir + " or " + dir.resolve("soccar"));
		}
		List<byte[]> bytes = new ArrayList<>(files.size());
		for (Path f : files) {
			try {
				bytes.add(Files.readAllBytes(f));
			} catch (IOException e) {
				throw new Missing("Cannot read " + f + " (" + e + ")");
			}
		}
		Set s;
		try {
			s = loadBytes(source, dir, bytes);
		} catch (McrlNativeException e) {
			throw new Missing("The arena meshes in " + dir + " do not load (" + e.getMessage() + ")");
		}
		LOADED.put(key, s);
		return s;
	}

	public static long identity() {
		Choice c = choose();
		if (c.dirText().isBlank()) {
			return 0;
		}
		Key key;
		try {
			key = new Key(c.source(), folder(c.dirText()).toAbsolutePath().normalize());
		} catch (InvalidPathException e) {
			return 0;
		}
		synchronized (IDENTITY) {
			return IDENTITY.computeIfAbsent(key, k -> identityOf(readAll(k.dir())));
		}
	}

	static long identityOf(List<byte[]> files) {
		if (files.isEmpty()) {
			return 0;
		}
		try {
			MessageDigest d = MessageDigest.getInstance("SHA-256");
			for (byte[] f : files) {
				d.update(ByteBuffer.allocate(4).putInt(f.length).array());
				d.update(f);
			}
			long id = ByteBuffer.wrap(d.digest()).getLong();
			return id == 0 ? 1 : id;
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static List<byte[]> readAll(Path dir) {
		List<byte[]> out = new ArrayList<>();
		try {
			for (Path f : files(dir)) {
				out.add(Files.readAllBytes(f));
			}
		} catch (IOException e) {
			return List.of();
		}
		return out;
	}

	private static @Nullable String clientSource(String settingDir) {
		String s = choose(() -> settingDir).dirText();
		return s.isEmpty() ? null : s;
	}

	public static String clientKey(String settingDir) {
		String src = clientSource(settingDir);
		if (src == null) {
			return "";
		}
		StringBuilder b = new StringBuilder(src);
		try {
			for (Path f : files(folder(src))) {
				b.append('|').append(f.getFileName()).append(':').append(Files.size(f));
			}
		} catch (IOException | InvalidPathException e) {
			b.append("|unreadable");
		}
		return b.toString();
	}

	public static List<byte[]> clientBytes(String settingDir) {
		String key = clientSource(settingDir);
		if (key == null) {
			return List.of();
		}
		try {
			List<byte[]> out = new ArrayList<>();
			for (Path f : files(folder(key))) {
				out.add(Files.readAllBytes(f));
			}
			return out;
		} catch (IOException | InvalidPathException e) {
			return List.of();
		}
	}

	public static Path folder(String dirText) {
		return McrlPaths.home(dirText);
	}

	private static Set loadBytes(Source source, Path dir, List<byte[]> files) {
		NativeBoot.ensureStarted();
		int n = files.size();
		MemorySegment handle;
		int[] hashes;
		try (Arena a = Arena.ofConfined()) {
			MemorySegment ptrs = a.allocate(ADDRESS, n);
			MemorySegment sizes = a.allocate(JAVA_INT, n);
			MemorySegment out = a.allocate(JAVA_INT, n);
			for (int i = 0; i < n; i++) {
				byte[] f = files.get(i);
				ptrs.setAtIndex(ADDRESS, i, f.length == 0 ? a.allocate(1) : a.allocateFrom(JAVA_BYTE, f));
				sizes.setAtIndex(JAVA_INT, i, f.length);
			}
			Breadcrumb.enter(Breadcrumb.Op.ARENA_MESHES, 0, 0, 0, 0);
			try {
				handle = McrlNative.arenaMeshesLoad(ptrs, sizes, n, out);
			} finally {
				// Not a sim thread: free its breadcrumb slot.
				Breadcrumb.release();
			}
			hashes = out.toArray(JAVA_INT);
		}
		Set s = new Set(handle, source, dir, hashes, CmfHash.knownMask(hashes));
		if (s.known() < SOCCAR_MESHES) {
			LOG.warn("mcrl: arena meshes: " + s.known() + " of " + SOCCAR_MESHES + " known RocketSim SOCCAR hashes (" + n
					+ " files in " + dir + ")");
		}
		LOG.info("mcrl: arena meshes loaded from {}: {} files, {} of {} known", dir, n, s.known(), SOCCAR_MESHES);
		return s;
	}

	public static List<Path> files(Path dir) {
		List<Path> in = cmfIn(dir.resolve("soccar"));
		return in.isEmpty() ? cmfIn(dir) : in;
	}

	private static List<Path> cmfIn(Path d) {
		if (!Files.isDirectory(d)) {
			return List.of();
		}
		try (Stream<Path> s = Files.list(d)) {
			return s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".cmf")
					&& Files.isRegularFile(p)).sorted(NATURAL).toList();
		} catch (IOException | UncheckedIOException e) {
			return List.of();
		}
	}

	static final Comparator<Path> NATURAL = Comparator.<Path, String>comparing(p -> p.getFileName().toString(),
			ArenaMeshes::naturalCompare).thenComparing(p -> p.getFileName().toString());

	static int naturalCompare(String a, String b) {
		int i = 0, j = 0;
		while (i < a.length() && j < b.length()) {
			char ca = a.charAt(i), cb = b.charAt(j);
			if (Character.isDigit(ca) && Character.isDigit(cb)) {
				int si = i, sj = j;
				while (i < a.length() && Character.isDigit(a.charAt(i))) {
					i++;
				}
				while (j < b.length() && Character.isDigit(b.charAt(j))) {
					j++;
				}
				String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
				String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
				int c = na.length() != nb.length() ? Integer.compare(na.length(), nb.length()) : na.compareTo(nb);
				if (c != 0) {
					return c;
				}
			} else {
				if (ca != cb) {
					return Character.compare(ca, cb);
				}
				i++;
				j++;
			}
		}
		return Integer.compare(a.length() - i, b.length() - j);
	}
}
