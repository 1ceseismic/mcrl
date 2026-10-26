package dev.mcrl.client.fx.particle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.mcrl.client.fx.particle.FxEmitterDef.Alignment;
import dev.mcrl.client.fx.particle.FxEmitterDef.Blend;
import dev.mcrl.client.fx.particle.FxEmitterDef.Burst;
import dev.mcrl.client.fx.particle.FxEmitterDef.Kind;
import dev.mcrl.client.fx.particle.FxEmitterDef.PerUnit;
import dev.mcrl.client.fx.particle.FxEmitterDef.Required;
import dev.mcrl.client.fx.particle.FxEmitterDef.Sort;
import dev.mcrl.client.fx.particle.FxEmitterDef.Spawn;
import dev.mcrl.client.fx.particle.FxEmitterDef.SubUv;
import dev.mcrl.client.fx.particle.FxEmitterDef.TypeData;
import dev.mcrl.client.rl.RlCache;
import dev.mcrl.rlassets.model.Distribution;
import dev.mcrl.rlassets.model.Effects;
import dev.mcrl.rlassets.model.MaterialDef;
import dev.mcrl.rlassets.model.Materials;

public final class FxDefs {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();
	private static final String REQUIRED = "ParticleModuleRequired", SPAWN = "ParticleModuleSpawn",
			PER_UNIT = "ParticleModuleSpawnPerUnit";
	static final Set<String> KNOWN = Set.of("ParticleModuleLifetime", "ParticleModuleSize",
			"ParticleModuleSizeMultiplyLife", "ParticleModuleSizeScale", "ParticleModuleColor",
			"ParticleModuleColorOverLife", "ParticleModuleColorScaleOverLife", "ParticleModuleVelocity",
			"ParticleModuleVelocityOverLifetime", "ParticleModuleVelocityInheritParent", "ParticleModuleAcceleration",
			"ParticleModuleAccelerationOverLifetime", "ParticleModuleRotation", "ParticleModuleRotationRate",
			"ParticleModuleMeshRotation", "ParticleModuleMeshRotationRate", "ParticleModuleLocation",
			"ParticleModuleLocationWorldOffset", "ParticleModuleLocationPrimitiveSphere",
			"ParticleModuleLocationPrimitiveCylinder", "ParticleModuleLocationEmitter", "ParticleModuleSourceMovement", "ParticleModuleOrbit",
			"ParticleModuleSubUV", "ParticleModuleParameterDynamic", "ParticleModuleCameraOffset",
			"ParticleModuleCollision", "ParticleModuleOrientationAxisLock", "ParticleModuleMaterialByParameter",
			"ParticleModuleTrailSource", "ParticleModuleBeamSource", "ParticleModuleBeamTarget",
			"ParticleModuleBeamNoise");

	private static @Nullable RlCache cachedFor;
	private static final Map<String, FxSystemDef> CACHED = new HashMap<>();

	private FxDefs() {
	}

	public static synchronized @Nullable FxSystemDef system(RlCache cache, String path) {
		if (cachedFor != cache) {
			cachedFor = cache;
			CACHED.clear();
		}
		FxSystemDef s = CACHED.get(path);
		if (s == null) {
			Effects effects = cache.effectsWith(path);
			if (effects == null) {
				return null;
			}
			Effects.ParticleSystemDef def = effects.systems().get(path);
			s = compile(def, effects.meshes(), cache.allMaterials());
			CACHED.put(path, s);
		}
		return s;
	}

	public static Blend blend(Materials materials, String material) {
		List<MaterialDef> chain = materials.chain(material);
		if (chain.isEmpty()) {
			return Blend.TRANSLUCENT;
		}
		for (MaterialDef d : chain) {
			if (d.blendMode() != null) {
				return switch (d.blendMode()) {
					case "BLEND_Additive" -> Blend.ADDITIVE;
					case "BLEND_Masked", "BLEND_SoftMasked" -> Blend.MASKED;
					case "BLEND_Opaque" -> Blend.OPAQUE;
					default -> Blend.TRANSLUCENT;
				};
			}
		}
		return Blend.OPAQUE;
	}

	private static FxSystemDef compile(Effects.ParticleSystemDef system, Map<String, String> meshes,
			Materials materials) {
		List<FxEmitterDef> out = new ArrayList<>();
		List<Effects.EmitterDef> emitters = system.emitters();
		for (int i = 0; i < emitters.size(); i++) {
			FxEmitterDef e = emitter(i, emitters.get(i), meshes, materials);
			if (e != null) {
				out.add(e);
			}
		}
		return new FxSystemDef(system.path(), out);
	}

