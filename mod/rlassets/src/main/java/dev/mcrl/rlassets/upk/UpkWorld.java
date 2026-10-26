package dev.mcrl.rlassets.upk;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import dev.mcrl.rlassets.upk.UpkSchema.PropDef;
import dev.mcrl.rlassets.upk.UpkSchema.StructDef;

public final class UpkWorld {
	public record Loc(String pkg, int index) {
	}

	public static final List<String> SEARCH = List.of("Startup", "TAGame", "GameInfo_Soccar_SF", "Engine", "ProjectX");

	private final Path dir;
	private final List<String> extras;
	private final Map<String, Optional<UpkReader>> packages = new HashMap<>();
	private final List<String> warnings = new ArrayList<>();
	private @Nullable UpkSchema schema;
	private final Map<String, List<PropDef>> fieldCache = new HashMap<>();
	private final Map<String, Map<String, PropDef>> propCache = new HashMap<>();
	private final Map<String, Map<String, Object>> structDefaults = new HashMap<>();
	private final Map<String, Map<String, Object>> cdoCache = new HashMap<>();
	private final Map<String, UObject> objects = new HashMap<>();
	private final Map<String, Optional<Loc>> resolved = new HashMap<>();
	private final Set<String> busy = new HashSet<>();

	public UpkWorld(Path unpackedDir) {
		this(unpackedDir, List.of());
	}

	public UpkWorld(Path unpackedDir, List<String> extraPackages) {
		this.dir = unpackedDir;
		this.extras = List.copyOf(extraPackages);
	}

	public @Nullable UpkReader pkg(String name) {
		Optional<UpkReader> p = packages.get(name);
		if (p == null) {
			p = Optional.empty();
			Path bin = dir.resolve(name + ".bin");
			if (!name.isEmpty() && !name.contains("/") && !name.contains("\\") && !name.startsWith(".")
					&& Files.isRegularFile(bin)) {
				try {
					p = Optional.of(UpkReader.open(bin));
				} catch (IOException | RuntimeException e) {
					warn("package " + name + " skipped: " + e.getMessage());
				}
			}
			packages.put(name, p);
		}
		return p.orElse(null);
	}

	public UpkSchema schema() {
		if (schema == null) {
			schema = UpkSchema.build(this);
		}
		return schema;
	}

	public List<String> warnings() {
		return Collections.unmodifiableList(warnings);
	}

	void warn(String s) {
		warnings.add(s);
	}

	public UObject obj(String pkg, int index) {
		UpkReader p = pkg(pkg);
		if (p == null) {
			throw new IllegalArgumentException("no package " + pkg + " in " + dir);
		}
		UObject o = object(p, index);
		return new UObject(o.classPath(), o.path(), o.archetype(), copy(o.props()), o.tagStart(), o.tagEnd(), o.size());
	}

	private UObject object(UpkReader p, int idx) {
		String key = p.packageName() + ":" + idx;
		UObject done = objects.get(key);
		if (done != null) {
			return done;
		}
		UpkReader.Export e = p.exports().get(idx - 1);
		String cp = classPath(p, e.classIndex());
		String path = p.packageName() + ":" + p.objectPath(idx);
		if (!busy.add("object " + key)) {
			warn(path + ": its archetype chain loops");
			return new UObject(cp, path, "CDO " + cp, cdo(cp), 0, 0, 0);
		}
		try {
			Map<String, Object> base;
			String archetype;
			if (e.archetype() != 0) {
				Loc a = resolve(p, e.archetype());
				archetype = refPath(p, e.archetype());
				UpkReader ap = a == null ? null : pkg(a.pkg());
				if (ap != null) {
					base = copy(object(ap, a.index()).props());
				} else {
					base = cdo(cp);
					warn(path + ": archetype " + p.objectPath(e.archetype()) + " not found");
				}
			} else {
				base = cdo(cp);
				archetype = "CDO " + cp;
			}
			Decoded d = cp.equals("Core.Class") ? new Decoded(base, 0, 0, e.serialSize()) : decode(p, idx, cp, base);
			Map<String, Object> v = d.props();
			for (PropDef pd : fields(cp)) {
				if (pd.type().equals("StructProperty") && !v.containsKey(pd.name())) {
					Map<String, Object> sd = structDefault(pd.sub());
					if (nonZero(sd)) {
						v.put(pd.name(), sd);
					}
				}
			}
			UObject o = new UObject(cp, path, archetype, v, d.start(), d.end(), d.size());
			objects.put(key, o);
			return o;
		} finally {
			busy.remove("object " + key);
		}
	}

