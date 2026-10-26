package dev.mcrl.rlassets.extract;

import dev.mcrl.rlassets.model.MaterialDef;
import dev.mcrl.rlassets.model.TextureInfo;
import dev.mcrl.rlassets.upk.UObject;
import dev.mcrl.rlassets.upk.UpkReader;
import dev.mcrl.rlassets.upk.UpkRef;
import dev.mcrl.rlassets.upk.UpkSchema;
import dev.mcrl.rlassets.upk.UpkWorld;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public final class MaterialExtractor {
	public static final List<String> SEARCH = List.of("Startup", "GameInfo_Soccar_SF", "TAGame");
	static final Set<String> TEXTURE_CLASSES = Set.of("Texture2D", "TextureCube", "TextureRenderTarget2D",
			"TextureRenderTargetCube", "TeamColorScriptedTexture_TA", "GlobalLookupTexture_TA", "ScriptedTexture",
			"TextureMovie", "LightMapTexture2D");
	private static final int MAX_CHAIN = 16;

	public record Named(String name, List<String> packages) {
	}

	public record Texture(String name, String path, String className, @Nullable TextureInfo info) {
	}

	public record Result(Map<String, MaterialDef> materials, Map<String, Texture> textures) {
		public static final Result EMPTY = new Result(Map.of(), Map.of());
	}

	public record Overlay(String name, String path, Named parent, Map<?, ?> params) {
	}

	private final UpkWorld w;
	private final List<String> warnings;
	private final Map<String, MaterialDef> materials = new LinkedHashMap<>();
	private final Keys materialKeys = new Keys(), textureKeys = new Keys();
	private final Map<String, Texture> textures = new LinkedHashMap<>();
	private final Map<String, Map<Integer, List<Integer>>> children = new HashMap<>();

	private MaterialExtractor(UpkWorld w, List<String> warnings) {
		this.w = w;
		this.warnings = warnings;
	}

	public static Result extract(UpkWorld w, List<Named> named, Collection<String> refs, List<String> warnings) {
		return extract(w, named, refs, List.of(), warnings);
	}

	public static Result extract(UpkWorld w, List<Named> named, Collection<String> refs, List<Overlay> overlays,
			List<String> warnings) {
		MaterialExtractor x = new MaterialExtractor(w, warnings);
		for (Named n : named) {
			UpkWorld.Loc l = x.byName(n);
			if (l == null) {
				warnings.add("material " + n.name() + " not found in " + n.packages() + " or " + SEARCH);
			} else {
				x.chain(l);
			}
		}
		for (String r : refs) {
			UpkWorld.Loc l = w.locate(r, null);
			if (l == null) {
				warnings.add("material " + r + " not found");
			} else {
				x.chain(l);
			}
		}
		for (Overlay o : overlays) {
			x.overlay(o);
		}
		return new Result(Collections.unmodifiableMap(x.materials), Collections.unmodifiableMap(x.textures));
	}

	private void overlay(Overlay o) {
		UpkWorld.Loc l = byName(o.parent());
		if (l == null) {
			warnings.add("material " + o.parent().name() + " (under " + o.name() + ") not found in "
					+ o.parent().packages() + " or " + SEARCH);
			return;
		}
		chain(l);
		if (materials.containsKey(o.name())) {
			warnings.add("material " + o.name() + " is taken; " + o.path() + " not added");
			return;
		}
		Map<String, Float> scalars = new LinkedHashMap<>();
		Map<String, float[]> vectors = new LinkedHashMap<>();
		Map<String, String> tex = new LinkedHashMap<>();
		for (Map<?, ?> v : structs(o.params().get("ScalarParameters"))) {
			if (v.get("Name") instanceof String n && v.get("Value") instanceof Float f) {
				scalars.put(n, f);
			}
		}
		for (Map<?, ?> v : structs(o.params().get("VectorParameters"))) {
			if (v.get("Name") instanceof String n && v.get("Value") instanceof Map<?, ?> c) {
				vectors.put(n, rgba(c));
			}
		}
		for (Map<?, ?> v : structs(o.params().get("TextureParameters"))) {
			if (v.get("Name") instanceof String n) {
				String t = texture(v.get("Value"));
				if (t != null) {
					tex.put(n, t);
				}
			}
		}
		materials.put(o.name(), new MaterialDef(o.name(), o.path(), "MaterialInstanceConstant",
				materialKeys.of(w.obj(l.pkg(), l.index()).path()), scalars, vectors, tex, Map.of(), null, null, null, null,
				List.of()));
	}

	private UpkWorld.@Nullable Loc byName(Named n) {
		Set<String> order = new LinkedHashSet<>(n.packages());
		order.addAll(SEARCH);
		for (String pk : order) {
			UpkReader p = w.pkg(pk);
			if (p == null) {
				continue;
			}
			for (int i : p.find(n.name())) {
				if (isMaterial(UpkWorld.classPath(p, p.exports().get(i - 1).classIndex()))) {
					return new UpkWorld.Loc(pk, i);
				}
			}
		}
		return null;
	}

	private boolean isMaterial(String classPath) {
		UpkSchema s = w.schema();
		return s.isA(classPath, "Engine.MaterialInterface") || s.isA(classPath, "Engine.Material")
				|| s.isA(classPath, "Engine.MaterialInstance");
	}

	private void chain(UpkWorld.Loc start) {
		UpkWorld.Loc l = start;
		for (int depth = 0; l != null && depth < MAX_CHAIN; depth++) {
			UObject o = w.obj(l.pkg(), l.index());
			String name = materialKeys.of(o.path());
			if (materials.containsKey(name)) {
				return;
			}
			UpkSchema s = w.schema();
			boolean base = s.isA(o.classPath(), "Engine.Material");
			boolean instance = !base && s.isA(o.classPath(), "Engine.MaterialInstance");
			if (!base && !instance) {
				warnings.add("material " + o.path() + " has class " + o.classPath() + ", neither a Material nor an instance");
				return;
			}
			UpkReader p = w.pkg(l.pkg());
			UpkWorld.Loc parent = null;
			String parentName = null;
			if (instance && o.props().get("Parent") instanceof UpkRef r) {
				parent = w.locate(r.path(), null);
				if (parent == null) {
					warnings.add("material " + o.path() + ": parent " + r.path() + " not found");
					parentName = lastName(r.path());
				} else {
					parentName = materialKeys.of(w.obj(parent.pkg(), parent.index()).path());
				}
			}
			Map<String, Float> scalars = new LinkedHashMap<>();
			Map<String, float[]> vectors = new LinkedHashMap<>();
			Map<String, String> tex = new LinkedHashMap<>();
			Map<String, Boolean> switches = new LinkedHashMap<>();
			String blend = null, lighting = null;
			Boolean twoSided = null;
			MaterialDef.Defaults defaults = null;
			if (instance) {
				for (Map<?, ?> v : structs(o.props().get("ScalarParameterValues"))) {
					if (v.get("ParameterName") instanceof String n && v.get("ParameterValue") instanceof Float f) {
						scalars.put(n, f);
					}
				}
				for (Map<?, ?> v : structs(o.props().get("VectorParameterValues"))) {
					if (v.get("ParameterName") instanceof String n && v.get("ParameterValue") instanceof Map<?, ?> c) {
						vectors.put(n, rgba(c));
					}
				}
				for (Map<?, ?> v : structs(o.props().get("TextureParameterValues"))) {
					if (v.get("ParameterName") instanceof String n) {
						String t = texture(v.get("ParameterValue"));
						if (t != null) {
							tex.put(n, t);
						}
					}
				}
				switches(p, l.index(), o, switches);
			} else {
				blend = enumValue(o, "BlendMode");
				lighting = enumValue(o, "LightingModel");
				twoSided = Boolean.TRUE.equals(o.props().get("TwoSided"));
				defaults = defaults(p, l);
			}
			List<String> shader = shaderTextures(p, l.index());
			materials.put(name, new MaterialDef(name, o.path(), shortName(o.classPath()), parentName, scalars, vectors, tex,
					switches, blend, twoSided, lighting, defaults, shader));
			l = parent;
		}
	}

	private MaterialDef.Defaults defaults(UpkReader p, UpkWorld.Loc l) {
		Map<String, Float> scalars = new LinkedHashMap<>();
		Map<String, float[]> vectors = new LinkedHashMap<>();
		Map<String, String> tex = new LinkedHashMap<>();
		Map<String, Boolean> switches = new LinkedHashMap<>();
		for (int i : children(p).getOrDefault(l.index(), List.of())) {
			String cls = p.className(p.exports().get(i - 1).classIndex());
			if (!cls.startsWith("MaterialExpression") || !cls.contains("Parameter")) {
				continue;
			}
			Map<String, Object> v = w.obj(l.pkg(), i).props();
			if (!(v.get("ParameterName") instanceof String n)) {
				continue;
			}
			if (cls.contains("ScalarParameter")) {
				scalars.putIfAbsent(n, v.get("DefaultValue") instanceof Float f ? f : 0f);
			} else if (cls.contains("VectorParameter")) {
				vectors.putIfAbsent(n, v.get("DefaultValue") instanceof Map<?, ?> c ? rgba(c) : new float[4]);
			} else if (cls.contains("TextureSampleParameter") || cls.contains("TextureObjectParameter")) {
				String t = texture(v.get("Texture"));
				if (t != null) {
					tex.putIfAbsent(n, t);
				}
			} else if (cls.contains("StaticSwitchParameter") || cls.contains("StaticBoolParameter")) {
				switches.putIfAbsent(n, Boolean.TRUE.equals(v.get("DefaultValue")));
			}
		}
		return new MaterialDef.Defaults(scalars, vectors, tex, switches);
	}

	private Map<Integer, List<Integer>> children(UpkReader p) {
		return children.computeIfAbsent(p.packageName(), k -> {
			Map<Integer, List<Integer>> m = new HashMap<>();
			for (int i = 1; i <= p.exports().size(); i++) {
				m.computeIfAbsent(p.exports().get(i - 1).outer(), o -> new ArrayList<>()).add(i);
			}
			return m;
		});
	}

	private void switches(UpkReader p, int index, UObject o, Map<String, Boolean> out) {
		ByteBuffer b;
		try {
			b = p.body(index);
		} catch (IOException e) {
			warnings.add(e.getMessage());
			return;
		}
		List<String> names = p.names();
		for (int at = o.tagEnd(); at + 32 <= b.limit();) {
			int ni = b.getInt(at), nn = b.getInt(at + 4);
			if (ni > 0 && ni < names.size() && nn == 0 && !names.get(ni).isEmpty()
					&& Character.isLetter(names.get(ni).charAt(0))) {
				int v = b.getInt(at + 8), ov = b.getInt(at + 12);
				if ((v == 0 || v == 1) && (ov == 0 || ov == 1)) {
					out.putIfAbsent(names.get(ni), v == 1);
					at += 32;
					continue;
				}
			}
			at++;
		}
	}

	private List<String> shaderTextures(UpkReader p, int index) {
		ByteBuffer b;
		try {
			b = p.body(index);
		} catch (IOException e) {
			warnings.add(e.getMessage());
			return List.of();
		}
		int imports = p.imports().size(), exports = p.exports().size();
		Set<List<Integer>> runs = new HashSet<>();
		Set<String> names = new LinkedHashSet<>();
		for (int at = 0; at + 8 <= b.limit(); at++) {
			int n = b.getInt(at);
			if (n < 1 || n > 32 || at + 4 + 4L * n > b.limit()) {
				continue;
			}
			List<Integer> refs = new ArrayList<>(n);
			for (int k = 0; k < n; k++) {
				int r = b.getInt(at + 4 + 4 * k);
				if (r == 0 || r < -imports || r > exports || !TEXTURE_CLASSES.contains(classOf(p, r))) {
					refs = null;
					break;
				}
				refs.add(r);
			}
			if (refs == null || !runs.add(refs)) {
				continue;
			}
			for (int r : refs) {
				String t = texture(w.ref(p, r));
				names.add(t != null ? t : lastName(p.objectPath(r)));
			}
		}
		return List.copyOf(names);
	}

	private static String classOf(UpkReader p, int r) {
		return r < 0 ? p.imports().get(-r - 1).className() : p.className(p.exports().get(r - 1).classIndex());
	}

	private @Nullable String texture(@Nullable Object ref) {
		if (!(ref instanceof UpkRef r)) {
			return null;
		}
		UpkWorld.Loc l = w.locate(r.path(), null);
		if (l == null) {
			warnings.add("texture " + r.path() + " not found");
			return lastName(r.path());
		}
		UObject o = w.obj(l.pkg(), l.index());
		String name = textureKeys.of(o.path());
		if (textures.containsKey(name)) {
			return name;
		}
		TextureInfo info = null;
		if (w.schema().isA(o.classPath(), "Engine.Texture2D")) {
			Map<String, Object> v = o.props();
			info = new TextureInfo(name, l.pkg(), "", v.get("SizeX") instanceof Integer x ? x : 0,
					v.get("SizeY") instanceof Integer y ? y : 0, 0, 0, enumValue(o, "Format"),
					enumValue(o, "CompressionSettings"), !Boolean.FALSE.equals(v.get("SRGB")), enumValue(o, "LODGroup"),
					enumValue(o, "AddressX"), enumValue(o, "AddressY"));
		}
		textures.put(name, new Texture(name, o.path(), shortName(o.classPath()), info));
		return name;
	}

	private String enumValue(UObject o, String prop) {
		if (o.props().get(prop) instanceof String s) {
			return s;
		}
		UpkSchema.PropDef pd = w.prop(o.classPath(), prop);
		List<String> values = pd == null || pd.sub() == null ? null : w.schema().enums().get(pd.sub());
		return values == null || values.isEmpty() ? "" : values.get(0);
	}

	private static List<Map<?, ?>> structs(@Nullable Object list) {
		List<Map<?, ?>> out = new ArrayList<>();
		if (list instanceof List<?> l) {
			for (Object x : l) {
				if (x instanceof Map<?, ?> m) {
					out.add(m);
				}
			}
		}
		return out;
	}

	private static float[] rgba(Map<?, ?> c) {
		float[] v = new float[4];
		String[] k = {"R", "G", "B", "A"};
		for (int i = 0; i < 4; i++) {
			v[i] = c.get(k[i]) instanceof Float f ? f : 0f;
		}
		return v;
	}

	private static final class Keys {
		final Map<String, String> byPath = new HashMap<>();
		final Set<String> used = new HashSet<>();

		String of(String path) {
			return byPath.computeIfAbsent(objectPath(path), op -> {
				String key = used.contains(lastName(op)) ? op : lastName(op);
				used.add(key);
				return key;
			});
		}
	}

	private static String lastName(String path) {
		return path.substring(Math.max(path.lastIndexOf(':'), path.lastIndexOf('.')) + 1);
	}

	private static String objectPath(String path) {
		return path.substring(path.indexOf(':') + 1);
	}

	private static String shortName(String classPath) {
		return classPath.substring(classPath.lastIndexOf('.') + 1);
	}
}
