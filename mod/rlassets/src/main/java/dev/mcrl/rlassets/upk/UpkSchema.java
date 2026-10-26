package dev.mcrl.rlassets.upk;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

public final class UpkSchema {
	public static final List<String> SCRIPT_PACKAGES = List.of("Core", "Engine", "GFxUI", "AkAudio", "IpDrv", "ProjectX",
			"TAGame");
	public static final Set<String> PROP_TYPES = Set.of("ArrayProperty", "BoolProperty", "ByteProperty", "ClassProperty",
			"ComponentProperty", "DelegateProperty", "FloatProperty", "IntProperty", "InterfaceProperty", "MapProperty",
			"NameProperty", "ObjectProperty", "StrProperty", "StructProperty", "QWordProperty", "SQWordProperty");
	public static final int BINARY_STRUCT = 0x20 | 0x80; // Immutable, ImmutableWhenCooked

	public record PropDef(String name, String type, int arrayDim, long flags, @Nullable String next, @Nullable String sub) {
	}

	public record StructDef(String name, @Nullable String superPath, @Nullable String children, int flags,
			List<String> fields, String pkg, int index, int defaultsOffset) {
		public boolean binary() {
			return (flags & BINARY_STRUCT) != 0;
		}
	}

	public record ClassDef(String name, @Nullable String superPath, @Nullable String children, List<String> fields) {
	}

	private final Map<String, PropDef> props;
	private final Map<String, StructDef> structs;
	private final Map<String, ClassDef> classes;
	private final Map<String, List<String>> enums;
	private final Map<String, UpkWorld.Loc> cdos;
	private final Map<String, List<String>> structsByName;
	private final Map<String, List<String>> classesByName;

	private UpkSchema(Map<String, PropDef> props, Map<String, StructDef> structs, Map<String, ClassDef> classes,
			Map<String, List<String>> enums, Map<String, UpkWorld.Loc> cdos) {
		this.props = Collections.unmodifiableMap(props);
		this.structs = Collections.unmodifiableMap(structs);
		this.classes = Collections.unmodifiableMap(classes);
		this.enums = Collections.unmodifiableMap(enums);
		this.cdos = Collections.unmodifiableMap(cdos);
		this.structsByName = byName(structs.entrySet().stream().map(e -> Map.entry(e.getValue().name(), e.getKey())).toList());
		this.classesByName = byName(classes.entrySet().stream().map(e -> Map.entry(e.getValue().name(), e.getKey())).toList());
	}

	private static Map<String, List<String>> byName(List<Map.Entry<String, String>> namePath) {
		Map<String, List<String>> m = new HashMap<>();
		for (Map.Entry<String, String> e : namePath) {
			m.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
		}
		m.replaceAll((k, v) -> List.copyOf(v));
		return Collections.unmodifiableMap(m);
	}

	private record RawStruct(String name, @Nullable String superPath, @Nullable String children, int flags, String pkg,
			int index, int defaultsOffset) {
	}

	private record RawClass(String name, @Nullable String superPath, @Nullable String children) {
	}