	public Map<String, Object> cdo(String classPath) {
		Map<String, Object> done = cdoCache.get(classPath);
		if (done != null) {
			return copy(done);
		}
		if (!busy.add("cdo " + classPath)) {
			warn("class " + classPath + " is its own super class");
			return new LinkedHashMap<>();
		}
		try {
			UpkSchema.ClassDef d = schema().classes().get(classPath);
			Map<String, Object> v = d != null && d.superPath() != null ? cdo(d.superPath()) : new LinkedHashMap<>();
			Loc loc = schema().cdos().get(classPath);
			UpkReader p = loc == null ? null : pkg(loc.pkg());
			if (p != null) {
				v = decode(p, loc.index(), classPath, v).props();
			}
			cdoCache.put(classPath, v);
			return copy(v);
		} finally {
			busy.remove("cdo " + classPath);
		}
	}

	public Map<String, Object> structDefault(@Nullable String structPath) {
		StructDef d = structPath == null ? null : schema().structs().get(structPath);
		if (d == null) {
			return new LinkedHashMap<>();
		}
		Map<String, Object> done = structDefaults.get(structPath);
		if (done != null) {
			return copy(done);
		}
		if (!busy.add("struct " + structPath)) {
			warn("struct " + structPath + " contains itself");
			return new LinkedHashMap<>();
		}
		try {
			Map<String, Object> v = d.superPath() != null ? structDefault(d.superPath()) : new LinkedHashMap<>();
			UpkReader p = pkg(d.pkg());
			try {
				if (p != null) {
					tags(p, new Cur(p.body(d.index()), d.defaultsOffset()), structPath, v);
				}
			} catch (IOException | RuntimeException ex) {
				warn("struct default " + structPath + ": " + ex);
			}
			structDefaults.put(structPath, v);
			return copy(v);
		} finally {
			busy.remove("struct " + structPath);
		}
	}

	private record Decoded(Map<String, Object> props, int start, int end, int size) {
	}

	private Decoded decode(UpkReader p, int idx, String classPath, Map<String, Object> base) {
		ByteBuffer b;
		try {
			b = p.body(idx);
		} catch (IOException ex) {
			warn(ex.getMessage());
			return new Decoded(base, 0, 0, 0);
		}
		if (b.limit() == 0) {
			return new Decoded(base, 0, 0, 0);
		}
		int start = startOfTags(p, b), end;
		try {
			Cur c = new Cur(b, start);
			tags(p, c, classPath, base);
			end = c.o;
		} catch (RuntimeException ex) {
			warn(p.packageName() + ":" + p.objectPath(idx) + ": " + ex);
			end = start;
		}
		return new Decoded(base, start, end, b.limit());
	}

	static int startOfTags(UpkReader p, ByteBuffer b) {
		for (int st = 4; st < Math.min(b.limit(), 40); st += 4) {
			if (tagsAt(p, b, st)) {
				return st;
			}
		}
		return tagsAt(p, b, STATE_FRAME_TAGS) ? STATE_FRAME_TAGS : 4;
	}

	static final int STATE_FRAME_TAGS = 26; // UE3's 22-byte actor state frame, then NetIndex

	private static boolean tagsAt(UpkReader p, ByteBuffer b, int st) {
		int names = p.names().size();
		if (st + 8 > b.limit()) {
			return false;
		}
		int i = b.getInt(st), n = b.getInt(st + 4);
		if (i < 0 || i >= names || n < 0 || n >= 100_000) {
			return false;
		}
		if (p.fname(i, n).equals("None")) {
			return true;
		}
		if (st + 16 <= b.limit()) {
			int i2 = b.getInt(st + 8), n2 = b.getInt(st + 12);
			return i2 >= 0 && i2 < names && n2 == 0 && p.names().get(i2).endsWith("Property");
		}
		return false;
	}

