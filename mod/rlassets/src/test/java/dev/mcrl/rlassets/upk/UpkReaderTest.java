package dev.mcrl.rlassets.upk;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpkReaderTest {
	@TempDir
	Path tmp;

	static UpkFixtureWriter small() {
		UpkFixtureWriter w = new UpkFixtureWriter("Small_SF");
		for (String n : List.of("None", "Core", "Package", "Class", "Obj")) {
			w.name(n);
		}
		int core = w.imp("Core", "Package", 0, "Core");
		int cls = w.imp("Core", "Class", core, "Obj");
		int a = w.exp(cls, 0, "Obj", new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
		int b = w.exp(cls, a, "Obj", new byte[] {9, 10});
		w.export(b).number = 3;
		w.export(b).superIndex = a;
		w.export(b).archetype = cls;
		w.export(b).objectFlags = 0x8000_0000_0000_0001L;
		w.export(b).exportFlags = 0x8000_0001;
		w.exp(0, 0, "Core");
		return w;
	}

	@Test
	void reads_summary_names_imports_exports() throws IOException {
		UpkFixtureWriter w = small();
		UpkReader r = UpkReader.open(w.write(tmp));
		assertEquals("Small_SF", r.packageName());
		assertEquals(new UpkReader.Summary(868, 34, w.headersSize, "None", 0x2000_0008, 5, w.nameOffset, 3, w.exportOffset, 2,
				w.importOffset, 10897, 136), r.summary());
		assertEquals(List.of("None", "Core", "Package", "Class", "Obj"), r.names());
		assertEquals(List.of(new UpkReader.Import("Core", "Package", 0, "Core"), new UpkReader.Import("Core", "Class", -1, "Obj")),
				r.imports());
		int bodies = w.headersSize;
		assertEquals(List.of(new UpkReader.Export(-2, 0, 0, "Obj", 0, 0x0007_0004_0000_0000L, 8, bodies, 0),
				new UpkReader.Export(-2, 1, 1, "Obj_2", -2, 0x8000_0000_0000_0001L, 2, bodies + 8, 0x8000_0001),
				new UpkReader.Export(0, 0, 0, "Core", 0, 0x0007_0004_0000_0000L, 0, bodies + 10, 0)), r.exports());
		ByteBuffer b = r.body(1);
		assertEquals(ByteOrder.LITTLE_ENDIAN, b.order());
		assertEquals(0x0403_0201, b.getInt(0));
		assertEquals(8, b.limit());
		ByteBuffer b2 = r.body(2);
		assertEquals(2, b2.remaining());
		assertEquals(9, b2.get(0));
		assertEquals(0, r.body(3).remaining());
	}

	@Test
	void bad_magic_and_truncation_throw() throws IOException {
		UpkFixtureWriter w = small();
		byte[] good = w.bytes();
		byte[] bad = good.clone();
		bad[0] ^= 1;
		assertOpenFails("Bad_SF", bad, "tag", "offset 0");
		assertOpenFails("Cut_SF", Arrays.copyOf(good, w.nameOffset - 16), "summary", "offset");
		assertOpenFails("Cut_SF", Arrays.copyOf(good, w.nameOffset + 6), "name", "offset");
		assertOpenFails("Cut_SF", Arrays.copyOf(good, w.exportOffset + 30), "export", "offset");
		assertOpenFails("Cut_SF", new byte[2], "summary", "offset 0");
		Path p = tmp.resolve("Short_SF.bin");
		Files.write(p, Arrays.copyOf(good, good.length - 1));
		UpkReader r = UpkReader.open(p);
		IOException e = assertThrows(IOException.class, () -> r.body(2));
		assertTrue(e.getMessage().contains("Short_SF") && e.getMessage().contains("offset"), e.getMessage());
	}

	@Test
	void a_count_the_image_cannot_hold_throws() throws IOException {
		UpkFixtureWriter w = small();
		byte[] good = w.bytes();
		int[][] counts = {{5, w.nameOffset}, {3, w.exportOffset}, {2, w.importOffset}};
		String[] tables = {"name", "export", "import"};
		ByteBuffer g = ByteBuffer.wrap(good).order(ByteOrder.LITTLE_ENDIAN);
		for (int t = 0; t < 3; t++) {
			int at = -1;
			for (int i = 0; i + 8 <= good.length && at < 0; i++) {
				if (g.getInt(i) == counts[t][0] && g.getInt(i + 4) == counts[t][1]) {
					at = i;
				}
			}
			assertTrue(at > 0, tables[t]);
			byte[] bad = good.clone();
			ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(at, Integer.MAX_VALUE);
			assertOpenFails("Huge_SF", bad, tables[t] + " table of " + Integer.MAX_VALUE + " entries", "offset");
		}
	}

	@Test
	void a_reference_out_of_range_throws() throws IOException {
		UpkFixtureWriter w = small();
		w.export(2).outer = 9;
		Path p = tmp.resolve("Ref_SF.bin");
		Files.write(p, w.bytes());
		IOException e = assertThrows(IOException.class, () -> UpkReader.open(p));
		assertTrue(e.getMessage().contains("Ref_SF") && e.getMessage().contains("offset"), e.getMessage());
	}

	private void assertOpenFails(String pkg, byte[] image, String... words) throws IOException {
		Path p = tmp.resolve(pkg + ".bin");
		Files.write(p, image);
		IOException e = assertThrows(IOException.class, () -> UpkReader.open(p));
		assertTrue(e.getMessage().contains(pkg), e.getMessage());
		for (String word : words) {
			assertTrue(e.getMessage().contains(word), e.getMessage());
		}
	}
}
