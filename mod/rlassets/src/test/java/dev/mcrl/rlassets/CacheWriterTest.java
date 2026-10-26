package dev.mcrl.rlassets;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcrl.rlassets.bodies.BodyCacheWriter;
import dev.mcrl.rlassets.bodies.BodyIndex;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class CacheWriterTest {
	@TempDir
	Path tmp;

	public static CacheWriter.Raw raw() {
		try {
			Path png = Files.createTempFile("mcrl-raw", ".png");
			png.toFile().deleteOnExit();
			ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", png.toFile());
			return new CacheWriter.Raw(Map.of(), JsonParser.parseString("{\"materials\": {}, \"textures\": {}}")
					.getAsJsonObject(), Map.of("pepe_body_d", png), Map.of());
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	public static CacheWriter.Content content(List<String> problems) {
		JsonObject alignment = new JsonObject();
		alignment.addProperty("maxResidualUU", 0.27);
		return new CacheWriter.Content("Startup.upk=1@2", "test", Map.of(
				"ball", new CacheWriter.MeshEntry(McrmTest.sample(), "ball"),
				"chassis", new CacheWriter.MeshEntry(McrmTest.sample(), "chassis")),
				Map.of("RocketBoost", new float[] {-0.96f, 0.18f, 0}), Map.of(), alignment, problems, raw());
	}

	List<String> siblings() throws IOException {
		try (Stream<Path> s = Files.list(tmp.resolve("rl-cache"))) {
			return s.map(p -> p.getFileName().toString()).filter(n -> !n.endsWith(".lock")).sorted().toList();
		}
	}

	@Test
	void convert_failure_leaves_no_cache() throws IOException {
		Path v1 = tmp.resolve("rl-cache/v1");
		Files.createDirectories(v1.getParent());
		CacheWriter.Content bad = content(List.of("FL wheel residual 2.000 UU > 1 UU"));
		CacheWriter.ValidationException e = assertThrows(CacheWriter.ValidationException.class, () -> CacheWriter.write(v1, bad));
		assertTrue(e.getMessage().contains("FL wheel residual"), e.getMessage());
		assertFalse(Files.exists(v1), "no v1 written");
		assertEquals(List.of(), siblings(), "the temp dir is gone");

		Files.createDirectories(v1.resolve("meshes"));
		Files.writeString(v1.resolve("manifest.json"), "{\"format\":1,\"fingerprint\":\"old\"}");
		Files.writeString(v1.resolve("meshes/old.mcrm"), "old");
		assertThrows(CacheWriter.ValidationException.class, () -> CacheWriter.write(v1, bad));
		assertEquals("{\"format\":1,\"fingerprint\":\"old\"}", Files.readString(v1.resolve("manifest.json")));
		assertEquals("old", Files.readString(v1.resolve("meshes/old.mcrm")));
		assertEquals(List.of("v1"), siblings());
	}

	@Test
	void writesAndReplacesAtomically() throws Exception {
		Path v1 = tmp.resolve("rl-cache/v1");
		Files.createDirectories(v1);
		Files.writeString(v1.resolve("manifest.json"), "{\"format\": 0}");
		Files.writeString(v1.resolve("stale.txt"), "x");
		assertEquals(List.of(), CacheWriter.write(v1, content(List.of())));
		assertFalse(Files.exists(v1.resolve("stale.txt")), "the old cache was replaced as a whole");
		assertEquals(List.of("v1"), siblings());
		JsonObject m = JsonParser.parseString(Files.readString(v1.resolve("manifest.json"))).getAsJsonObject();
		assertEquals(2, m.get("format").getAsInt());
		assertEquals("Startup.upk=1@2", m.get("fingerprint").getAsString());
		assertEquals("test", m.get("rlVersion").getAsString());
		JsonObject ball = m.getAsJsonObject("meshes").getAsJsonObject("ball");
		assertEquals("meshes/ball.mcrm", ball.get("file").getAsString());
		assertEquals("ball", ball.get("texture").getAsString());
		assertEquals("{\"pepe_body_d\":\"textures/pepe_body_d.png\"}", m.getAsJsonObject("textures").toString());
		assertEquals("materials.json", m.get("materials").getAsString());
		assertEquals(-0.96f, m.getAsJsonObject("sockets").getAsJsonArray("RocketBoost").get(0).getAsFloat());
		assertTrue(m.getAsJsonObject("sounds").isEmpty());
		assertArrayEquals(McrmTest.sample().vertices(), Mcrm.read(v1.resolve("meshes/ball.mcrm")).vertices());
		try (Stream<Path> files = Files.list(v1.resolve("textures"))) {
			assertEquals(List.of("pepe_body_d.png"), files.map(f -> f.getFileName().toString()).toList(), "no body_blue.png");
		}
		assertEquals(0.27, JsonParser.parseString(Files.readString(v1.resolve("alignment.json"))).getAsJsonObject()
				.get("maxResidualUU").getAsDouble());
		assertEquals("Startup.upk=1@2", CacheWriter.fingerprintOf(v1));
		assertNull(CacheWriter.fingerprintOf(tmp.resolve("rl-cache/none")));
	}

	public static Map<String, String> snapshot(Path root) throws IOException {
		Map<String, String> m = new java.util.TreeMap<>();
		try (Stream<Path> s = Files.walk(root)) {
			for (Path p : s.toList()) {
				m.put(root.relativize(p).toString(), Files.isRegularFile(p)
						? new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.ISO_8859_1) : "<dir>");
			}
		}
		return m;
	}

	public static void tree(Path root, String... files) throws IOException {
		for (String f : files) {
			Files.createDirectories(root.resolve(f).getParent());
			Files.writeString(root.resolve(f), "keep " + f);
		}
	}

	@Test
	void refusesNonCacheTargetsAndLeavesThemUntouched() throws IOException {
		CacheWriter.Guard guard = new CacheWriter.Guard(tmp.resolve("home/.minecraft"), null);
		Path project = tmp.resolve("project");
		tree(project, "build.gradle", "src/main/java/A.java");
		Path share = tmp.resolve("home/.local/share/mcrl");
		tree(share, "tools/umodel", "work/umodel/notify.log", "rl-cache/v1/manifest.json");
		Path other = tmp.resolve("other");
		tree(other, "manifest.json");
		tree(tmp.resolve("home/.minecraft"), "options.txt");
		Map<String, String> before = snapshot(tmp);
		for (Path target : List.of(project, share, other, tmp.resolve("home/.minecraft"), tmp.resolve("home/.minecraft/mcrl/v1"),
				tmp.resolve("home/.minecraft/saves"), tmp.resolve("project/build.gradle"))) {
			CacheWriter.TargetRefusedException e = assertThrows(CacheWriter.TargetRefusedException.class,
					() -> CacheWriter.write(target, content(List.of()), guard), target.toString());
			assertTrue(e.getMessage().contains(target.getFileName().toString()), e.getMessage());
		}
		Path link = Files.createSymbolicLink(tmp.resolve("link"), tmp.resolve("home/.minecraft"));
		assertThrows(CacheWriter.TargetRefusedException.class, () -> CacheWriter.write(link.resolve("v1"), content(List.of()), guard));
		Files.delete(link);
		assertEquals(before, snapshot(tmp), "nothing created, changed or deleted");
	}

	@Test
	void refusesTheRlInstallsVolume() throws IOException {
		Path install = Files.createDirectories(tmp.resolve("rl"));
		CacheWriter.Guard guard = new CacheWriter.Guard(tmp.resolve(".minecraft"), install);
		Path v1 = tmp.resolve("rl-cache/v1");
		CacheWriter.TargetRefusedException e = assertThrows(CacheWriter.TargetRefusedException.class,
				() -> CacheWriter.write(v1, content(List.of()), guard));
		assertTrue(e.getMessage().contains("volume"), e.getMessage());
		assertFalse(Files.exists(tmp.resolve("rl-cache")));
	}

	@Test
	void interruptedSwapIsRestoredBeforeAnything() throws IOException {
		Path older = tmp.resolve("rl-cache/v1.old-4194305");
		Path newer = tmp.resolve("rl-cache/v1.old-4194306");
		tree(older, "meshes/a.mcrm");
		tree(newer, "meshes/b.mcrm");
		Files.writeString(older.resolve("manifest.json"), "{\"format\": 2, \"fingerprint\": \"older\"}");
		Files.writeString(newer.resolve("manifest.json"), "{\"format\": 2, \"fingerprint\": \"newer\"}");
		Files.setLastModifiedTime(older, java.nio.file.attribute.FileTime.fromMillis(1_000_000_000_000L));
		Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.fromMillis(1_100_000_000_000L));
		Path v1 = tmp.resolve("rl-cache/v1");
		assertThrows(CacheWriter.ValidationException.class, () -> CacheWriter.write(v1, content(List.of("bad"))));
		assertEquals("newer", CacheWriter.fingerprintOf(v1));
		assertTrue(Files.isRegularFile(v1.resolve("meshes/b.mcrm")));
		assertEquals(List.of("v1"), siblings(), "the other leftover is removed as stale");
	}

	@Test
	void aFailedCarryPutsBackWhatItMoved() throws Exception {
		Path v2 = tmp.resolve("rl-cache/v2");
		CacheWriter.write(v2, content(List.of()));
		tree(v2, "arena/soccar/mesh_0.cmf", "stadium/y", "bodies/z");
		Files.write(v2.resolve("arena/soccar/mesh_0.cmf"), new byte[] {0, 1, 2, 0x7F});
		Files.writeString(v2.resolve("stale.txt"), "the old cache");
		Map<String, String> before = bytes(v2);
		CacheWriter.beforeCarry = name -> {
			if (name.toString().equals("stadium")) {
				throw new IOException("seam: stadium does not move");
			}
		};
		try {
			IOException e = assertThrows(IOException.class, () -> CacheWriter.write(v2, content(List.of())));
			assertEquals("seam: stadium does not move", e.getMessage());
		} finally {
			CacheWriter.beforeCarry = null;
		}
		assertEquals(before, bytes(v2), "the old cache byte for byte, arena/ included");
		assertEquals(List.of("v2"), siblings(), "no temp dir or kept subtree left");

		Files.move(v2.resolve("stadium"), v2.resolve("stadium.old-4194306"));
		CacheWriter.write(v2, content(List.of()));
		assertEquals(Map.of("", "<dir>", "y", before.get("stadium/y")), bytes(v2.resolve("stadium")));
		assertEquals(under(before, "arena"), bytes(v2.resolve("arena")));
		assertFalse(Files.exists(v2.resolve("stadium.old-4194306")));
	}

	private static Map<String, String> bytes(Path root) throws IOException {
		Map<String, String> m = new java.util.TreeMap<>();
		try (Stream<Path> s = Files.walk(root)) {
			for (Path p : s.toList()) {
				m.put(root.relativize(p).toString(), Files.isRegularFile(p)
						? java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(p)) : "<dir>");
			}
		}
		return m;
	}

	private static Map<String, String> under(Map<String, String> all, String sub) {
		Map<String, String> out = new java.util.TreeMap<>();
		all.forEach((k, v) -> {
			if (k.equals(sub)) {
				out.put("", v);
			} else if (k.startsWith(sub + "/")) {
				out.put(k.substring(sub.length() + 1), v);
			}
		});
		return out;
	}

	@Test
	void lockAcrossProcesses() throws Exception {
		Path v1 = tmp.resolve("rl-cache/v1");
		CacheWriter.write(v1, content(List.of()));
		Path ready = tmp.resolve("ready"), release = tmp.resolve("release");
		Process other = lockHolder(v1, ready, release);
		ExecutorService ex = Executors.newFixedThreadPool(3);
		try {
			waitFor(() -> Files.exists(ready), "the other process took the lock");
			List<Future<?>> writes = new ArrayList<>();
			writes.add(ex.submit(() -> CacheWriter.write(v1, content(List.of()))));
			writes.add(ex.submit(() -> CacheWriter.writeSubtree(v1, "stadium", CacheWriter.Guard.standard(null),
					d -> Files.writeString(d.resolve("s"), "s"))));
			writes.add(ex.submit(() -> {
				BodyCacheWriter.writeIndex(v1, new BodyIndex("fp", List.of()), CacheWriter.Guard.standard(null));
				return null;
			}));
			Thread.sleep(300);
			for (Future<?> f : writes) {
				assertFalse(f.isDone(), "a write went while the other process held the lock");
			}
			Files.writeString(release, "go");
			for (Future<?> f : writes) {
				f.get(30, TimeUnit.SECONDS);
			}
			assertTrue(other.waitFor(30, TimeUnit.SECONDS));
			assertEquals(0, other.exitValue());
			assertEquals("s", Files.readString(v1.resolve("stadium/s")));
			assertTrue(Files.isRegularFile(v1.resolve("bodies/index.json")));
		} finally {
			ex.shutdownNow();
			other.destroyForcibly();
		}

		Path ready2 = tmp.resolve("ready2"), release2 = tmp.resolve("release2");
		Files.writeString(release2, "go");
		Process second;
		try (CacheWriter.CacheLock held = CacheWriter.lock(v1)) {
			second = lockHolder(v1, ready2, release2);
			waitFor(() -> Files.exists(tmp.resolve("ready2.start")), "the other process started");
			Thread.sleep(300);
			assertFalse(Files.exists(ready2), "the other process took the lock this one holds");
		}
		try {
			assertTrue(second.waitFor(30, TimeUnit.SECONDS));
			assertEquals(0, second.exitValue());
			assertTrue(Files.exists(ready2), "the other process had the lock once this one let go");
		} finally {
			second.destroyForcibly();
		}
	}

	private static Process lockHolder(Path cache, Path ready, Path release) throws Exception {
		String javaBin = ProcessHandle.current().info().command().orElse("java");
		List<String> cp = new ArrayList<>();
		for (Class<?> c : new Class<?>[] {LockHolder.class, CacheWriter.class, com.google.gson.Gson.class}) {
			cp.add(Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
		}
		return new ProcessBuilder(javaBin, "-cp", String.join(java.io.File.pathSeparator, cp), LockHolder.class.getName(),
				cache.toString(), ready.toString(), release.toString()).redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
	}

	private static void waitFor(BooleanSupplier condition, String what) throws InterruptedException {
		long until = System.nanoTime() + 30_000_000_000L;
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > until) {
				fail("timed out waiting: " + what);
			}
			Thread.sleep(10);
		}
	}
}