	private void tags(UpkReader p, Cur c, @Nullable String owner, Map<String, Object> out) {
		while (true) {
			String name = c.name(p);
			if (name.equals("None")) {
				return;
			}
			String type = c.name(p);
			int size = c.i32(), index = c.i32();
			String key = index == 0 ? name : name + "[" + index + "]";
			if (type.equals("BoolProperty")) {
				out.put(key, c.u8() != 0);
				continue;
			}
			String extra = type.equals("StructProperty") || type.equals("ByteProperty") ? c.name(p) : null;
			ByteBuffer val = c.take(size);
			Object v;
			try {
				v = tagValue(p, owner, key, type, extra, prop(owner, name), val, out);
			} catch (RuntimeException ex) {
				v = new UpkUnknown(type, hex(val));
				warn(owner + "." + key + ": " + ex);
			}
			out.put(key, v);
		}
	}

	private @Nullable Object tagValue(UpkReader p, @Nullable String owner, String key, String type, @Nullable String extra,
			@Nullable PropDef pd, ByteBuffer val, Map<String, Object> out) {
		int size = val.limit();
		switch (type) {
			case "StructProperty" -> {
				String sp = pd != null ? pd.sub() : null;
				if (sp == null) {
					List<String> byName = schema().structsByName().get(extra);
					sp = byName == null ? null : byName.get(0);
				}
				if (sp == null) {
					return unknown(owner, key, type, val, "no struct " + extra);
				}
				StructDef sd = schema().structs().get(sp);
				if (sd == null) {
					throw new IllegalStateException("no struct " + sp);
				}
				Cur s = new Cur(val, 0);
				Map<String, Object> v;
				if (sd.binary()) {
					v = binStruct(p, sp, s);
					if (s.o != size) {
						warn(owner + "." + key + ": binary " + extra + " used " + s.o + "/" + size + " B");
					}
				} else {
					v = out.get(key) instanceof Map<?, ?> m ? copy(m) : structDefault(sp);
					tags(p, s, sp, v);
					if (s.o != size) {
						warn(owner + "." + key + ": tagged " + extra + " used " + s.o + "/" + size + " B");
					}
				}
				return v;
			}
			case "ByteProperty" -> {
				return size == 8 ? new Cur(val, 0).name(p) : val.get(0) & 0xFF;
			}
			case "ArrayProperty" -> {
				int n = val.getInt(0);
				PropDef inner = pd == null || pd.sub() == null ? null : schema().props().get(pd.sub());
				if (inner == null) {
					return unknown(owner, key, type, val, "no array type");
				}
				Cur s = new Cur(val, 4);
				List<Object> v = new ArrayList<>();
				for (int i = 0; i < n; i++) {
					v.add(elem(p, inner, s));
				}
				if (s.o != size) {
					warn(owner + "." + key + ": array used " + s.o + "/" + size + " B");
				}
				return v;
			}
			case "FloatProperty" -> {
				return exact(val, 4).getFloat(0);
			}
			case "IntProperty" -> {
				return exact(val, 4).getInt(0);
			}
			case "QWordProperty", "SQWordProperty" -> {
				return exact(val, 8).getLong(0);
			}
			case "NameProperty" -> {
				return new Cur(val, 0).name(p);
			}
			case "StrProperty" -> {
				return new Cur(val, 0).fstring();
			}
			case "ObjectProperty", "ClassProperty", "ComponentProperty", "InterfaceProperty" -> {
				return ref(p, val.getInt(0));
			}
			case "DelegateProperty" -> {
				return delegate(p, new Cur(val, 0));
			}
			default -> {
				return unknown(owner, key, type, val, "unknown type " + type);
			}
		}
	}

	private UpkUnknown unknown(@Nullable String owner, String key, String type, ByteBuffer val, String why) {
		warn(owner + "." + key + ": " + why + ", kept as " + val.limit() + " B of hex");
		return new UpkUnknown(type, hex(val));
	}

	private @Nullable Object elem(UpkReader p, PropDef inner, Cur c) {
		if (inner.type().equals("StructProperty")) {
			StructDef sd = inner.sub() == null ? null : schema().structs().get(inner.sub());
			if (sd != null && sd.binary()) {
				return binStruct(p, inner.sub(), c);
			}
			Map<String, Object> v = structDefault(inner.sub());
			tags(p, c, inner.sub(), v);
			return v;
		}
		return binItem(p, inner, c);
	}

