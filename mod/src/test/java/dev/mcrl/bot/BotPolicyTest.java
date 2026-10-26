package dev.mcrl.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mcrl.natives.McrlNative;
import dev.mcrl.natives.NativeBoot;
import dev.mcrl.testkit.PolicyFiles;

class BotPolicyTest {
	@TempDir
	Path dir;

	@BeforeAll
	static void boot() {
		NativeBoot.ensureStarted();
	}

	private void rejected(Path file, String contains) {
		BotPolicyException e = assertThrows(BotPolicyException.class, () -> BotPolicy.read(file));
		assertTrue(e.getMessage().startsWith("botPolicy: "), e.getMessage());
		assertTrue(e.getMessage().contains(contains), e.getMessage() + " should contain " + contains);
		assertTrue(e.getMessage().contains(file.toString()), e.getMessage() + " should name the file");
	}

	@Test
	void rejects() throws Exception {
		rejected(PolicyFiles.write(dir.resolve("in92.lt"), PolicyFiles.net(new int[] {92, 4, 90}, null), false), "89");
		rejected(PolicyFiles.write(dir.resolve("out91.lt"), PolicyFiles.net(new int[] {89, 4, 91}, null), false), "90");
		List<float[]> nan = PolicyFiles.net(new int[] {89, 4, 90}, null);
		nan.get(1)[2] = Float.NaN;
		rejected(PolicyFiles.write(dir.resolve("nan.lt"), nan, false), "finite");
		List<float[]> gap = PolicyFiles.net(new int[] {89, 4, 90}, null);
		gap.set(1, null);
		Path holes = dir.resolve("gap.lt");
		try (OutputStream os = Files.newOutputStream(holes); ZipOutputStream z = new ZipOutputStream(os)) {
			for (int i = 0; i < gap.size(); i++) {
				if (gap.get(i) != null) {
					PolicyFiles.put(z, "archive/data/" + i, new byte[4 * gap.get(i).length], false);
				}
			}
		}
		rejected(holes, "data/1");
		Path text = Files.writeString(dir.resolve("notes.txt"), "not a checkpoint");
		rejected(text, "not a zip");
		Path missing = dir.resolve("nowhere/POLICY.lt");
		rejected(missing, missing.toString());

		List<float[]> wide = List.of(new float[89], new float[65536], new float[0], new float[65536], new float[0],
				new float[90]);
		rejected(PolicyFiles.write(dir.resolve("overflow.lt"), wide, false), "4096");
		List<float[]> zero = PolicyFiles.net(new int[] {89, 4, 90}, null);
		zero.add(2, new float[0]);
		zero.add(3, new float[0]);
		zero.set(4, new float[0]);
		rejected(PolicyFiles.write(dir.resolve("zero.lt"), zero, false), "4096");
		for (String index : new String[] {"99999999999999999999", "2147483647", "200000000", "64"}) {
			Path wild = dir.resolve("index" + index + ".lt");
			try (OutputStream os = Files.newOutputStream(wild); ZipOutputStream z = new ZipOutputStream(os)) {
				PolicyFiles.put(z, "archive/data/0", new byte[4 * 89 * 90], false);
				PolicyFiles.put(z, "archive/data/" + index, new byte[4 * 90], false);
			}
			rejected(wild, "data/" + index);
		}
		Path twice = dir.resolve("twice.lt");
		try (OutputStream os = Files.newOutputStream(twice); ZipOutputStream z = new ZipOutputStream(os)) {
			PolicyFiles.put(z, "archive/data/0", new byte[4 * 89 * 90], false);
			PolicyFiles.put(z, "archive/data/1", new byte[4 * 90], false);
			PolicyFiles.put(z, "archive/data/01", new byte[4 * 90], false);
		}
		rejected(twice, "twice");
		Path bomb = dir.resolve("bomb.lt");
		try (OutputStream os = Files.newOutputStream(bomb); ZipOutputStream z = new ZipOutputStream(os)) {
			z.putNextEntry(new ZipEntry("archive/data/0"));
			byte[] chunk = new byte[1 << 20];
			for (int i = 0; i < 64; i++) {
				z.write(chunk);
			}
			z.closeEntry();
			PolicyFiles.put(z, "archive/data/1", new byte[4 * 4], true);
			PolicyFiles.put(z, "archive/data/2", new byte[4 * 4 * 90], true);
			PolicyFiles.put(z, "archive/data/3", new byte[4 * 90], true);
		}
		rejected(bomb, "89");
		Path bigBias = dir.resolve("bigbias.lt");
		try (OutputStream os = Files.newOutputStream(bigBias); ZipOutputStream z = new ZipOutputStream(os)) {
			PolicyFiles.put(z, "archive/data/0", new byte[4 * 89], true);
			z.putNextEntry(new ZipEntry("archive/data/1"));
			byte[] chunk = new byte[1 << 20];
			for (int i = 0; i < 64; i++) {
				z.write(chunk);
			}
			z.closeEntry();
		}
		rejected(bigBias, "4096");
	}