	private static @Nullable FxEmitterDef emitter(int index, Effects.EmitterDef e, Map<String, String> meshes,
			Materials materials) {
		if (e.cookedOut()) {
			return null;
		}
		JsonElement lod0 = e.props().get("LOD0");
		if (lod0 != null && lod0.isJsonObject() && !bool(lod0.getAsJsonObject(), "bEnabled", true)) {
			return null;
		}
		Effects.ModuleDef required = null;
		for (Effects.ModuleDef m : e.modules()) {
			if (m.className().equals(REQUIRED)) {
				required = m;
				break;
			}
		}
		if (required == null || !required.enabled()) {
			return null;
		}
		Spawn spawn = Spawn.NONE;
		PerUnit perUnit = null;
		TypeData typeData = TypeData.SPRITE;
		List<FxModule> modules = new ArrayList<>();
		for (Effects.ModuleDef m : e.modules()) {
			String cls = m.className();
			if (m == required || !m.enabled()) {
				continue;
			}
			if (cls.equals(SPAWN)) {
				spawn = spawn(m);
			} else if (cls.equals(PER_UNIT)) {
				perUnit = perUnit(m);
			} else if (cls.startsWith("ParticleModuleTypeData")) {
				typeData = typeData(m, meshes);
			} else if (KNOWN.contains(cls)) {
				modules.add(module(m));
			} else if (LOGGED.add(cls)) {
				LOG.warn("mcrl: particle module " + cls + " is not supported; ignored (first seen in emitter "
						+ index + " " + e.name() + ")");
			}
		}
		return new FxEmitterDef(index, e.name(), required(required.props(), materials), spawn, perUnit, typeData,
				modules);
	}

	private static Required required(JsonObject p, Materials materials) {
		String material = string(p, "Material", "");
		int maxDraw = bool(p, "bUseMaxDrawCount", true) ? integer(p, "MaxDrawCount", 500) : -1;
		return new Required(material, material.isEmpty() ? Blend.TRANSLUCENT : blend(materials, material),
				Math.max(1, integer(p, "SubImages_Horizontal", 1)), Math.max(1, integer(p, "SubImages_Vertical", 1)),
				subUv(string(p, "InterpolationMethod", "PSUVIM_None")),
				alignment(string(p, "ScreenAlignment", "PSA_Square")), bool(p, "bUseLocalSpace", false),
				number(p, "EmitterDuration", 1), integer(p, "EmitterLoops", 0), number(p, "EmitterDelay", 0),
				bool(p, "bDelayFirstLoopOnly", false), bool(p, "bKillOnDeactivate", false),
				bool(p, "bKillOnCompleted", false), maxDraw, sort(string(p, "SortMode", "PSORTMODE_None")));
	}

	private static Spawn spawn(Effects.ModuleDef m) {
		JsonObject p = m.props();
		List<Burst> bursts = new ArrayList<>();
		JsonElement list = p.get("BurstList");
		if (list != null && list.isJsonArray()) {
			for (JsonElement b : (JsonArray) list) {
				JsonObject o = b.getAsJsonObject();
				bursts.add(new Burst(number(o, "Time", 0), integer(o, "Count", 0), integer(o, "CountLow", -1)));
			}
		}
		return new Spawn(m.dist("Rate"), m.dist("RateScale"), bool(p, "bProcessSpawnRate", true), bursts,
				bool(p, "bProcessBurstList", true));
	}

	private static PerUnit perUnit(Effects.ModuleDef m) {
		JsonObject p = m.props();
		return new PerUnit(number(p, "UnitScalar", 50), dist(m, "SpawnPerUnit"), number(p, "MovementTolerance", 0.1f),
				number(p, "MaxFrameDistance", 0), bool(p, "bProcessSpawnRate", true),
				bool(p, "bIgnoreSpawnRateWhenMoving", false));
	}

	private static TypeData typeData(Effects.ModuleDef m, Map<String, String> meshes) {
		JsonObject p = m.props();
		return switch (m.className()) {
			case "ParticleModuleTypeDataMesh" -> {
				String ref = string(p, "Mesh", "");
				yield new TypeData(Kind.MESH, ref.isEmpty() ? null : meshes.get(ref), bool(p, "bOverrideMaterial", false),
						null, null);
			}
			case "ParticleModuleTypeDataRibbon" -> new TypeData(Kind.RIBBON, null, false, new FxEmitterDef.Ribbon(
					Math.max(1, integer(p, "MaxTrailCount", 1)), Math.max(1, integer(p, "MaxParticleInTrailCount", 500)),
					string(p, "RenderAxis", "Trails_CameraUp").equals("Trails_WorldUp"),
					number(p, "TilingDistance", 0), bool(p, "bClipSourceSegement", false)), null);
			case "ParticleModuleTypeDataBeam2" -> new TypeData(Kind.BEAM, null, false, null,
					new FxEmitterDef.Beam(Math.max(1, integer(p, "MaxBeamCount", 1))));
			default -> {
				if (LOGGED.add(m.className())) {
					LOG.warn("mcrl: particle type data " + m.className() + " is not supported; drawn as sprites");
				}
				yield TypeData.SPRITE;
			}
		};
	}