	public static UpkSchema build(UpkWorld w) {
		Map<String, PropDef> props = new HashMap<>();
		Map<String, RawStruct> structs = new LinkedHashMap<>();
		Map<String, RawClass> classes = new LinkedHashMap<>();
		Map<String, List<String>> enums = new HashMap<>();
		Map<String, UpkWorld.Loc> cdos = new HashMap<>();
		Map<String, String> nexts = new HashMap<>();
		for (String pk : SCRIPT_PACKAGES) {
			UpkReader p = w.pkg(pk);
			if (p == null) {
				continue;
			}
			for (int i = 1; i <= p.exports().size(); i++) {
				UpkReader.Export e = p.exports().get(i - 1);
				if (e.serialSize() == 0) {
					continue;
				}
				String cls = p.className(e.classIndex());
				String fp = fullPath(p, i);
				try {
					if (PROP_TYPES.contains(cls)) {
						ByteBuffer b = p.body(i);
						int next = b.getInt(12), arrayDim = b.getInt(16);
						long flags = b.getLong(20);
						long o = 40;
						int n = b.getInt(40);
						o += 4 + (n >= 0 ? n : -2L * n);
						int sub = o + 4 <= b.limit() ? b.getInt((int) o) : 0;
						props.put(fp, new PropDef(e.objectName(), cls, arrayDim, flags, fullPath(p, next),
								cls.equals("BoolProperty") || sub == 0 || !inRange(p, sub) ? null : fullPath(p, sub)));
					} else if (cls.equals("Function") || cls.equals("State") || cls.equals("Const")) {
						ByteBuffer b = p.body(i);
						if (b.limit() >= 16) {
							nexts.put(fp, fullPath(p, b.getInt(12)));
						}
					} else if (cls.equals("ScriptStruct")) {
						ByteBuffer b = p.body(i);
						nexts.put(fp, fullPath(p, b.getInt(12)));
						int storage = b.getInt(44), o = 48 + storage;
						structs.put(fp, new RawStruct(e.objectName(), fullPath(p, b.getInt(16)), fullPath(p, b.getInt(24)),
								b.getInt(o), pk, i, o + 4));
					} else if (cls.equals("Class")) {
						ByteBuffer b = p.body(i);
						classes.put(fp, new RawClass(e.objectName(), fullPath(p, b.getInt(8)), fullPath(p, b.getInt(16))));
					} else if (cls.equals("Enum")) {
						ByteBuffer b = p.body(i);
						nexts.put(fp, fullPath(p, b.getInt(12)));
						int count = b.getInt(16);
						List<String> values = new ArrayList<>(Math.max(0, Math.min(count, 4096)));
						for (int k = 0; k < count; k++) {
							values.add(p.fname(b.getInt(20 + 8 * k), b.getInt(24 + 8 * k)));
						}
						enums.put(fp, List.copyOf(values));
					}
				} catch (IOException | RuntimeException ex) {
					w.warn("schema: " + pk + ":" + p.objectPath(i) + " (" + cls + "): " + ex);
				}
				if (e.objectName().startsWith("Default__") && e.outer() == 0) {
					cdos.put(pk + "." + e.objectName().substring(9), new UpkWorld.Loc(pk, i));
				}
			}
		}
		Map<String, StructDef> s = new LinkedHashMap<>();
		structs.forEach((fp, r) -> s.put(fp, new StructDef(r.name(), r.superPath(), r.children(), r.flags(),
				fields(r.children(), props, nexts), r.pkg(), r.index(), r.defaultsOffset())));
		Map<String, ClassDef> c = new LinkedHashMap<>();
		classes.forEach((fp, r) -> c.put(fp, new ClassDef(r.name(), r.superPath(), r.children(),
				fields(r.children(), props, nexts))));
		return new UpkSchema(props, s, c, enums, cdos);
	}

	private static List<String> fields(@Nullable String children, Map<String, PropDef> props, Map<String, String> nexts) {
		List<String> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (String c = children; c != null && seen.add(c);) {
			PropDef p = props.get(c);
			if (p != null) {
				out.add(c);
				c = p.next();
			} else {
				c = nexts.get(c);
			}
		}
		return List.copyOf(out);
	}

	static @Nullable String fullPath(UpkReader p, int k) {
		if (k > 0) {
			return p.packageName() + "." + p.objectPath(k);
		}
		return k < 0 ? p.objectPath(k) : null;
	}

	private static boolean inRange(UpkReader p, int k) {
		return k >= -p.imports().size() && k <= p.exports().size();
	}

	public Map<String, PropDef> props() {
		return props;
	}

	public Map<String, StructDef> structs() {
		return structs;
	}

	public Map<String, ClassDef> classes() {
		return classes;
	}

	public Map<String, List<String>> enums() {
		return enums;
	}

	public Map<String, UpkWorld.Loc> cdos() {
		return cdos;
	}

	public Map<String, List<String>> structsByName() {
		return structsByName;
	}

	public Map<String, List<String>> classesByName() {
		return classesByName;
	}

	public boolean isA(String classPath, String base) {
		Set<String> seen = new HashSet<>();
		for (String c = classPath; c != null && seen.add(c);) {
			if (c.equals(base)) {
				return true;
			}
			ClassDef d = classes.get(c);
			c = d == null ? null : d.superPath();
		}
		return false;
	}
}