	private @Nullable Object binItem(UpkReader p, PropDef pd, Cur c) {
		switch (pd.type()) {
			case "FloatProperty" -> {
				return c.f32();
			}
			case "IntProperty" -> {
				return c.i32();
			}
			case "QWordProperty", "SQWordProperty" -> {
				return c.i64();
			}
			case "ByteProperty" -> {
				return pd.sub() != null ? c.name(p) : c.u8();
			}
			case "BoolProperty" -> {
				return c.u8() != 0;
			}
			case "NameProperty" -> {
				return c.name(p);
			}
			case "ObjectProperty", "ComponentProperty", "ClassProperty", "InterfaceProperty" -> {
				return ref(p, c.i32());
			}
			case "StrProperty" -> {
				return c.fstring();
			}
			case "DelegateProperty" -> {
				return delegate(p, c);
			}
			case "StructProperty" -> {
				return binStruct(p, pd.sub(), c);
			}
			case "ArrayProperty" -> {
				int n = c.i32();
				PropDef inner = pd.sub() == null ? null : schema().props().get(pd.sub());
				if (inner == null) {
					throw new IllegalStateException("no array type for " + pd.name());
				}
				List<Object> v = new ArrayList<>();
				for (int i = 0; i < n; i++) {
					v.add(elem(p, inner, c));
				}
				return v;
			}
			default -> throw new IllegalArgumentException("no binary form for " + pd.type());
		}
	}

	private Map<String, Object> binStruct(UpkReader p, @Nullable String structPath, Cur c) {
		Map<String, Object> v = new LinkedHashMap<>();
		for (PropDef pd : fields(structPath)) {
			for (int k = 0; k < pd.arrayDim(); k++) {
				v.put(k == 0 ? pd.name() : pd.name() + "[" + k + "]", binItem(p, pd, c));
			}
		}
		return v;
	}

	private Map<String, Object> delegate(UpkReader p, Cur c) {
		Map<String, Object> v = new LinkedHashMap<>();
		v.put("Object", ref(p, c.i32()));
		v.put("FunctionName", c.name(p));
		return v;
	}

	private static ByteBuffer exact(ByteBuffer val, int size) {
		if (val.limit() != size) {
			throw new IllegalArgumentException(size + " B expected, the tag has " + val.limit());
		}
		return val;
	}

	public List<PropDef> fields(@Nullable String owner) {
		if (owner == null) {
			return List.of();
		}
		List<PropDef> done = fieldCache.get(owner);
		if (done != null) {
			return done;
		}
		UpkSchema s = schema();
		List<List<String>> chain = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (String c = owner; c != null && seen.add(c);) {
			UpkSchema.ClassDef cd = s.classes().get(c);
			StructDef sd = cd == null ? s.structs().get(c) : null;
			if (cd == null && sd == null) {
				break;
			}
			chain.add(0, cd != null ? cd.fields() : sd.fields());
			c = cd != null ? cd.superPath() : sd.superPath();
		}
		List<PropDef> out = new ArrayList<>();
		for (List<String> fs : chain) {
			for (String f : fs) {
				out.add(s.props().get(f));
			}
		}
		done = List.copyOf(out);
		fieldCache.put(owner, done);
		return done;
	}

	public @Nullable PropDef prop(@Nullable String owner, String name) {
		if (owner == null) {
			return null;
		}
		Map<String, PropDef> byName = propCache.get(owner);
		if (byName == null) {
			byName = new HashMap<>();
			for (PropDef pd : fields(owner)) {
				byName.put(pd.name(), pd);
			}
			propCache.put(owner, byName);
		}
		return byName.get(name);
	}

	public static String classPath(UpkReader p, int classIndex) {
		if (classIndex == 0) {
			return "Core.Class";
		}
		return classIndex < 0 ? p.objectPath(classIndex) : p.packageName() + "." + p.objectPath(classIndex);
	}

	public @Nullable Loc locate(String path, @Nullable String hintPkg) {
		int colon = path.indexOf(':');
		if (colon >= 0) {
			hintPkg = path.substring(0, colon);
			path = path.substring(colon + 1);
		}
		Set<String> order = new LinkedHashSet<>();
		if (hintPkg != null) {
			order.add(hintPkg);
		}
		int dot = path.indexOf('.');
		order.add(dot < 0 ? path : path.substring(0, dot));
		order.addAll(SEARCH);
		order.addAll(extras);
		for (String name : order) {
			UpkReader p = pkg(name);
			if (p == null) {
				continue;
			}
			List<Integer> hits = p.find(path);
			String prefix = p.packageName() + ".";
			if (hits.isEmpty() && path.startsWith(prefix)) {
				hits = p.find(path.substring(prefix.length()));
			}
			if (!hits.isEmpty()) {
				return new Loc(p.packageName(), hits.get(0));
			}
		}
		return null;
	}