	private static FxModule module(Effects.ModuleDef m) {
		JsonObject p = m.props();
		return switch (m.className()) {
			case "ParticleModuleLifetime" -> new FxModule.Lifetime(dist(m, "LifeTime"));
			case "ParticleModuleSize" -> new FxModule.Size(vector(m, "StartSize", 0));
			case "ParticleModuleSizeMultiplyLife" -> new FxModule.SizeMultiplyLife(vector(m, "LifeMultiplier", 1),
					bool(p, "MultiplyX", true), bool(p, "MultiplyY", true), bool(p, "MultiplyZ", true));
			case "ParticleModuleSizeScale" -> new FxModule.SizeScale(vector(m, "SizeScale", 1), bool(p, "EnableX", true),
					bool(p, "EnableY", true), bool(p, "EnableZ", true));
			case "ParticleModuleColor" -> new FxModule.Color(vector(m, "StartColor", 1), scalar(m, "StartAlpha", 1),
					bool(p, "bClampAlpha", true));
			case "ParticleModuleColorOverLife" -> new FxModule.ColorOverLife(vector(m, "ColorOverLife", 1),
					scalar(m, "AlphaOverLife", 1), bool(p, "bClampAlpha", true));
			case "ParticleModuleColorScaleOverLife" -> new FxModule.ColorScaleOverLife(
					vector(m, "ColorScaleOverLife", 1), scalar(m, "AlphaScaleOverLife", 1), bool(p, "bEmitterTime", false));
			case "ParticleModuleVelocity" -> new FxModule.Velocity(vector(m, "StartVelocity", 0),
					scalar(m, "StartVelocityRadial", 0), bool(p, "bInWorldSpace", false));
			case "ParticleModuleVelocityOverLifetime" -> new FxModule.VelocityOverLifetime(vector(m, "VelOverLife", 1),
					bool(p, "Absolute", false), bool(p, "bInWorldSpace", false));
			case "ParticleModuleVelocityInheritParent" -> new FxModule.VelocityInheritParent(vector(m, "Scale", 1),
					bool(p, "bInWorldSpace", false), number(p, "MaxAddedVelocity", 0));
			case "ParticleModuleAcceleration" -> new FxModule.Acceleration(vector(m, "Acceleration", 0),
					bool(p, "bAlwaysInWorldSpace", false));
			case "ParticleModuleAccelerationOverLifetime" -> new FxModule.AccelerationOverLifetime(
					vector(m, "AccelOverLife", 0), bool(p, "bAlwaysInWorldSpace", false));
			case "ParticleModuleRotation" -> new FxModule.Rotation(scalar(m, "StartRotation", 0));
			case "ParticleModuleRotationRate" -> new FxModule.RotationRate(scalar(m, "StartRotationRate", 0));
			case "ParticleModuleMeshRotation" -> new FxModule.MeshRotation(vector(m, "StartRotation", 0));
			case "ParticleModuleMeshRotationRate" -> new FxModule.MeshRotationRate(vector(m, "StartRotationRate", 0));
			case "ParticleModuleLocation" -> new FxModule.Location(vector(m, "StartLocation", 0));
			case "ParticleModuleLocationWorldOffset" -> new FxModule.LocationWorldOffset(
					vector(m, "StartLocation", 0));
			case "ParticleModuleLocationPrimitiveSphere" -> new FxModule.LocationSphere(vector(m, "StartLocation", 0),
					scalar(m, "StartRadius", 50), bool(p, "SurfaceOnly", false), bool(p, "Velocity", false),
					scalar(m, "VelocityScale", 1), axes(p));
			case "ParticleModuleLocationPrimitiveCylinder" -> new FxModule.LocationCylinder(
					vector(m, "StartLocation", 0), scalar(m, "StartRadius", 50), scalar(m, "StartHeight", 50),
					switch (string(p, "HeightAxis", "PMLPC_HEIGHTAXIS_Z")) {
						case "PMLPC_HEIGHTAXIS_X" -> 0;
						case "PMLPC_HEIGHTAXIS_Y" -> 1;
						default -> 2;
					}, bool(p, "SurfaceOnly", false), bool(p, "Velocity", false), bool(p, "RadialVelocity", true),
					scalar(m, "VelocityScale", 1), axes(p));
			case "ParticleModuleLocationEmitter" -> new FxModule.LocationEmitter(string(p, "EmitterName", "None"),
					bool(p, "InheritSourceVelocity", false), number(p, "InheritSourceVelocityScale", 1));
			case "ParticleModuleSourceMovement" -> new FxModule.SourceMovement(vector(m, "SourceMovementScale", 1));
			case "ParticleModuleOrbit" -> new FxModule.Orbit(vector(m, "OffsetAmount", 0), orbitOptions(p,
					"OffsetOptions"), vector(m, "RotationAmount", 0), orbitOptions(p, "RotationOptions"),
					vector(m, "RotationRateAmount", 0), orbitOptions(p, "RotationRateOptions"));
			case "ParticleModuleSubUV" -> new FxModule.SubUV(scalar(m, "SubImageIndex", 0));
			case "ParticleModuleParameterDynamic" -> new FxModule.ParameterDynamic(dynamicParams(p));
			case "ParticleModuleCameraOffset" -> new FxModule.CameraOffset(scalar(m, "CameraOffset", 1));
			case "ParticleModuleCollision" -> new FxModule.Collision(vector(m, "DampingFactor", 1),
					scalar(m, "MaxCollisions", 1), switch (string(p, "CollisionCompletionOption", "EPCC_Kill")) {
						case "EPCC_Kill" -> FxModule.Completion.KILL;
						case "EPCC_HaltCollisions" -> FxModule.Completion.HALT;
						default -> FxModule.Completion.FREEZE;
					}, scalar(m, "DelayAmount", 0));
			case "ParticleModuleOrientationAxisLock" -> new FxModule.AxisLock(lock(string(p, "LockAxisFlags",
					"EPAL_NONE")));
			case "ParticleModuleMaterialByParameter" -> new FxModule.MaterialByParameter(strings(p, "DefaultMaterials"));
			case "ParticleModuleTrailSource" -> new FxModule.TrailSource(offsets(p));
			case "ParticleModuleBeamSource" -> new FxModule.BeamSource(vector(m, "Source", 0));
			case "ParticleModuleBeamTarget" -> new FxModule.BeamTarget(vector(m, "Target", 0));
			case "ParticleModuleBeamNoise" -> new FxModule.BeamNoise(vector(m, "NoiseRange", 0),
					scalar(m, "NoiseRangeScale", 1), bool(p, "bNRScaleEmitterTime", false),
					Math.max(1, integer(p, "Frequency", 0)), number(p, "NoiseLockTime", 0));
			default -> new FxModule.Other(m.className());
		};
	}

