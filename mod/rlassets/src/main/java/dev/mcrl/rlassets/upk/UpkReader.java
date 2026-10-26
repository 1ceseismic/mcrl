package dev.mcrl.rlassets.upk;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

public final class UpkReader {
	public static final int TAG = 0x9E2A83C1; // UE3 PACKAGE_FILE_TAG
	private static final int NAME_MIN = 4 + 8, IMPORT_MIN = 3 * 8 + 4;
	private static final int EXPORT_MIN = 3 * 4 + 8 + 4 + 8 + 4 + 8 + 4 + 4 + 16 + 4;

	public record Summary(int fileVersion, int licenseeVersion, int headersSize, String folder, int packageFlags,
			int nameCount, int nameOffset, int exportCount, int exportOffset, int importCount, int importOffset,
			int engineVersion, int cookerVersion) {
	}

	public record Import(String classPackage, String className, int outer, String objectName) {
	}

	public record Export(int classIndex, int superIndex, int outer, String objectName, int archetype, long objectFlags,
			int serialSize, long serialOffset, int exportFlags) {
	}

	private final String packageName;
	private final ByteBuffer data;
	private final Summary summary;
	private final List<String> names;
	private final List<Import> imports;
	private final List<Export> exports;
	private @Nullable Map<String, List<Integer>> byName;

	private UpkReader(String packageName, ByteBuffer data) throws IOException {
		this.packageName = packageName;
		this.data = data;
		Cursor c = new Cursor("summary", 0);
		int tag = c.i32();
		if (tag != TAG) {
			throw new IOException(String.format("%s: bad package tag 0x%08X at offset 0", packageName, tag));
		}
		int version = c.i32(), headersSize = c.i32();
		String folder = c.fstring();
		int packageFlags = c.i32();
		int nameCount = c.count(), nameOffset = c.i32(), exportCount = c.count(), exportOffset = c.i32();
		int importCount = c.count(), importOffset = c.i32();
		c.skip(5 * 4); // DependsOffset, ImportExportGuidsOffset, ImportGuidsCount, ExportGuidsCount, ThumbnailTableOffset
		c.skip(16); // Guid
		c.skip(12L * c.count()); // generations
		int engineVersion = c.i32(), cookerVersion = c.i32();
		c.skip(4); // CompressionFlags
		c.skip(16L * c.count()); // compressed chunks
		c.skip(4); // PackageSource
		for (int i = c.count(); i > 0; i--) {
			c.fstring(); // AdditionalPackagesToCook
		}
		for (int i = c.count(); i > 0; i--) { // texture allocations
			c.skip(20);
			c.skip(4L * c.count());
		}
		c.skip(3 * 4); // RL: GarbageSize, CompressedChunkInfoOffset, LastBlockSize
		summary = new Summary(version & 0xFFFF, version >>> 16, headersSize, folder, packageFlags, nameCount, nameOffset,
				exportCount, exportOffset, importCount, importOffset, engineVersion, cookerVersion);

		c = new Cursor("name table", nameOffset);
		c.fits(nameCount, NAME_MIN);
		List<String> n = new ArrayList<>(nameCount);
		for (int i = 0; i < nameCount; i++) {
			n.add(c.fstring());
			c.skip(8); // flags
		}
		names = Collections.unmodifiableList(n);

		c = new Cursor("import table", importOffset);
		c.fits(importCount, IMPORT_MIN);
		List<Import> im = new ArrayList<>(importCount);
		for (int i = 0; i < importCount; i++) {
			int at = c.pos;
			String classPackage = c.fname(), className = c.fname();
			int outer = c.i32();
			String objectName = c.fname();
			im.add(new Import(classPackage, className, ref(outer, importCount, exportCount, "import", i + 1, at),
					objectName));
		}
		imports = Collections.unmodifiableList(im);

		c = new Cursor("export table", exportOffset);
		c.fits(exportCount, EXPORT_MIN);
		List<Export> ex = new ArrayList<>(exportCount);
		for (int i = 0; i < exportCount; i++) {
			int at = c.pos;
			int classIndex = c.i32(), superIndex = c.i32(), outer = c.i32();
			String objectName = c.fname();
			int archetype = c.i32();
			long objectFlags = c.i64();
			int serialSize = c.i32();
			long serialOffset = c.i64();
			int exportFlags = c.i32();
			c.skip(4L * c.count()); // net objects
			c.skip(16 + 4); // package Guid, PackageFlags
			for (int r : new int[] {classIndex, superIndex, outer, archetype}) {
				ref(r, importCount, exportCount, "export", i + 1, at);
			}
			ex.add(new Export(classIndex, superIndex, outer, objectName, archetype, objectFlags, serialSize, serialOffset,
					exportFlags));
		}
		exports = Collections.unmodifiableList(ex);
	}

