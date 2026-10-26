package dev.mcrl.rlassets.bodies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;

import dev.mcrl.rlassets.CacheWriter;
import dev.mcrl.rlassets.CacheWriterTest;
import dev.mcrl.rlassets.McrmTest;
import dev.mcrl.rlassets.MeshData;
import dev.mcrl.rlassets.WheelRig;

class BodyCacheWriterTest {
	@TempDir
	Path tmp;

	private Path cache() throws Exception {
		Path cache = tmp.resolve("rl-cache/v2");
		CacheWriter.write(cache, CacheWriterTest.content(List.of()));
		return cache;
	}

	static BodyManifest manifest(String id, int preset, String materialsFile) {
		Map<String, String> meshes = new LinkedHashMap<>();
		for (String m : BodyManifest.MESHES) {
			meshes.put(m, "meshes/" + m + ".mcrm");
		}
		float[][] disc = {{1, 0, 1}, {1, 0, -1}, {-1, 0, 1}, {-1, 0, -1}};
		float[][] pivot = {{1, 0, 1}, {1, 0, -1}, null, null};
		WheelRig rig = new WheelRig(disc, pivot, new double[] {-6, -6, -4, -4}, 0, 0, 12, 10);
		return new BodyManifest(id, BodyIds.label(id), preset, "fp", meshes, materialsFile, "MIC_" + id, id
				+ "_Chassis", null, null, null, Map.of("RocketBoost", new float[] {-1, 0.2f, 0}), rig, new JsonObject());
	}

	private Map<String, MeshData> meshes() {
		Map<String, MeshData> m = new LinkedHashMap<>();
		BodyManifest.MESHES.forEach(n -> m.put(n, McrmTest.sample()));
		return m;
	}

	private int files;

	private Path materials(String body) throws Exception {
		Path f = tmp.resolve("materials-" + files++ + ".json");
		Files.writeString(f, body);
		return f;
	}

	@Test
	void failedWriteKeepsTheOldBody() throws Exception {
		Path cache = cache();
		Path good = materials("{\"materials\": {}, \"textures\": {}}");
		BodyCacheWriter.write(cache, manifest("Body_A", 3, "materials.json"), meshes(), Map.of("materials.json", good),
				CacheWriter.Guard.standard(null));
		Map<String, String> before = CacheWriterTest.snapshot(BodyIds.dir(cache, "Body_A"));
		CacheWriter.ValidationException e = assertThrows(CacheWriter.ValidationException.class,
				() -> BodyCacheWriter.write(cache, manifest("Body_A", 4, "materials.json"), meshes(),
						Map.of("materials.json", materials("not json at all")), CacheWriter.Guard.standard(null)));
		assertTrue(e.getMessage().contains("materials.json"), e.getMessage());
		assertEquals(before, CacheWriterTest.snapshot(BodyIds.dir(cache, "Body_A")));
		assertThrows(java.io.IOException.class, () -> BodyCacheWriter.write(cache, manifest("Body_A", 4,
				"materials.json"), meshes(), Map.of("../outside.json", good), CacheWriter.Guard.standard(null)),
				"a file outside the body dir");
		assertFalse(Files.exists(cache.resolve("bodies/outside.json")));
		assertEquals(before, CacheWriterTest.snapshot(BodyIds.dir(cache, "Body_A")));
	}

	@Test
	void waitsForTheLock() throws Exception {
		Path cache = cache();
		Path mats = materials("{\"materials\": {}, \"textures\": {}}");
		ExecutorService ex = Executors.newFixedThreadPool(2);
		CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
		try {
			Future<?> holder = ex.submit(() -> {
				try (CacheWriter.CacheLock l = CacheWriter.lock(cache)) {
					locked.countDown();
					release.await();
				}
				return null;
			});
			assertTrue(locked.await(10, TimeUnit.SECONDS));
			Future<?> write = ex.submit(() -> BodyCacheWriter.write(cache, manifest("Body_A", 3, "materials.json"),
					meshes(), Map.of("materials.json", mats), CacheWriter.Guard.standard(null)));
			Thread.sleep(200);
			assertFalse(write.isDone(), "the write did not wait for the lock");
			release.countDown();
			holder.get(10, TimeUnit.SECONDS);
			write.get(10, TimeUnit.SECONDS);
			assertTrue(Files.isRegularFile(BodyIds.dir(cache, "Body_A").resolve("manifest.json")));
		} finally {
			ex.shutdownNow();
		}
	}

	@Test
	void indexRefusesWhatOtherWritersRefuse() throws Exception {
		BodyIndex index = new BodyIndex("fp", List.of());
		Path mc = tmp.resolve("minecraft");
		Path inMc = mc.resolve("caches/v2");
		CacheWriter.write(inMc, CacheWriterTest.content(List.of()), new CacheWriter.Guard(tmp.resolve("elsewhere"),
				null));
		Files.delete(mc.resolve("caches/v2.lock"));
		assertThrows(CacheWriter.TargetRefusedException.class, () -> BodyCacheWriter.writeIndex(inMc, index,
				new CacheWriter.Guard(mc, null)));
		assertFalse(Files.exists(inMc.resolve("bodies")));
		assertFalse(Files.exists(mc.resolve("caches/v2.lock")));
		Path stray = Files.createDirectories(tmp.resolve("stray"));
		assertThrows(CacheWriter.TargetRefusedException.class, () -> BodyCacheWriter.writeIndex(stray, index,
				CacheWriter.Guard.standard(null)));
		assertFalse(Files.exists(stray.resolve("bodies")));
		assertFalse(Files.exists(tmp.resolve("stray.lock")));
	}
}