	@Test
	void newestUnreadableFallsBack() throws Exception {
		Files.createDirectories(dir.resolve("100"));
		PolicyFiles.write(dir.resolve("100/POLICY.lt"), PolicyFiles.net(new int[] {89, 4, 90}, new Random(5)), false);
		Files.createDirectories(dir.resolve("200"));
		Files.write(dir.resolve("200/POLICY.lt"), new byte[] {'P', 'K', 3, 4, 0, 0});
		assertEquals(List.of(dir.resolve("200/POLICY.lt"), dir.resolve("100/POLICY.lt")), BotPolicy.candidates(dir));
		BotPolicies.Loaded l = BotPolicies.load(dir.toString());
		assertEquals(dir.resolve("100/POLICY.lt").toRealPath(), l.file());
		String status = BotPolicies.describe(dir.toString());
		assertTrue(status.startsWith("100/POLICY.lt: 89-4-90") && status.contains("200/POLICY.lt"), status);
		Files.delete(dir.resolve("100/POLICY.lt"));
		BotPolicyException e = assertThrows(BotPolicyException.class, () -> BotPolicies.load(dir.toString()));
		assertTrue(e.getMessage().contains("200/POLICY.lt"), "the newest's reason: " + e.getMessage());
	}

	private static double[] javaForward(BotPolicy p, float[] obs) {
		double[] x = new double[obs.length];
		for (int i = 0; i < obs.length; i++) {
			x[i] = obs[i];
		}
		int[] d = p.dims();
		for (int l = 0; l + 1 < d.length; l++) {
			float[] w = p.params()[2 * l], b = p.params()[2 * l + 1];
			double[] y = new double[d[l + 1]];
			for (int o = 0; o < y.length; o++) {
				double s = b[o];
				for (int i = 0; i < d[l]; i++) {
					s += (double) w[o * d[l] + i] * x[i];
				}
				y[o] = l + 2 < d.length ? Math.max(0, s) : s;
			}
			x = y;
		}
		return x;
	}

	@Test
	void nativeForwardMatchesJava() throws Exception {
		BotPolicy p = BotPolicy.read(PolicyFiles.write(dir.resolve("POLICY.lt"), PolicyFiles.net(new int[] {89, 8, 8, 90}, new Random(1)),
				false));
		MemorySegment h = McrlNative.botPolicyCreate(p.dims(), p.params());
		try {
			Random r = new Random(9);
			for (int k = 0; k < 5; k++) {
				float[] obs = new float[89], logits = new float[90];
				for (int i = 0; i < obs.length; i++) {
					obs[i] = (float) (r.nextDouble() * 2 - 1);
				}
				McrlNative.botPolicyForward(h, obs, logits);
				double[] want = javaForward(p, obs);
				for (int i = 0; i < 90; i++) {
					assertEquals(want[i], logits[i], 1e-4, "observation " + k + ", logit " + i);
				}
			}
		} finally {
			McrlNative.botPolicyFree(h);
		}
	}
}
