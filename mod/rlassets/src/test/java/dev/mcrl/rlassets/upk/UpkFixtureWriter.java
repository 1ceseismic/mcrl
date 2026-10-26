package dev.mcrl.rlassets.upk;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class UpkFixtureWriter {
	public final String packageName;
	public int version = 868 | 34 << 16;
	public String folder = "None";
	public int packageFlags = 0x2000_0008;
	public int engineVersion = 10897, cookerVersion = 136;
	final List<String> names = new ArrayList<>();
	final List<Imp> imports = new ArrayList<>();
	final List<Exp> exports = new ArrayList<>();
	private final Map<Integer, Script> script = new HashMap<>();
	private final Map<Integer, List<Integer>> members = new LinkedHashMap<>();
	private final Map<String, Integer> classImports = new HashMap<>();
	public int nameOffset, importOffset, exportOffset, headersSize;

	public UpkFixtureWriter(String packageName) {
		this.packageName = packageName;
	}

	public record Imp(String classPackage, String className, int outer, String name) {
	}

	public static final class Exp {
		public int classIndex, superIndex, outer, archetype;
		public String name;
		public int number;
		public long objectFlags = 0x0007_0004_0000_0000L;
		public int exportFlags;
		public int netObjects;
		public byte[] body = new byte[0];
		public Long serialOffset;
		public int serialSize;

		Exp(int classIndex, int outer, String name) {
			this.classIndex = classIndex;
			this.outer = outer;
			this.name = name;
		}
	}

	public int name(String s) {
		int i = names.indexOf(s);
		if (i >= 0) {
			return i;
		}
		names.add(s);
		return names.size() - 1;
	}

	public int imp(String classPackage, String className, int outer, String name) {
		name(classPackage);
		name(className);
		name(name);
		imports.add(new Imp(classPackage, className, outer, name));
		return -imports.size();
	}

	public int exp(int classIndex, int outer, String name) {
		name(name);
		exports.add(new Exp(classIndex, outer, name));
		return exports.size();
	}

	public int exp(int classIndex, int outer, String name, byte[] body) {
		int ref = exp(classIndex, outer, name);
		export(ref).body = body;
		return ref;
	}

	public Exp export(int ref) {
		return exports.get(ref - 1);
	}

	public Body body() {
		return new Body();
	}

	private sealed interface Script {
	}

	private record ClassScript(int superRef) implements Script {
	}

	private record StructScript(int superRef, int flags, Body defaults) implements Script {
	}

	private record PropScript(String type, int arrayDim, int sub) implements Script {
	}

	private record FunctionScript() implements Script {
	}

	private record EnumScript(List<String> values) implements Script {
	}

	public int classImport(String name) {
		Integer r = classImports.get(name);
		if (r == null) {
			Integer core = classImports.get("");
			if (core == null) {
				core = imp("Core", "Package", 0, "Core");
				classImports.put("", core);
			}
			r = imp("Core", "Class", core, name);
			classImports.put(name, r);
		}
		return r;
	}

	public int scriptClass(String name, int superRef) {
		int ref = exp(0, 0, name);
		script.put(ref, new ClassScript(superRef));
		members.put(ref, new ArrayList<>());
		return ref;
	}

	public int scriptStruct(int owner, String name, int superRef, int flags, Body defaults) {
		int ref = member(owner, classImport("ScriptStruct"), name, new StructScript(superRef, flags, defaults));
		members.put(ref, new ArrayList<>());
		return ref;
	}

	public int scriptEnum(int owner, String name, String... values) {
		for (String v : values) {
			name(v);
		}
		return member(owner, classImport("Enum"), name, new EnumScript(List.of(values)));
	}

	public int function(int owner, String name) {
		return member(owner, classImport("Function"), name, new FunctionScript());
	}

	public int property(int owner, String name, String type, int arrayDim, int sub) {
		return member(owner, classImport(type), name, new PropScript(type, arrayDim, sub));
	}

	public int arrayProperty(int owner, String name, String innerType, int innerSub) {
		int array = property(owner, name, "ArrayProperty", 1, 0);
		int inner = property(array, name, innerType, 1, innerSub);
		script.put(array, new PropScript("ArrayProperty", 1, inner));
		return array;
	}

	public int cdo(int classRef, Body tags) {
		return exp(classRef, 0, "Default__" + export(classRef).name, body().i32(0).raw(tags.toArray()).fname("None").toArray());
	}

	private int member(int owner, int classRef, String name, Script s) {
		int ref = exp(classRef, owner, name);
		script.put(ref, s);
		List<Integer> list = members.get(owner);
		if (list != null) {
			list.add(ref);
		}
		return ref;
	}

	private void linkScript() {
		Map<Integer, Integer> next = new HashMap<>();
		for (List<Integer> list : members.values()) {
			for (int i = 0; i + 1 < list.size(); i++) {
				next.put(list.get(i), list.get(i + 1));
			}
		}
		for (Map.Entry<Integer, Script> e : script.entrySet()) {
			int ref = e.getKey();
			int nx = next.getOrDefault(ref, 0);
			List<Integer> own = members.get(ref);
			int children = own == null || own.isEmpty() ? 0 : own.get(0);
			Body b = body();
			switch (e.getValue()) {
				case ClassScript c -> b.i32(0).i32(nx).i32(c.superRef()).i32(0).i32(children).raw(new byte[12]);
				case StructScript c -> b.i32(0).fname("None").i32(nx).i32(c.superRef()).i32(0).i32(children).i32(0).i32(0)
						.i32(0).i32(3).i32(3).raw(new byte[] {0x0B, 0x53, 0x00}).i32(c.flags()).raw(c.defaults().toArray())
						.fname("None");
				case PropScript c -> {
					String text = c.type().equals("ObjectProperty") ? "ab" : c.type().equals("StructProperty") ? "S☃" : "";
					b.i32(0).fname("None").i32(nx).i32(c.arrayDim()).i64(1).fname("None").i32(0).fstring(text);
					if (!c.type().equals("BoolProperty")) {
						b.i32(c.sub());
					}
				}
				case FunctionScript c -> b.i32(0).fname("None").i32(nx).raw(new byte[8]);
				case EnumScript c -> {
					b.i32(0).fname("None").i32(nx).i32(c.values().size());
					c.values().forEach(b::fname);
				}
			}
			export(ref).body = b.toArray();
		}
	}

	public byte[] bytes() {
		linkScript();
		Body s = summary(0, 0, 0, 0);
		int summaryEnd = s.size();
		Body tables = new Body();
		nameOffset = summaryEnd;
		for (String n : names) {
			tables.fstring(n).i64(0x0007_0010_0000_0000L);
		}
		importOffset = summaryEnd + tables.size();
		for (Imp i : imports) {
			tables.i32(names.indexOf(i.classPackage())).i32(0).i32(names.indexOf(i.className())).i32(0).i32(i.outer())
					.i32(names.indexOf(i.name())).i32(0);
		}
		exportOffset = summaryEnd + tables.size();
		int exportTable = 0;
		for (Exp e : exports) {
			exportTable += 4 * 6 + 8 + 4 + 8 + 4 + 4 + 4 * e.netObjects + 16 + 4;
		}
		headersSize = exportOffset + exportTable;
		long at = headersSize;
		Body bodies = new Body();
		for (Exp e : exports) {
			long offset = e.serialOffset != null ? e.serialOffset : at;
			int size = e.serialOffset != null ? e.serialSize : e.body.length;
			tables.i32(e.classIndex).i32(e.superIndex).i32(e.outer).i32(names.indexOf(e.name)).i32(e.number)
					.i32(e.archetype).i64(e.objectFlags).i32(size).i64(offset).i32(e.exportFlags).i32(e.netObjects);
			for (int k = 0; k < e.netObjects; k++) {
				tables.i32(100 + k);
			}
			tables.raw(new byte[16]).i32(packageFlags);
			if (e.serialOffset == null) {
				bodies.raw(e.body);
				at += e.body.length;
			}
		}
		s = summary(headersSize, nameOffset, importOffset, exportOffset);
		if (s.size() != summaryEnd) {
			throw new IllegalStateException("summary size changed");
		}
		return s.raw(tables.toArray()).raw(bodies.toArray()).toArray();
	}

	private Body summary(int headers, int nameOff, int importOff, int exportOff) {
		Body s = new Body();
		s.i32(UpkReader.TAG).i32(version).i32(headers).fstring(folder).i32(packageFlags);
		s.i32(names.size()).i32(nameOff).i32(exports.size()).i32(exportOff).i32(imports.size()).i32(importOff);
		s.i32(headers).i32(headers).i32(0).i32(0).i32(0); // DependsOffset .. ThumbnailTableOffset
		s.raw(new byte[16]); // Guid
		s.i32(1).i32(exports.size()).i32(names.size()).i32(0); // generations
		s.i32(engineVersion).i32(cookerVersion);
		s.i32(1).i32(1).i32(0).i32(0).i32(0).i32(0); // CompressionFlags, one compressed chunk
		s.i32(0x1234_5678); // PackageSource
		s.i32(1).fstring("Extra_SF"); // AdditionalPackagesToCook
		s.i32(1).raw(new byte[20]).i32(2).i32(7).i32(9); // texture allocations
		s.i32(0).i32(0).i32(0); // RL: GarbageSize, CompressedChunkInfoOffset, LastBlockSize
		return s;
	}

	public Path write(Path dir) throws IOException {
		Path p = dir.resolve(packageName + ".bin");
		Files.write(p, bytes());
		return p;
	}

	public final class Body {
		private byte[] a = new byte[64];
		private int n;

		private Body grow(int k) {
			if (n + k > a.length) {
				a = Arrays.copyOf(a, Math.max(2 * a.length, n + k));
			}
			return this;
		}

		public Body u8(int v) {
			grow(1).a[n++] = (byte) v;
			return this;
		}

		public Body i32(int v) {
			grow(4);
			ByteBuffer.wrap(a, n, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(v);
			n += 4;
			return this;
		}

		public Body i64(long v) {
			grow(8);
			ByteBuffer.wrap(a, n, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(v);
			n += 8;
			return this;
		}

		public Body f32(float v) {
			return i32(Float.floatToRawIntBits(v));
		}

		public Body raw(byte[] b) {
			grow(b.length);
			System.arraycopy(b, 0, a, n, b.length);
			n += b.length;
			return this;
		}

		public Body fname(String s) {
			return fname(s, 0);
		}

		public Body fname(String s, int number) {
			return i32(name(s)).i32(number);
		}

		public Body fstring(String s) {
			if (s.isEmpty()) {
				return i32(0);
			}
			if (s.chars().allMatch(c -> c <= 0xFF)) {
				return i32(s.length() + 1).raw(s.getBytes(StandardCharsets.ISO_8859_1)).u8(0);
			}
			return i32(-(s.length() + 1)).raw(s.getBytes(StandardCharsets.UTF_16LE)).u8(0).u8(0);
		}

		public Body tag(String name, String type, int size, int index) {
			return fname(name).fname(type).i32(size).i32(index);
		}

		public Body prop(String name, String type, byte[] value) {
			return tag(name, type, value.length, 0).raw(value);
		}

		public Body floatProp(String name, float v) {
			return tag(name, "FloatProperty", 4, 0).f32(v);
		}

		public Body intProp(String name, int v) {
			return intProp(name, 0, v);
		}

		public Body intProp(String name, int index, int v) {
			return tag(name, "IntProperty", 4, index).i32(v);
		}

		public Body strProp(String name, String v) {
			return prop(name, "StrProperty", new Body().fstring(v).toArray());
		}

		public Body objProp(String name, int ref) {
			return tag(name, "ObjectProperty", 4, 0).i32(ref);
		}

		public Body structProp(String name, String structName, Body value) {
			return tag(name, "StructProperty", value.size(), 0).fname(structName).raw(value.toArray());
		}

		public Body arrayProp(String name, int count, Body elements) {
			return tag(name, "ArrayProperty", 4 + elements.size(), 0).i32(count).raw(elements.toArray());
		}

		public Body none() {
			return fname("None");
		}

		public int size() {
			return n;
		}

		public byte[] toArray() {
			return Arrays.copyOf(a, n);
		}
	}
}