	private static FxModule.OrbitOptions orbitOptions(JsonObject p, String key) {
		JsonElement e = p.get(key);
		JsonObject o = e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
		return new FxModule.OrbitOptions(bool(o, "bProcessDuringSpawn", true), bool(o, "bProcessDuringUpdate", false),
				bool(o, "bUseEmitterTime", false));
	}

	private static List<FxModule.DynParam> dynamicParams(JsonObject p) {
		List<FxModule.DynParam> out = new ArrayList<>();
		JsonElement list = p.get("DynamicParams");
		if (list != null && list.isJsonArray()) {
			for (JsonElement e : (JsonArray) list) {
				JsonObject o = e.getAsJsonObject();
				JsonElement value = o.get("ParamValue");
				Distribution d = value != null && value.isJsonObject() && value.getAsJsonObject().has("$dist")
						? Distribution.fromJson(value.getAsJsonObject()) : constant(0);
				out.add(new FxModule.DynParam(string(o, "ParamName", "None"), d, bool(o, "bUseEmitterTime", false),
						bool(o, "bSpawnTimeOnly", false)));
			}
		}
		return out;
	}

	private static float[] offsets(JsonObject p) {
		JsonElement list = p.get("SourceOffsetDefaults");
		int n = integer(p, "SourceOffsetCount", 0);
		if (list == null || !list.isJsonArray()) {
			return new float[0];
		}
		JsonArray a = list.getAsJsonArray();
		n = Math.min(n, a.size());
		float[] out = new float[3 * n];
		for (int k = 0; k < n; k++) {
			JsonObject o = a.get(k).getAsJsonObject();
			out[3 * k] = number(o, "X", 0);
			out[3 * k + 1] = number(o, "Y", 0);
			out[3 * k + 2] = number(o, "Z", 0);
		}
		return out;
	}

