package dev.mcrl.client.rl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mcrl.client.config.McrlConfig.Visuals;
import dev.mcrl.rlassets.Converter;
import dev.mcrl.rlassets.LocateResult;
import dev.mcrl.rlassets.McrlPaths;
import dev.mcrl.rlassets.RlState;

class RlConversionTest {
	@TempDir
	Path tmp;

	private final class FakeHost implements RlConversion.Host {
		final Path cache = tmp.resolve("cache");
		boolean enabled = true;
		LocateResult loc = new LocateResult(LocateResult.State.NOT_FOUND, null, null, "none", "Rocket League was not found");
		int converts;

		@Override
		public String affected() {
			return RlConversion.affected(Visuals.RL, 1f, false);
		}

		@Override
		public Path umodelBinary() {
			return tmp.resolve("tools/umodel");
		}

		@Override
		public Path vgmstreamBinary() {
			return tmp.resolve("tools/vgmstream-cli");
		}

		@Override
		public Path cacheDir() {
			return cache;
		}

		@Override
		public boolean conversionEnabled() {
			return enabled;
		}

		@Override
		public LocateResult locate() {
			return loc;
		}

		@Override
		public String decoder(LocateResult l) {
			return null;
		}

		@Override
		public RlState.Failed failedConversion() {
			return null;
		}

		@Override
		public void rememberFailedConversion(RlState.Failed f) {
		}

		@Override
		public Converter.ConvertResult convert(LocateResult l, Path dir, Consumer<String> progress) {
			converts++;
			progress.accept("Exporting Startup.upk Body_Octane_SK");
			throw new IllegalStateException("converter bug");
		}

		@Override
		public Converter.ConvertResult convertStadium(LocateResult l, Path dir, Consumer<String> progress) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void stadiumConverted() {
		}

		@Override
		public RlCache load(Path dir) throws Exception {
			return RlCache.read(dir);
		}
	}

	private static RlConversion conversion(FakeHost host) {
		return new RlConversion(host, Runnable::run);
	}

	@Test
	void a_configured_dir_with_an_older_cache_converts_beside_it() throws Exception {
		Path v1 = tmp.resolve("caches/v1"), mine = tmp.resolve("caches/mine"), mineV2 = tmp.resolve("caches/mine-v2");
		assertEquals(v1, McrlPaths.configuredCacheDir(v1), "nothing there: the dir itself");
		RlCacheFixtures.writeCache(v1, "fp", tmp.resolve("s1"));
		assertEquals(v1, McrlPaths.configuredCacheDir(v1), "a current cache: the dir itself");
		Path m = v1.resolve("manifest.json");
		Files.writeString(m, Files.readString(m).replace("\"format\": 2", "\"format\": 1"));
		assertEquals(tmp.resolve("caches/v2"), McrlPaths.configuredCacheDir(v1), "v2 beside v1");

		RlCacheFixtures.writeCache(mine, "fp", tmp.resolve("s2"));
		m = mine.resolve("manifest.json");
		Files.writeString(m, Files.readString(m).replace("\"format\": 2", "\"format\": 1"));
		assertEquals(mineV2, McrlPaths.configuredCacheDir(mine));
		RlCacheFixtures.writeCache(mineV2, "fp", tmp.resolve("s3"));
		try (Stream<Path> w = Files.walk(mine)) {
			for (Path f : w.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(f);
			}
		}
		assertEquals(mineV2, McrlPaths.configuredCacheDir(mine), "the older cache gone: still the sibling");
	}

	@Test
	void a_throwing_converter_falls_back_to_simple_with_one_message() throws Exception {
		FakeHost host = new FakeHost();
		host.loc = RlCacheFixtures.foundInstall(tmp.resolve("rl"));
		RlConversion c = conversion(host);
		c.start();
		assertEquals(1, host.converts);
		assertFallback(c, true, "converter bug");
	}

	private static void assertFallback(RlConversion c, boolean announced, String why) {
		assertEquals(Visuals.SIMPLE, c.effective(Visuals.RL));
		assertNull(c.cache());
		List<String> said = new ArrayList<>(c.takeMessages());
		if (announced) {
			assertFalse(said.isEmpty(), "nothing said");
			assertEquals(RlConversion.CONVERTING, said.remove(0));
		}
		assertEquals(1, said.size(), "one message: " + said);
		assertTrue(said.get(0).startsWith(RlConversion.UNAVAILABLE), said.get(0));
		assertTrue(said.get(0).contains("SIMPLE"), said.get(0));
		assertTrue(said.get(0).contains(why), "'" + said.get(0) + "' does not say " + why);
		c.start();
		assertTrue(c.takeMessages().isEmpty(), "the reason is said once");
	}

	@Test
	void an_interrupted_cache_swap_is_restored_before_the_cache_loads() throws Exception {
		FakeHost host = new FakeHost();
		host.enabled = false;
		Path old = tmp.resolve("cache.old-4194305");
		RlCacheFixtures.writeCache(tmp.resolve("staging"), "the-cache-before-the-swap", tmp);
		Files.move(tmp.resolve("staging"), old);
		RlConversion c = conversion(host);
		c.start();
		assertNotNull(c.cache(), c.takeMessages().toString());
		assertEquals("the-cache-before-the-swap", c.cache().fingerprint());
		assertTrue(Files.isRegularFile(host.cache.resolve("manifest.json")));
		assertFalse(Files.exists(old));
		assertTrue(c.takeMessages().isEmpty());
	}
}