	public @Nullable Loc resolve(UpkReader p, int k) {
		if (k > 0) {
			return new Loc(p.packageName(), k);
		}
		if (k == 0) {
			return null;
		}
		String key = p.packageName() + "#" + k;
		Optional<Loc> r = resolved.get(key);
		if (r == null) {
			r = Optional.ofNullable(locate(p.objectPath(k), p.packageName()));
			resolved.put(key, r);
		}
		return r.orElse(null);
	}

	public @Nullable UpkRef ref(UpkReader p, int k) {
		return k == 0 ? null : new UpkRef(refPath(p, k));
	}

	private String refPath(UpkReader p, int k) {
		Loc l = resolve(p, k);
		UpkReader at = l == null ? null : pkg(l.pkg());
		return at == null ? p.objectPath(k) : at.packageName() + ":" + at.objectPath(l.index());
	}

	static Map<String, Object> copy(Map<?, ?> m) {
		Map<String, Object> c = new LinkedHashMap<>();
		for (Map.Entry<?, ?> e : m.entrySet()) {
			c.put((String) e.getKey(), copyValue(e.getValue()));
		}
		return c;
	}

	private static @Nullable Object copyValue(@Nullable Object v) {
		if (v instanceof Map<?, ?> m) {
			return copy(m);
		}
		if (v instanceof List<?> l) {
			List<Object> c = new ArrayList<>(l.size());
			for (Object x : l) {
				c.add(copyValue(x));
			}
			return c;
		}
		return v;
	}

	static boolean nonZero(@Nullable Object v) {
		if (v instanceof Map<?, ?> m) {
			return m.values().stream().anyMatch(UpkWorld::nonZero);
		}
		if (v instanceof List<?> l) {
			return l.stream().anyMatch(UpkWorld::nonZero);
		}
		if (v == null) {
			return false;
		}
		if (v instanceof Float f) {
			return f != 0;
		}
		if (v instanceof Number n) {
			return n.longValue() != 0;
		}
		if (v instanceof Boolean b) {
			return b;
		}
		if (v instanceof String s) {
			return !s.isEmpty() && !s.equals("None");
		}
		return true;
	}

	private static String hex(ByteBuffer b) {
		byte[] a = new byte[b.limit()];
		b.get(0, a);
		return HexFormat.of().formatHex(a);
	}

	private static final class Cur {
		final ByteBuffer b;
		int o;

		Cur(ByteBuffer b, int o) {
			this.b = b.order(ByteOrder.LITTLE_ENDIAN);
			this.o = o;
		}

		int i32() {
			int v = b.getInt(o);
			o += 4;
			return v;
		}

		long i64() {
			long v = b.getLong(o);
			o += 8;
			return v;
		}

		float f32() {
			float v = b.getFloat(o);
			o += 4;
			return v;
		}

		int u8() {
			return b.get(o++) & 0xFF;
		}

		String name(UpkReader p) {
			int i = i32(), n = i32();
			return p.fname(i, n);
		}

		ByteBuffer take(int n) {
			if (n < 0 || o + n > b.limit()) {
				throw new IndexOutOfBoundsException("a value of " + n + " B at " + o + " runs past " + b.limit());
			}
			ByteBuffer v = b.slice(o, n).order(ByteOrder.LITTLE_ENDIAN);
			o += n;
			return v;
		}

		String fstring() {
			int n = i32();
			if (n >= 0) {
				String s = n == 0 ? "" : new String(bytes(n - 1), StandardCharsets.ISO_8859_1);
				o += n;
				return s;
			}
			long len = -2L * n;
			if (o + len > b.limit()) {
				throw new IndexOutOfBoundsException("a string of " + len + " B at " + o + " runs past " + b.limit());
			}
			String s = new String(bytes((int) len - 2), StandardCharsets.UTF_16LE);
			o += (int) len;
			return s;
		}

		private byte[] bytes(int n) {
			byte[] a = new byte[n];
			b.get(o, a);
			return a;
		}
	}
}