	private static int axes(JsonObject p) {
		return (bool(p, "Positive_X", true) ? FxModule.POS_X : 0) | (bool(p, "Negative_X", true) ? FxModule.NEG_X : 0)
				| (bool(p, "Positive_Y", true) ? FxModule.POS_Y : 0) | (bool(p, "Negative_Y", true) ? FxModule.NEG_Y : 0)
				| (bool(p, "Positive_Z", true) ? FxModule.POS_Z : 0) | (bool(p, "Negative_Z", true) ? FxModule.NEG_Z : 0);
	}

	private static FxModule.Lock lock(String s) {
		return switch (s) {
			case "EPAL_X" -> FxModule.Lock.X;
			case "EPAL_Y" -> FxModule.Lock.Y;
			case "EPAL_Z" -> FxModule.Lock.Z;
			case "EPAL_NEGATIVE_X" -> FxModule.Lock.NEG_X;
			case "EPAL_NEGATIVE_Y" -> FxModule.Lock.NEG_Y;
			case "EPAL_NEGATIVE_Z" -> FxModule.Lock.NEG_Z;
			case "EPAL_ROTATE_X" -> FxModule.Lock.ROTATE_X;
			case "EPAL_ROTATE_Y" -> FxModule.Lock.ROTATE_Y;
			case "EPAL_ROTATE_Z" -> FxModule.Lock.ROTATE_Z;
			default -> FxModule.Lock.NONE;
		};
	}

	private static List<String> strings(JsonObject p, String key) {
		List<String> out = new ArrayList<>();
		JsonElement list = p.get(key);
		if (list != null && list.isJsonArray()) {
			for (JsonElement e : (JsonArray) list) {
				out.add(e.isJsonNull() ? "None" : e.getAsString());
			}
		}
		return out;
	}

	static Distribution dist(Effects.ModuleDef m, String key) {
		return scalar(m, key, 0);
	}

	private static Distribution scalar(Effects.ModuleDef m, String key, float fallback) {
		Distribution d = m.dist(key);
		return d != null ? d : constant(fallback);
	}

	private static Distribution vector(Effects.ModuleDef m, String key, float fallback) {
		Distribution d = m.dist(key);
		return d != null ? d : constant(fallback, fallback, fallback);
	}

	static Distribution constant(float... v) {
		float[] t = new float[2 + 2 * v.length];
		t[0] = v[0];
		t[1] = v[0];
		System.arraycopy(v, 0, t, 2, v.length);
		System.arraycopy(v, 0, t, 2 + v.length, v.length);
		return new Distribution(1, v.length, 1, 0, 0, t, null);
	}

	private static SubUv subUv(String s) {
		return switch (s) {
			case "PSUVIM_Linear" -> SubUv.LINEAR;
			case "PSUVIM_Linear_Blend" -> SubUv.LINEAR_BLEND;
			case "PSUVIM_Random" -> SubUv.RANDOM;
			case "PSUVIM_Random_Blend" -> SubUv.RANDOM_BLEND;
			default -> SubUv.NONE;
		};
	}

	private static Alignment alignment(String s) {
		return switch (s) {
			case "PSA_Rectangle" -> Alignment.RECTANGLE;
			case "PSA_Velocity" -> Alignment.VELOCITY;
			case "PSA_TypeSpecific" -> Alignment.TYPE_SPECIFIC;
			default -> Alignment.SQUARE;
		};
	}

	private static Sort sort(String s) {
		return switch (s.toUpperCase(Locale.ROOT)) {
			case "PSORTMODE_VIEWPROJDEPTH" -> Sort.VIEW_PROJ_DEPTH;
			case "PSORTMODE_DISTANCETOVIEW" -> Sort.DISTANCE_TO_VIEW;
			case "PSORTMODE_AGE_OLDESTFIRST" -> Sort.AGE_OLDEST_FIRST;
			case "PSORTMODE_AGE_NEWESTFIRST" -> Sort.AGE_NEWEST_FIRST;
			default -> Sort.NONE;
		};
	}

	static boolean bool(JsonObject o, String key, boolean fallback) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsBoolean() : fallback;
	}

	static float number(JsonObject o, String key, float fallback) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsFloat() : fallback;
	}

	static int integer(JsonObject o, String key, int fallback) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsInt() : fallback;
	}

	static String string(JsonObject o, String key, String fallback) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
	}
}
