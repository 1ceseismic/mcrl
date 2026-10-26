package dev.mcrl.rlassets.extract;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcrl.rlassets.model.AttachmentDef;
import dev.mcrl.rlassets.model.CacheJson;
import dev.mcrl.rlassets.model.FxActorDef;
import dev.mcrl.rlassets.upk.UObject;
import dev.mcrl.rlassets.upk.UpkReader;
import dev.mcrl.rlassets.upk.UpkRef;
import dev.mcrl.rlassets.upk.UpkWorld;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public final class FxExtractor {
	public static final Map<String, String> ROOTS = ordered(
			"car", "TAGame:FXActors.Car.Car_FXActor",
			"body", "Body_Octane_SF:Body_Octane.FXActor",
			"boost", "Boost_Standard_SF:Boost_Standard.FX.FXActor",
			"supersonicTrail", "ss_default_SF:ss_default.Archetypes.SS_Default_FXActor",
			"ball", "GameInfo_Soccar_SF:FXActors.Ball.Ball_FXActor",
			"spawn", "Startup:Body_FX.Spawn_FXActor",
			"demolish", "TAGame:FXActors.Explosion.Demolish_FXActor",
			"leave", "TAGame:FX_LeaveGame.Leave_FXActor",
			"goalExplosion", "Explosion_Default_SF:Explosion_Default.Archetype.Explosion_Default_FXActor",
			"boostPadUsed", "Stadium_P:Pickup_Boost.BoostPad_FXActor",
			"boostPillUsed", "Stadium_P:Pickup_Boost.BoostPill_FXActor");
	public static final Map<String, String> OBJECTS = ordered(
			"flipReset", "GameInfo_Soccar_SF:Archetypes.Car.Car_Default.FlipResetFXActorArchetype",
			"ballDefault", "GameInfo_Soccar_SF:Archetypes.Ball.Ball_Default",
			"boostMesh", "Startup:Parent_Boost_Mesh.Archetype.Parent_Boost_Mesh",
			"wheelEffectsMap", "TAGame:EffectsMaps.Wheel.Wheel_EffectsMap",
			"carImpactEffectsMap", "TAGame:EffectsMaps.Impact.CarImpact_EffectsMap");

	static final String COMPONENT = "Engine.ActorComponent", SYSTEM = "Engine.ParticleSystem",
			TYPE_DATA_MESH = "Engine.ParticleModuleTypeDataMesh";
	static final List<String> MATERIALS = List.of("Engine.Material", "Engine.MaterialInstanceConstant");
	static final String LENS_FLARE = "Engine.LensFlare";
	private static final Set<String> ATTACHMENT_FIELDS = Set.of("Name", "SocketOrBoneName", "Component", "AttachAny",
			"AttachAll", "DetachAny", "AttachDelay", "DetachDelay", "LifeTime", "Target");
	private static final List<String> LOD_MODULES = List.of("RequiredModule", "SpawnModule", "TypeDataModule",
			"EventGenerator", "Modules", "SpawningModules", "SpawnModules", "UpdateModules", "OrbitModules",
			"EventReceiverModules");
	private static final int MAX_INLINE_DEPTH = 8;

	public record Result(JsonObject effects, JsonObject fxActors, Set<String> materialRefs, Set<String> meshRefs) {
	}

	private final UpkWorld w;
	private final List<String> warnings;
	private final Map<String, Optional<String>> classes = new HashMap<>();
	private final Set<String> systemRefs = new LinkedHashSet<>();
	private final Deque<String> systemQueue = new ArrayDeque<>();
	private final Set<String> materialRefs = new LinkedHashSet<>();
	private final Set<String> lensFlareRefs = new HashSet<>();
	private final Map<String, String> meshes = new LinkedHashMap<>();

	private FxExtractor(UpkWorld w, List<String> warnings) {
		this.w = w;
		this.warnings = warnings;
	}

	public static Result extract(UpkWorld w, List<String> warnings) {
		return extract(w, ROOTS, OBJECTS, warnings);
	}

	public static Result extract(UpkWorld w, Map<String, String> roots, Map<String, String> objects, List<String> warnings) {
		return new FxExtractor(w, warnings).run(roots, objects);
	}

	private Result run(Map<String, String> roots, Map<String, String> objects) {
		Map<String, FxActorDef> actors = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : roots.entrySet()) {
			try {
				FxActorDef d = actor(e.getKey(), e.getValue());
				if (d != null) {
					actors.put(e.getKey(), d);
				}
			} catch (RuntimeException ex) {
				warnings.add("FX actor " + e.getKey() + " " + e.getValue() + " skipped: it does not decode (" + ex + ")");
			}
		}
		JsonObject objs = new JsonObject();
		for (Map.Entry<String, String> e : objects.entrySet()) {
			try {
				UObject o = find(e.getValue(), "FX object " + e.getKey());
				if (o != null) {
					Map<String, Object> v = decoded(o, o.path());
					scan(v);
					objs.add(e.getKey(), UJson.object(v));
				}
			} catch (RuntimeException ex) {
				warnings.add("FX object " + e.getKey() + " " + e.getValue() + " skipped: it does not decode (" + ex + ")");
			}
		}
		JsonObject systems = new JsonObject();
		while (!systemQueue.isEmpty()) {
			String path = systemQueue.poll();
			try {
				JsonObject s = system(path);
				if (s != null) {
					systems.add(path, s);
				}
			} catch (RuntimeException ex) {
				warnings.add("particle system " + path + " skipped: it does not decode (" + ex + ")");
			}
		}
		JsonObject effects = new JsonObject();
		effects.add("systems", systems);
		JsonObject m = new JsonObject();
		meshes.forEach(m::addProperty);
		effects.add("meshes", m);
		JsonObject fx = new JsonObject();
		fx.add("actors", CacheJson.fxActors(actors));
		fx.add("objects", objs);
		return new Result(effects, fx, materialRefs, meshes.keySet());
	}

	private @Nullable FxActorDef actor(String key, String rootPath) {
		UObject o = find(rootPath, "FX actor " + key);
		if (o == null) {
			return null;
		}
		Map<String, Object> props = inlined(o.props(), o.path());
		List<Map<String, Object>> atts = attachments(props);
		Set<String> names = new HashSet<>();
		atts.forEach(a -> names.add(name(a)));
		Set<String> seen = new HashSet<>(Set.of(o.path()));
		for (Object parent = props.get("Parent"); parent instanceof UpkRef p && seen.add(p.path());) {
			UObject po = find(p.path(), "FX actor " + key + "'s parent");
			if (po == null) {
				break;
			}
			Map<String, Object> pp = inlined(po.props(), po.path());
			for (Map<String, Object> a : attachments(pp)) {
				if (names.add(name(a))) {
					atts.add(a);
				}
			}
			parent = pp.get("Parent");
		}
		props.put("bRenderInactive", Boolean.TRUE.equals(props.get("bRenderInactive")));
		scan(props);
		List<AttachmentDef> defs = new ArrayList<>();
		for (Map<String, Object> a : atts) {
			scan(a);
			defs.add(attachment(key, a));
		}
		return new FxActorDef(key, o.path(), shortName(o.classPath()), defs, UJson.object(props));
	}

	private static List<Map<String, Object>> attachments(Map<String, Object> props) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (props.remove("Attachments") instanceof List<?> l) {
			for (Object a : l) {
				if (a instanceof Map<?, ?> m) {
					out.add(copy(m));
				}
			}
		}
		return out;
	}

	private AttachmentDef attachment(String key, Map<String, Object> a) {
		String socket = a.get("SocketOrBoneName") instanceof String s && !s.isEmpty() && !s.equals("None") ? s : null;
		String componentClass = "";
		JsonObject component = new JsonObject();
		Object c = a.get("Component");
		if (c instanceof Map<?, ?> m) {
			componentClass = m.get("$class") instanceof String s ? s : "";
			component = UJson.object(m);
		} else if (c instanceof UpkRef r) {
			warnings.add("FX actor " + key + ": attachment " + name(a) + "'s component " + r.path() + " was not found");
			component.addProperty("$path", r.path());
		}
		Map<String, Object> rest = new LinkedHashMap<>(a);
		rest.keySet().removeAll(ATTACHMENT_FIELDS);
		return new AttachmentDef(name(a), socket, states(a.get("AttachAny")), states(a.get("AttachAll")),
				states(a.get("DetachAny")), num(a.get("AttachDelay")), num(a.get("DetachDelay")), num(a.get("LifeTime")),
				target(key, name(a), a.get("Target")), componentClass, component, UJson.object(rest));
	}

	private static String name(Map<String, Object> a) {
		return a.get("Name") instanceof String s ? s : "None";
	}

	private static List<String> states(@Nullable Object list) {
		List<String> out = new ArrayList<>();
		if (list instanceof List<?> l) {
			for (Object x : l) {
				if (x instanceof UpkRef r) {
					String p = r.path();
					out.add(p.substring(Math.max(p.lastIndexOf(':'), p.lastIndexOf('.')) + 1));
				}
			}
		}
		return out;
	}

	private AttachmentDef.Target target(String key, String att, @Nullable Object t) {
		String s = t instanceof String x ? x : "FXComponentTarget_All";
		if (s.endsWith("_NonLocal")) {
			return AttachmentDef.Target.NON_LOCAL;
		}
		if (s.endsWith("_Local")) {
			return AttachmentDef.Target.LOCAL;
		}
		if (!s.endsWith("_All")) {
			warnings.add("FX actor " + key + ": attachment " + att + " has target " + s + ", read as All");
		}
		return AttachmentDef.Target.ALL;
	}

	private Map<String, Object> decoded(UObject o, String root) {
		Map<String, Object> v = new LinkedHashMap<>();
		v.put("$path", o.path());
		v.put("$class", shortName(o.classPath()));
		v.putAll(inlined(o.props(), root));
		return v;
	}

	private Map<String, Object> inlined(Map<String, Object> props, String root) {
		Set<String> visiting = new HashSet<>();
		Map<String, Object> out = new LinkedHashMap<>();
		props.forEach((k, x) -> out.put(k, inline(x, root, visiting, 0)));
		return out;
	}

	private @Nullable Object inline(@Nullable Object v, String root, Set<String> visiting, int depth) {
		if (v instanceof Map<?, ?> m) {
			Map<String, Object> out = new LinkedHashMap<>();
			m.forEach((k, x) -> out.put((String) k, inline(x, root, visiting, depth)));
			return out;
		}
		if (v instanceof List<?> l) {
			List<Object> out = new ArrayList<>(l.size());
			l.forEach(x -> out.add(inline(x, root, visiting, depth)));
			return out;
		}
		if (v instanceof UpkRef r && depth < MAX_INLINE_DEPTH) {
			String path = r.path(), cp = classOf(path);
			if (cp != null && (path.startsWith(root + ".") || w.schema().isA(cp, COMPONENT)) && visiting.add(path)) {
				try {
					UpkWorld.Loc l = w.locate(path, null);
					UObject o = w.obj(l.pkg(), l.index());
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("$path", path);
					out.put("$class", shortName(cp));
					o.props().forEach((k, x) -> out.put(k, inline(x, root, visiting, depth + 1)));
					return out;
				} finally {
					visiting.remove(path);
				}
			}
		}
		return v;
	}

	private @Nullable JsonObject system(String path) {
		UObject ps = find(path, "particle system");
		if (ps == null) {
			return null;
		}
		Map<String, Object> props = new LinkedHashMap<>(ps.props());
		JsonArray lod = new JsonArray();
		if (props.get("LODDistances") instanceof List<?> l) {
			l.forEach(x -> lod.add(UJson.encode(x)));
		}
		JsonArray emitters = new JsonArray();
		if (props.remove("Emitters") instanceof List<?> l) {
			for (Object e : l) {
				emitters.add(emitter(path, e));
			}
		}
		Map<String, Object> p = params(props);
		scan(p);
		JsonObject s = new JsonObject();
		s.add("lodDistances", lod);
		s.add("props", UJson.object(p));
		s.add("emitters", emitters);
		return s;
	}

	private JsonObject emitter(String system, @Nullable Object ref) {
		JsonObject e = new JsonObject();
		UObject em = ref instanceof UpkRef r ? find(r.path(), "emitter of " + system) : null;
		if (em == null) {
			e.addProperty("name", "None");
			e.addProperty("class", "None");
			e.addProperty("cookedOut", true);
			e.add("props", new JsonObject());
			e.add("modules", new JsonArray());
			return e;
		}
		Map<String, Object> props = new LinkedHashMap<>(em.props());
		JsonArray modules = new JsonArray();
		if (props.remove("LODLevels") instanceof List<?> lods && !lods.isEmpty() && lods.get(0) instanceof UpkRef l0) {
			UObject lod = find(l0.path(), "LOD 0 of " + em.path());
			if (lod != null) {
				Map<String, Object> lp = new LinkedHashMap<>(lod.props());
				for (String k : LOD_MODULES.subList(0, 5)) {
					Object v = lp.get(k);
					for (Object m : v instanceof List<?> list ? list : Collections.singletonList(v)) {
						if (m instanceof UpkRef mr) {
							JsonObject mo = module(mr.path());
							if (mo != null) {
								modules.add(mo);
							}
						}
					}
				}
				lp.keySet().removeAll(LOD_MODULES);
				props.put("LOD0", lp);
			}
		}
		Map<String, Object> p = params(props);
		scan(p);
		e.addProperty("name", em.props().get("EmitterName") instanceof String n ? n : "None");
		e.addProperty("class", shortName(em.classPath()));
		e.addProperty("cookedOut", Boolean.TRUE.equals(em.props().get("bCookedOut")));
		e.add("props", UJson.object(p));
		e.add("modules", modules);
		return e;
	}

	private @Nullable JsonObject module(String path) {
		UObject m = find(path, "particle module");
		if (m == null) {
			return null;
		}
		Map<String, Object> p = params(m.props());
		scan(p);
		if (w.schema().isA(m.classPath(), TYPE_DATA_MESH) && p.get("Mesh") instanceof UpkRef mesh) {
			mesh(mesh.path());
		}
		JsonObject o = new JsonObject();
		o.addProperty("class", shortName(m.classPath()));
		o.addProperty("enabled", Boolean.TRUE.equals(m.props().get("bEnabled")));
		o.add("props", UJson.object(p));
		return o;
	}

	private void mesh(String ref) {
		if (meshes.containsKey(ref)) {
			return;
		}
		String base = "fx_" + ref.substring(Math.max(ref.lastIndexOf(':'), ref.lastIndexOf('.')) + 1)
				.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
		String name = base;
		for (int i = 2; meshes.containsValue(name); i++) {
			name = base + "_" + i;
		}
		if (!name.equals(base)) {
			warnings.add("FX mesh " + ref + " shares its name with another; cached as " + name);
		}
		meshes.put(ref, name);
	}

	private Map<String, Object> params(Map<String, Object> props) {
		Map<String, Object> out = new LinkedHashMap<>();
		props.forEach((k, x) -> out.put(k, param(x)));
		return out;
	}

	private @Nullable Object param(@Nullable Object v) {
		if (v instanceof Map<?, ?> m) {
			Map<String, Object> out = new LinkedHashMap<>();
			m.forEach((k, x) -> out.put((String) k, param(x)));
			if (UJson.isRawDistribution(m) && m.get("Distribution") instanceof UpkRef r) {
				String cp = classOf(r.path());
				if (cp != null && cp.endsWith("ParticleParameter")) {
					UpkWorld.Loc l = w.locate(r.path(), null);
					UObject o = w.obj(l.pkg(), l.index());
					Map<String, Object> d = new LinkedHashMap<>();
					d.put("$class", shortName(cp));
					d.putAll(o.props());
					out.put("Distribution", d);
				}
			}
			return out;
		}
		if (v instanceof List<?> l) {
			List<Object> out = new ArrayList<>(l.size());
			l.forEach(x -> out.add(param(x)));
			return out;
		}
		return v;
	}

	private void scan(@Nullable Object v) {
		if (v instanceof Map<?, ?> m) {
			m.values().forEach(this::scan);
		} else if (v instanceof List<?> l) {
			l.forEach(this::scan);
		} else if (v instanceof UpkRef r) {
			String cp = classOf(r.path());
			if (cp == null) {
				return;
			}
			if (w.schema().isA(cp, SYSTEM)) {
				if (systemRefs.add(r.path())) {
					systemQueue.add(r.path());
				}
			} else if (MATERIALS.stream().anyMatch(b -> w.schema().isA(cp, b))) {
				materialRefs.add(r.path());
			} else if (w.schema().isA(cp, LENS_FLARE) && lensFlareRefs.add(r.path())) {
				UObject lf = find(r.path(), "lens flare");
				if (lf != null) {
					scan(lf.props());
				}
			}
		}
	}

	private @Nullable String classOf(String path) {
		return classes.computeIfAbsent(path, p -> {
			UpkWorld.Loc l = w.locate(p, null);
			UpkReader r = l == null ? null : w.pkg(l.pkg());
			return r == null ? Optional.empty()
					: Optional.of(UpkWorld.classPath(r, r.exports().get(l.index() - 1).classIndex()));
		}).orElse(null);
	}

	private @Nullable UObject find(String path, String what) {
		UpkWorld.Loc l = w.locate(path, null);
		if (l == null) {
			warnings.add(what + " " + path + " not found");
			return null;
		}
		return w.obj(l.pkg(), l.index());
	}

	private static String shortName(String classPath) {
		return classPath.substring(classPath.lastIndexOf('.') + 1);
	}

	private static float num(@Nullable Object v) {
		return v instanceof Number n ? n.floatValue() : 0f;
	}

	private static Map<String, Object> copy(Map<?, ?> m) {
		Map<String, Object> out = new LinkedHashMap<>();
		m.forEach((k, x) -> out.put((String) k, x));
		return out;
	}

	private static Map<String, String> ordered(String... kv) {
		Map<String, String> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			m.put(kv[i], kv[i + 1]);
		}
		return Collections.unmodifiableMap(m);
	}
}
