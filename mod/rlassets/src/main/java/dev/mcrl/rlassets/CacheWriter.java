package dev.mcrl.rlassets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.mcrl.rlassets.wwise.HircExport;
import dev.mcrl.rlassets.wwise.HircJson;
import dev.mcrl.rlassets.wwise.HircModel;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class CacheWriter {
	public static final int FORMAT = 2;
	public static final String EFFECTS = "effects.json", FX_ACTORS = "fxactors.json", MATERIALS = "materials.json";
	public static final String SOUND_CUES = "sounds/cues.json";
	public static final List<String> CARRIED = List.of("arena", "stadium", "bodies");
	private static final Pattern NAME = Pattern.compile("[a-z0-9_]+");
	private static final Pattern MEDIA_WAV = Pattern.compile("w\\d+\\.wav");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Gson FX_GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();

	private CacheWriter() {
	}

	public record MeshEntry(MeshData mesh, String texture) {
	}

	public record Content(String fingerprint, String rlVersion, Map<String, MeshEntry> meshes, Map<String, float[]> sockets,
			JsonObject sounds, Map<String, MeshData> extras, JsonObject alignment, List<String> problems, SoundInfo soundInfo,
			JsonObject wheels, Fx fx, Raw raw, JsonObject body) {
		public Content(String fingerprint, String rlVersion, Map<String, MeshEntry> meshes, Map<String, float[]> sockets,
				JsonObject sounds, Map<String, MeshData> extras, JsonObject alignment, List<String> problems,
				SoundInfo soundInfo, JsonObject wheels, Fx fx, Raw raw) {
			this(fingerprint, rlVersion, meshes, sockets, sounds, extras, alignment, problems, soundInfo, wheels, fx, raw,
					new JsonObject());
		}

		public Content(String fingerprint, String rlVersion, Map<String, MeshEntry> meshes, Map<String, float[]> sockets,
				Map<String, MeshData> extras, JsonObject alignment, List<String> problems, Raw raw) {
			this(fingerprint, rlVersion, meshes, sockets, new JsonObject(), extras, alignment, problems, SoundInfo.NONE,
					new JsonObject(), Fx.NONE, raw);
		}
	}

	public record Fx(@Nullable JsonObject effects, @Nullable JsonObject fxActors, @Nullable JsonObject soundCues,
			@Nullable String missing) {
		public static final Fx NONE = new Fx(null, null, null, null);

		public Fx(@Nullable JsonObject effects, @Nullable JsonObject fxActors, @Nullable String missing) {
			this(effects, fxActors, null, missing);
		}
	}

	public record Raw(Map<String, List<String>> extraMaterials, @Nullable JsonObject materials, Map<String, Path> textures,
			Map<String, Path> hud) {
		public static final Raw NONE = new Raw(Map.of(), null, Map.of(), Map.of());
	}

	public enum SoundsMissingKind {
		NO_DECODER,
		DECODE_FAILED,
		BANKS
	}

	public record SoundInfo(@Nullable Path stage, @Nullable String missing, @Nullable SoundsMissingKind missingKind,
			@Nullable String decoder, @Nullable String kept) {
		public static final SoundInfo NONE = new SoundInfo(null, null, null, null, null);
	}

	public record SoundsStatus(@Nullable String missing, @Nullable SoundsMissingKind missingKind, @Nullable String decoder,
			@Nullable String kept) {
	}

	public record Guard(Path minecraftDir, @Nullable Path rlInstall) {
		public static Guard standard(@Nullable Path rlInstall) {
			return new Guard(Path.of(System.getProperty("user.home"), ".minecraft"), rlInstall);
		}
	}

	public static final class TargetRefusedException extends IOException {
		public TargetRefusedException(Path target, String why) {
			super("refusing to write the RL cache to " + target + ": it " + why);
		}
	}

	public static final class ValidationException extends Exception {
		private final List<String> problems;

		public ValidationException(List<String> problems) {
			super("cache validation failed: " + String.join("; ", problems));
			this.problems = List.copyOf(problems);
		}

		public List<String> problems() {
			return problems;
		}
	}

	public static List<String> write(Path cacheDir, Content c) throws IOException, ValidationException {
		return write(cacheDir, c, Guard.standard(null));
	}

	public static List<String> write(Path cacheDir, Content c, Guard guard) throws IOException, ValidationException {
		checkTarget(cacheDir, guard);
		Path dir = cacheDir.toAbsolutePath().normalize();
		Path parent = dir.getParent();
		String base = dir.getFileName().toString();
		long pid = ProcessHandle.current().pid();
		List<String> warnings = new ArrayList<>();
		Files.createDirectories(parent);
		try (CacheLock held = lock(dir)) {
			restoreInterrupted(parent, base, dir, pid, warnings);
			removeStale(parent, base);
			Path tmp = parent.resolve(base + ".tmp-" + pid), old = parent.resolve(base + ".old-" + pid);
			deleteTree(tmp);
			List<String> carried = new ArrayList<>();
			try {
				writeAll(tmp, c);
				List<String> problems = validate(tmp, c);
				if (!problems.isEmpty()) {
					throw new ValidationException(problems);
				}
				if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
					for (String name : CARRIED) {
						restoreInterrupted(dir, name, dir.resolve(name), pid, warnings, false);
					}
				}
				carry(dir, tmp, carried);
				swap(tmp, dir, old, warnings);
				return warnings;
			} catch (IOException | ValidationException | RuntimeException e) {
				uncarry(tmp, Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) ? dir : old, carried, e);
				try {
					if (carried.isEmpty()) {
						deleteTree(tmp);
					} else {
						Path kept = parent.resolve(base + ".kept-" + pid);
						deleteTree(kept);
						Files.move(tmp, kept, StandardCopyOption.ATOMIC_MOVE);
						e.addSuppressed(new IOException("kept " + carried + " of the old cache in " + kept));
					}
				} catch (IOException suppressed) {
					e.addSuppressed(suppressed);
				}
				throw e;
			}
		}
	}

	// File locks are per process: threads queue here first.
	private static final Map<Path, Held> HELD = new ConcurrentHashMap<>();

	private static final class Held {
		final ReentrantLock thread = new ReentrantLock();
		@Nullable FileChannel channel;
		@Nullable FileLock file;
	}

	public static final class CacheLock implements AutoCloseable {
		private final Held held;
		private boolean closed;

		private CacheLock(Held held) {
			this.held = held;
		}

		@Override
		public void close() throws IOException {
			if (closed) {
				return;
			}
			closed = true;
			try {
				if (held.thread.getHoldCount() == 1) {
					FileLock f = held.file;
					FileChannel ch = held.channel;
					held.file = null;
					held.channel = null;
					try {
						if (f != null) {
							f.release();
						}
					} finally {
						if (ch != null) {
							ch.close();
						}
					}
				}
			} finally {
				held.thread.unlock();
			}
		}
	}

	public static CacheLock lock(Path cacheDir) throws IOException {
		Path dir = cacheDir.toAbsolutePath().normalize();
		if (dir.getParent() == null || dir.getFileName() == null) {
			throw new IOException("no lock for a filesystem root: " + dir);
		}
		Path file = dir.getParent().resolve(dir.getFileName() + ".lock");
		Held h = HELD.computeIfAbsent(file, k -> new Held());
		try {
			h.thread.lockInterruptibly();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted waiting for the cache lock " + file, e);
		}
		if (h.thread.getHoldCount() > 1) {
			return new CacheLock(h);
		}
		FileChannel ch = null;
		try {
			Files.createDirectories(file.getParent());
			ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			FileLock got = ch.tryLock();
			if (got == null) {
				System.err.println("rlassets: waiting for the cache lock " + file
						+ " (another mcrl process is writing this cache)");
				got = ch.lock();
			}
			h.file = got;
			h.channel = ch;
			return new CacheLock(h);
		} catch (IOException | RuntimeException e) {
			if (ch != null) {
				try {
					ch.close();
				} catch (IOException suppressed) {
					e.addSuppressed(suppressed);
				}
			}
			h.thread.unlock();
			throw e;
		}
	}

	public static void checkTarget(Path cacheDir, Guard guard) throws IOException {
		Path dir = cacheDir.toAbsolutePath().normalize();
		if (dir.getParent() == null || dir.getFileName() == null) {
			throw new TargetRefusedException(dir, "is a filesystem root");
		}
		Path mc = guard.minecraftDir().toAbsolutePath().normalize();
		Path real = realOfExistingPrefix(dir);
		if (dir.startsWith(mc) || real.startsWith(realOfExistingPrefix(mc))) {
			throw new TargetRefusedException(dir, "is under " + mc);
		}
		Path inst = guard.rlInstall();
		if (inst != null && Files.exists(inst)
				&& Files.getFileStore(existingPrefix(real)).equals(Files.getFileStore(inst.toRealPath()))) {
			throw new TargetRefusedException(dir, "is on the Rocket League install's volume (" + inst + ")");
		}
		if (Files.isSymbolicLink(dir)) {
			throw new TargetRefusedException(dir, "is a symbolic link");
		}
		if (Files.exists(dir)) {
			if (!Files.isDirectory(dir)) {
				throw new TargetRefusedException(dir, "exists and is not a directory");
			}
			boolean empty;
			try (Stream<Path> s = Files.list(dir)) {
				empty = s.findAny().isEmpty();
			}
			if (!empty && !hasCacheManifest(dir)) {
				throw new TargetRefusedException(dir, "is not empty and holds no RL cache manifest.json");
			}
		}
	}

	public static void checkCache(Path cacheDir, Guard guard) throws IOException {
		checkTarget(cacheDir, guard);
		Path dir = cacheDir.toAbsolutePath().normalize();
		if (!hasCacheManifest(dir)) {
			throw new TargetRefusedException(dir, "holds no RL cache manifest.json (convert the base cache first)");
		}
	}

	private static boolean hasCacheManifest(Path dir) {
		try {
			JsonElement e = JsonParser.parseString(Files.readString(dir.resolve("manifest.json"), StandardCharsets.UTF_8));
			return e.isJsonObject() && e.getAsJsonObject().has("format");
		} catch (IOException | RuntimeException ex) {
			return false;
		}
	}

	private static Path existingPrefix(Path p) {
		Path e = p;
		while (e != null && !Files.exists(e)) {
			e = e.getParent();
		}
		return e != null ? e : p.getRoot();
	}

	private static Path realOfExistingPrefix(Path p) throws IOException {
		Path e = existingPrefix(p);
		return e.toRealPath().resolve(e.relativize(p)).normalize();
	}

	public static SoundsStatus soundsStatus(Path cacheDir) {
		JsonObject m = manifest(cacheDir);
		if (m == null) {
			return new SoundsStatus(null, null, null, null);
		}
		SoundsMissingKind kind = null;
		String k = string(m, "soundsMissingKind");
		if (k != null) {
			try {
				kind = SoundsMissingKind.valueOf(k);
			} catch (IllegalArgumentException unknown) {
				kind = SoundsMissingKind.DECODE_FAILED;
			}
		}
		return new SoundsStatus(string(m, "soundsMissing"), kind, string(m, "soundsDecoder"), string(m, "soundsKept"));
	}

	public static @Nullable JsonObject reusableSounds(Path cacheDir) {
		JsonObject m = manifest(cacheDir);
		if (m == null || fingerprintOf(cacheDir) == null || !(m.get("sounds") instanceof JsonObject sounds)
				|| !(sounds.get("model") instanceof JsonPrimitive model) || !modelComplete(cacheDir, model.getAsString())) {
			return null;
		}
		JsonObject out = new JsonObject();
		for (String k : List.of("model", "media", "missing")) {
			if (sounds.has(k)) {
				out.add(k, sounds.get(k).deepCopy());
			}
		}
		return out;
	}

	private static boolean stagedSound(Path f) {
		String n = f.getFileName().toString();
		return MEDIA_WAV.matcher(n).matches() || ("sounds/" + n).equals(HircExport.MODEL_FILE);
	}

	private static boolean modelComplete(Path cacheDir, String file) {
		if (!file.equals(HircExport.MODEL_FILE)) {
			return false;
		}
		try {
			for (HircModel.Media m : HircJson.read(cacheDir.resolve(file)).media().values()) {
				Path w = cacheDir.resolve(m.file());
				if (!m.file().startsWith("sounds/") || m.file().contains("..") || !Files.isRegularFile(w, LinkOption.NOFOLLOW_LINKS)
						|| Files.size(w) <= 44) {
					return false;
				}
			}
			return true;
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private static @Nullable JsonObject manifest(Path cacheDir) {
		try {
			JsonElement e = JsonParser.parseString(Files.readString(cacheDir.resolve("manifest.json"), StandardCharsets.UTF_8));
			return e.isJsonObject() ? e.getAsJsonObject() : null;
		} catch (IOException | RuntimeException ex) {
			return null;
		}
	}

	private static @Nullable String string(JsonObject m, String key) {
		JsonElement e = m.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	public static int formatOf(Path cacheDir) {
		JsonObject m = manifest(cacheDir);
		JsonElement f = m == null ? null : m.get("format");
		try {
			return f != null && f.isJsonPrimitive() ? f.getAsInt() : -1;
		} catch (RuntimeException e) {
			return -1;
		}
	}

	public static @Nullable String fingerprintOf(Path cacheDir) {
		try {
			JsonElement e = JsonParser.parseString(Files.readString(cacheDir.resolve("manifest.json"), StandardCharsets.UTF_8));
			if (!e.isJsonObject()) {
				return null;
			}
			JsonObject m = e.getAsJsonObject();
			if (!m.has("format") || m.get("format").getAsInt() != FORMAT || !m.has("fingerprint")) {
				return null;
			}
			return m.get("fingerprint").getAsString();
		} catch (IOException | RuntimeException ex) {
			return null;
		}
	}

	private static void writeAll(Path tmp, Content c) throws IOException {
		Files.createDirectories(tmp.resolve("meshes"));
		Files.createDirectories(tmp.resolve("textures"));
		Path stage = c.soundInfo().stage();
		if (stage != null && Files.isDirectory(stage.resolve("sounds"))) {
			Files.createDirectories(tmp.resolve("sounds"));
			try (var ws = Files.list(stage.resolve("sounds"))) {
				for (Path w : (Iterable<Path>) ws.filter(CacheWriter::stagedSound)::iterator) {
					Files.copy(w, tmp.resolve("sounds").resolve(w.getFileName().toString()));
				}
			}
		}
		JsonObject meshes = new JsonObject();
		for (Map.Entry<String, MeshEntry> e : new TreeMap<>(c.meshes()).entrySet()) {
			String file = "meshes/" + safe(e.getKey()) + ".mcrm";
			Mcrm.write(tmp.resolve(file), e.getValue().mesh());
			JsonObject o = new JsonObject();
			o.addProperty("file", file);
			o.addProperty("texture", e.getValue().texture());
			meshes.add(e.getKey(), o);
		}
		JsonObject textures = new JsonObject();
		JsonObject sockets = new JsonObject();
		for (Map.Entry<String, float[]> e : new TreeMap<>(c.sockets()).entrySet()) {
			JsonArray a = new JsonArray();
			for (float f : e.getValue()) {
				a.add(f);
			}
			sockets.add(e.getKey(), a);
		}
		JsonObject extras = new JsonObject();
		for (Map.Entry<String, MeshData> e : new TreeMap<>(c.extras()).entrySet()) {
			String file = "meshes/" + safe(e.getKey()) + ".mcrm";
			Mcrm.write(tmp.resolve(file), e.getValue());
			JsonObject o = new JsonObject();
			o.addProperty("file", file);
			List<String> mats = c.raw().extraMaterials().get(e.getKey());
			if (mats != null) {
				JsonArray a = new JsonArray();
				mats.forEach(a::add);
				o.add("materials", a);
			}
			extras.add(e.getKey(), o);
		}
		Raw raw = c.raw();
		for (Map.Entry<String, Path> e : new TreeMap<>(raw.textures()).entrySet()) {
			String file = "textures/" + safe(e.getKey()) + ".png";
			Files.copy(e.getValue(), tmp.resolve(file));
			textures.addProperty(e.getKey(), file);
		}
		JsonObject hud = new JsonObject();
		if (!raw.hud().isEmpty()) {
			Files.createDirectories(tmp.resolve("hud"));
			for (Map.Entry<String, Path> e : new TreeMap<>(raw.hud()).entrySet()) {
				String file = "hud/" + safe(e.getKey()) + ".png";
				Files.copy(e.getValue(), tmp.resolve(file));
				hud.addProperty(e.getKey(), file);
			}
		}
		JsonObject m = new JsonObject();
		m.addProperty("format", FORMAT);
		m.addProperty("fingerprint", c.fingerprint());
		m.addProperty("rlVersion", c.rlVersion());
		m.add("meshes", meshes);
		m.add("textures", textures);
		m.add("sockets", sockets);
		m.add("extras", extras);
		if (!c.wheels().isEmpty()) {
			m.add("wheels", c.wheels());
		}
		JsonObject sounds = c.sounds().deepCopy();
		sounds.remove("cues");
		if (c.fx().soundCues() != null) {
			Files.createDirectories(tmp.resolve("sounds"));
			Files.writeString(tmp.resolve(SOUND_CUES), FX_GSON.toJson(c.fx().soundCues()) + "\n", StandardCharsets.UTF_8);
			sounds.addProperty("cues", SOUND_CUES);
		}
		m.add("sounds", sounds);
		SoundInfo si = c.soundInfo();
		if (si.missing() != null) {
			m.addProperty("soundsMissing", si.missing());
		}
		if (si.missingKind() != null) {
			m.addProperty("soundsMissingKind", si.missingKind().name());
		}
		if (si.decoder() != null) {
			m.addProperty("soundsDecoder", si.decoder());
		}
		if (si.kept() != null) {
			m.addProperty("soundsKept", si.kept());
		}
		Fx fx = c.fx();
		if (fx.effects() != null) {
			Files.writeString(tmp.resolve(EFFECTS), FX_GSON.toJson(fx.effects()) + "\n", StandardCharsets.UTF_8);
			m.addProperty("effects", EFFECTS);
		}
		if (fx.fxActors() != null) {
			Files.writeString(tmp.resolve(FX_ACTORS), FX_GSON.toJson(fx.fxActors()) + "\n", StandardCharsets.UTF_8);
			m.addProperty("fxactors", FX_ACTORS);
		}
		if (fx.missing() != null) {
			m.addProperty("fxMissing", fx.missing());
		}
		if (raw.materials() != null) {
			Files.writeString(tmp.resolve(MATERIALS), GSON.toJson(raw.materials()) + "\n", StandardCharsets.UTF_8);
			m.addProperty("materials", MATERIALS);
		}
		if (!hud.isEmpty()) {
			m.add("hud", hud);
		}
		if (!c.body().isEmpty()) {
			m.add("body", c.body());
		}
		Files.writeString(tmp.resolve("alignment.json"), GSON.toJson(c.alignment()) + "\n", StandardCharsets.UTF_8);
		Files.writeString(tmp.resolve("manifest.json"), GSON.toJson(m) + "\n", StandardCharsets.UTF_8);
	}

	private static String safe(String name) throws IOException {
		if (!NAME.matcher(name).matches()) {
			throw new IOException("bad cache entry name '" + name + "'");
		}
		return name;
	}

	private static List<String> validate(Path tmp, Content c) throws IOException {
		List<String> p = new ArrayList<>(c.problems());
		if (c.meshes().isEmpty()) {
			p.add("no meshes");
		}
		for (Map.Entry<String, MeshEntry> e : c.meshes().entrySet()) {
			String name = e.getKey();
			MeshData want = e.getValue().mesh();
			if (want.triangleCount() == 0) {
				p.add("mesh " + name + " has no triangles");
			}
			for (float f : want.vertices()) {
				if (!Float.isFinite(f)) {
					p.add("mesh " + name + " has a non-finite vertex value");
					break;
				}
			}
			if (!TextureBaker.LEGACY.contains(e.getValue().texture())) {
				p.add("mesh " + name + " uses texture '" + e.getValue().texture() + "', which the client does not bake");
			}
			MeshData back = Mcrm.read(tmp.resolve("meshes/" + name + ".mcrm"));
			if (back.vertexCount() != want.vertexCount() || back.indices().length != want.indices().length) {
				p.add("mesh " + name + " did not read back");
			}
		}
		if (c.sounds().has("model") && !modelComplete(tmp, c.sounds().get("model").getAsString())) {
			p.add("sound model " + c.sounds().get("model").getAsString() + " or one of its media was not written");
		}
		if (c.raw().materials() == null) {
			p.add("no materials.json: a format-" + FORMAT + " cache needs the materials");
		}
		for (Map.Entry<String, Path> e : c.raw().textures().entrySet()) {
			sameBytes(e.getValue(), tmp.resolve("textures/" + e.getKey() + ".png"), "raw texture " + e.getKey(), p);
		}
		for (Map.Entry<String, Path> e : c.raw().hud().entrySet()) {
			sameBytes(e.getValue(), tmp.resolve("hud/" + e.getKey() + ".png"), "HUD texture " + e.getKey(), p);
		}
		for (String f : new String[] {c.fx().effects() != null ? EFFECTS : null, c.fx().fxActors() != null ? FX_ACTORS : null,
				c.raw().materials() != null ? MATERIALS : null, c.fx().soundCues() != null ? SOUND_CUES : null}) {
			if (f == null) {
				continue;
			}
			try {
				if (!JsonParser.parseString(Files.readString(tmp.resolve(f), StandardCharsets.UTF_8)).isJsonObject()) {
					p.add(f + " is not a JSON object");
				}
			} catch (IOException | RuntimeException e) {
				p.add(f + " did not read back: " + e.getMessage());
			}
		}
		if (!c.fingerprint().equals(fingerprintOf(tmp))) {
			p.add("manifest did not read back");
		}
		return p;
	}

	private static void sameBytes(Path source, Path copy, String what, List<String> problems) throws IOException {
		if (!Files.isRegularFile(copy) || Files.size(copy) != Files.size(source)) {
			problems.add(what + " was not copied");
		}
	}

	static volatile @Nullable SubtreeWriter beforeCarry;

	private static void carry(Path dir, Path tmp, List<String> moved) throws IOException {
		for (String name : CARRIED) {
			Path from = dir.resolve(name), to = tmp.resolve(name);
			if (Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS) && !Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
				SubtreeWriter seam = beforeCarry;
				if (seam != null) {
					seam.write(Path.of(name));
				}
				Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
				moved.add(name);
			}
		}
	}

	private static void uncarry(Path tmp, Path into, List<String> carried, Exception failure) {
		for (Iterator<String> it = carried.iterator(); it.hasNext();) {
			String name = it.next();
			try {
				if (Files.isDirectory(into, LinkOption.NOFOLLOW_LINKS)
						&& !Files.exists(into.resolve(name), LinkOption.NOFOLLOW_LINKS)) {
					Files.move(tmp.resolve(name), into.resolve(name), StandardCopyOption.ATOMIC_MOVE);
					it.remove();
				}
			} catch (IOException e) {
				failure.addSuppressed(e);
			}
		}
	}

	@FunctionalInterface
	public interface SubtreeWriter {
		void write(Path dir) throws IOException;
	}

	public static List<String> writeSubtree(Path cacheDir, String name, Guard guard, SubtreeWriter writer)
			throws IOException {
		if (!CARRIED.contains(name)) {
			throw new IllegalArgumentException("not a carried subtree: " + name);
		}
		checkTarget(cacheDir, guard);
		Path dir = cacheDir.toAbsolutePath().normalize();
		if (!hasCacheManifest(dir)) {
			throw new TargetRefusedException(dir, "holds no RL cache manifest.json (convert the base cache first)");
		}
		try (CacheLock held = lock(dir)) {
			return replaceDir(dir, name, writer);
		}
	}

	public static List<String> writeNested(Path cacheDir, String subtree, String name, Guard guard, SubtreeWriter writer)
			throws IOException {
		if (!CARRIED.contains(subtree)) {
			throw new IllegalArgumentException("not a carried subtree: " + subtree);
		}
		checkTarget(cacheDir, guard);
		Path dir = cacheDir.toAbsolutePath().normalize();
		if (!name.matches("[A-Za-z0-9_]+")) {
			throw new TargetRefusedException(dir.resolve(subtree).resolve(name), "is not a plain name");
		}
		if (!hasCacheManifest(dir)) {
			throw new TargetRefusedException(dir, "holds no RL cache manifest.json (convert the base cache first)");
		}
		try (CacheLock held = lock(dir)) {
			Path sub = dir.resolve(subtree);
			if (Files.isSymbolicLink(sub)) {
				throw new TargetRefusedException(sub, "is a symbolic link");
			}
			Files.createDirectories(sub);
			return replaceDir(sub, name, writer);
		}
	}

	private static List<String> replaceDir(Path dir, String name, SubtreeWriter writer) throws IOException {
		long pid = ProcessHandle.current().pid();
		List<String> warnings = new ArrayList<>();
		Path target = dir.resolve(name);
		restoreInterrupted(dir, name, target, pid, warnings, false);
		removeStale(dir, name);
		Path tmp = dir.resolve(name + ".tmp-" + pid);
		deleteTree(tmp);
		try {
			Files.createDirectories(tmp);
			writer.write(tmp);
			swap(tmp, target, dir.resolve(name + ".old-" + pid), warnings);
			return warnings;
		} catch (IOException | RuntimeException e) {
			try {
				deleteTree(tmp);
			} catch (IOException suppressed) {
				e.addSuppressed(suppressed);
			}
			throw e;
		}
	}

	// Linux cannot rename a dir over a non-empty one.
	private static void swap(Path tmp, Path dir, Path old, List<String> warnings) throws IOException {
		deleteTree(old);
		boolean hadOld = false;
		try {
			Files.move(dir, old, StandardCopyOption.ATOMIC_MOVE);
			hadOld = true;
		} catch (NoSuchFileException noPreviousCache) {
		}
		try {
			Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			if (hadOld && !Files.exists(dir)) {
				try {
					Files.move(old, dir, StandardCopyOption.ATOMIC_MOVE);
				} catch (IOException rollback) {
					e.addSuppressed(rollback);
				}
			}
			throw e;
		}
		if (hadOld) {
			try {
				deleteTree(old);
			} catch (IOException e) {
				warnings.add("could not delete the previous cache copy " + old + ": " + e);
			}
		}
	}

	public static List<String> recover(Path cacheDir) throws IOException {
		List<String> warnings = new ArrayList<>();
		Path dir = cacheDir.toAbsolutePath().normalize();
		Path parent = dir.getParent();
		if (parent == null || dir.getFileName() == null || Files.exists(dir, LinkOption.NOFOLLOW_LINKS)
				|| !Files.isDirectory(parent)) {
			return warnings;
		}
		try {
			checkTarget(dir, Guard.standard(null));
		} catch (TargetRefusedException refused) {
			return warnings;
		}
		restoreInterrupted(parent, dir.getFileName().toString(), dir, ProcessHandle.current().pid(), warnings);
		return warnings;
	}

	private static void restoreInterrupted(Path parent, String base, Path dir, long self, List<String> warnings) throws IOException {
		restoreInterrupted(parent, base, dir, self, warnings, true);
	}

	private static void restoreInterrupted(Path parent, String base, Path dir, long self, List<String> warnings,
			boolean cache) throws IOException {
		if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		Path newest = null;
		FileTime newestTime = null;
		for (Path p : leftovers(parent, base, "old")) {
			long pid = pidOf(p);
			if ((pid == self || !alive(pid)) && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
					&& (!cache || hasCacheManifest(p))) {
				FileTime t = Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS);
				if (newestTime == null || t.compareTo(newestTime) > 0) {
					newest = p;
					newestTime = t;
				}
			}
		}
		if (newest != null) {
			Files.move(newest, dir, StandardCopyOption.ATOMIC_MOVE);
			warnings.add("restored the previous " + (cache ? "cache" : base) + " from " + newest
					+ " (an earlier write was interrupted)");
		}
	}

	private static final Pattern LEFTOVER = Pattern.compile("\\.(tmp|old)-(\\d{1,18})");

	private static void removeStale(Path parent, String base) throws IOException {
		List<Path> stale = new ArrayList<>();
		for (String kind : new String[] {"tmp", "old"}) {
			for (Path p : leftovers(parent, base, kind)) {
				if (!alive(pidOf(p))) {
					stale.add(p);
				}
			}
		}
		for (Path p : stale) {
			deleteTree(p);
		}
	}

	private static List<Path> leftovers(Path parent, String base, String kind) throws IOException {
		List<Path> out = new ArrayList<>();
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(parent, p -> p.getFileName().toString().startsWith(base + "."))) {
			for (Path p : ds) {
				Matcher m = LEFTOVER.matcher(p.getFileName().toString().substring(base.length()));
				if (m.matches() && m.group(1).equals(kind)) {
					out.add(p);
				}
			}
		}
		return out;
	}

	private static long pidOf(Path leftover) {
		String n = leftover.getFileName().toString();
		return Long.parseLong(n.substring(n.lastIndexOf('-') + 1));
	}

	private static boolean alive(long pid) {
		return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
	}

	public static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
				if (exc != null) {
					throw exc;
				}
				Files.delete(d);
				return FileVisitResult.CONTINUE;
			}
		});
	}
}