	public static UpkReader open(Path bin) throws IOException {
		String file = bin.getFileName().toString();
		int dot = file.lastIndexOf('.');
		String name = dot > 0 ? file.substring(0, dot) : file;
		try (FileChannel ch = FileChannel.open(bin, StandardOpenOption.READ)) {
			if (ch.size() > Integer.MAX_VALUE) {
				throw new IOException(name + ": image of " + ch.size() + " bytes is over 2 GiB");
			}
			ByteBuffer data = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.LITTLE_ENDIAN);
			return new UpkReader(name, data);
		}
	}

	public String packageName() {
		return packageName;
	}

	public Summary summary() {
		return summary;
	}

	public List<String> names() {
		return names;
	}

	public List<Import> imports() {
		return imports;
	}

	public List<Export> exports() {
		return exports;
	}

	public String fname(int index, int number) {
		String n = index >= 0 && index < names.size() ? names.get(index) : "?" + index;
		return number > 0 ? n + "_" + (number - 1) : n;
	}

	public String objectPath(int ref) {
		if (ref == 0) {
			return "None";
		}
		Deque<String> parts = new ArrayDeque<>();
		int limit = imports.size() + exports.size();
		for (int r = ref; r != 0; r = r < 0 ? imports.get(-r - 1).outer() : exports.get(r - 1).outer()) {
			if (parts.size() > limit) {
				throw new IllegalStateException(packageName + ": the outers of " + ref + " form a loop");
			}
			parts.addFirst(r < 0 ? imports.get(-r - 1).objectName() : exports.get(r - 1).objectName());
		}
		return String.join(".", parts);
	}

	public String className(int ref) {
		if (ref == 0) {
			return "Class";
		}
		return ref < 0 ? imports.get(-ref - 1).objectName() : exports.get(ref - 1).objectName();
	}

	public ByteBuffer body(int exportIndex) throws IOException {
		Export e = exports.get(exportIndex - 1);
		long end = e.serialOffset() + e.serialSize();
		if (e.serialSize() < 0 || e.serialOffset() < 0 || end > data.capacity()) {
			throw new IOException(packageName + ": export " + exportIndex + " (" + objectPath(exportIndex) + ") body of "
					+ e.serialSize() + " bytes at offset " + e.serialOffset() + " runs past the end of the image ("
					+ data.capacity() + " bytes)");
		}
		return data.slice((int) e.serialOffset(), e.serialSize()).order(ByteOrder.LITTLE_ENDIAN);
	}

	public List<Integer> find(String nameOrPath) {
		if (byName == null) {
			Map<String, List<Integer>> m = new HashMap<>();
			for (int i = 1; i <= exports.size(); i++) {
				String name = exports.get(i - 1).objectName();
				m.computeIfAbsent(name, k -> new ArrayList<>()).add(i);
				String path = objectPath(i);
				if (!path.equals(name)) {
					m.computeIfAbsent(path, k -> new ArrayList<>()).add(i);
				}
			}
			byName = m;
		}
		List<Integer> hits = byName.get(nameOrPath);
		return hits == null ? List.of() : Collections.unmodifiableList(hits);
	}

	private int ref(int r, int importCount, int exportCount, String table, int entry, int at) throws IOException {
		if (r < -importCount || r > exportCount) {
			throw new IOException(packageName + ": " + table + " " + entry + " at offset " + at + " refers to " + r
					+ ", outside -" + importCount + ".." + exportCount);
		}
		return r;
	}

	private final class Cursor {
		final String what;
		int pos;

		Cursor(String what, int pos) {
			this.what = what;
			this.pos = pos;
		}

		void need(long n) throws IOException {
			if (pos < 0 || n < 0 || pos + n > data.capacity()) {
				throw new IOException(packageName + ": " + what + " truncated at offset " + pos + " (" + n
						+ " more bytes needed, the image has " + data.capacity() + ")");
			}
		}

		int i32() throws IOException {
			need(4);
			int v = data.getInt(pos);
			pos += 4;
			return v;
		}

		long i64() throws IOException {
			need(8);
			long v = data.getLong(pos);
			pos += 8;
			return v;
		}

		int count() throws IOException {
			int at = pos, n = i32();
			if (n < 0) {
				throw new IOException(packageName + ": " + what + " has count " + n + " at offset " + at);
			}
			return n;
		}

		void fits(int n, int minEntrySize) throws IOException {
			if (pos < 0 || (long) n * minEntrySize > data.capacity() - (long) pos) {
				throw new IOException(packageName + ": " + what + " of " + n + " entries at offset " + pos
						+ " does not fit in the " + data.capacity() + "-byte image");
			}
		}

		void skip(long n) throws IOException {
			need(n);
			pos += (int) n;
		}

		String fstring() throws IOException {
			int n = i32();
			if (n == 0) {
				return "";
			}
			long len = n > 0 ? n : -2L * n;
			need(len);
			String s = n > 0 ? new String(bytes(pos, n - 1), StandardCharsets.ISO_8859_1)
					: new String(bytes(pos, (int) len - 2), StandardCharsets.UTF_16LE);
			pos += (int) len;
			return s;
		}

		String fname() throws IOException {
			int at = pos, index = i32(), number = i32();
			if (index < 0 || index >= names.size()) {
				throw new IOException(packageName + ": " + what + " names " + index + " at offset " + at + ", outside the "
						+ names.size() + " names");
			}
			return UpkReader.this.fname(index, number);
		}

		private byte[] bytes(int at, int n) {
			byte[] b = new byte[n];
			data.get(at, b);
			return b;
		}
	}
}
